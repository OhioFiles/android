package cloud.ohiofiles.app.ui.diagram

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.KeyEvent
import android.webkit.*
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class RenderedDiagram(val svg: String, val bitmap: Bitmap, val blockedRequests: Int)
private data class EngineDiagram(val svg: String, val bitmap: Bitmap?, val blockedRequests: Int)

/** The only WebView is an offline, credential-free SVG calculation engine, never app UI. */
object OfflineDiagramRenderer {
    private const val ORIGIN = "https://diagram.ohio.invalid"
    private const val INDEX = "$ORIGIN/assets/diagram/index.html"
    private val mutex = Mutex()
    private val cache = object : LruCache<String, RenderedDiagram>(16 * 1024 * 1024) { override fun sizeOf(key: String, value: RenderedDiagram) = value.bitmap.allocationByteCount + value.svg.length * 2 }
    suspend fun render(activity: Activity, input: String, dark: Boolean, timeoutMillis: Long = DiagramPolicy.TIMEOUT_MILLIS): RenderedDiagram {
        require(timeoutMillis in 1..DiagramPolicy.TIMEOUT_MILLIS)
        val source = withContext(Dispatchers.Default) { DiagramPolicy.source(input) }
        val cacheKey = withContext(Dispatchers.Default) { MessageDigest.getInstance("SHA-256").digest("${DiagramPolicy.VERSION}|$dark|$source".toByteArray()).joinToString("") { "%02x".format(it) } }
        cache.get(cacheKey)?.let { return it }
        return mutex.withLock {
            cache.get(cacheKey)?.let { return@withLock it }
            val engine = withContext(Dispatchers.Main) { calculate(activity, source, dark, timeoutMillis) }
            val result = withContext(Dispatchers.Default) {
                val safe = DiagramPolicy.svg(engine.svg, allowMath = engine.bitmap != null)
                engine.bitmap?.let { return@withContext RenderedDiagram(safe, it, engine.blockedRequests) }
                val svg = SVG.getFromString(safe)
                val box = svg.documentViewBox
                val width = (box?.width() ?: svg.documentWidth).takeIf { it.isFinite() && it > 0 } ?: 800f
                val height = (box?.height() ?: svg.documentHeight).takeIf { it.isFinite() && it > 0 } ?: 600f
                require(width <= 1_000_000f && height <= 1_000_000f) { "The diagram dimensions exceed the preview limit" }
                val scale = minOf(1600f / width, 1600f / height, 2f)
                val outputWidth = (width * scale).roundToInt().coerceIn(1, 1600); val outputHeight = (height * scale).roundToInt().coerceIn(1, 1600)
                val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                try { svg.setDocumentWidth(outputWidth.toFloat()); svg.setDocumentHeight(outputHeight.toFloat()); svg.renderToCanvas(Canvas(bitmap)) }
                catch (e: Throwable) { bitmap.recycle(); throw e }
                RenderedDiagram(safe, bitmap, engine.blockedRequests)
            }
            currentCoroutineContext().ensureActive(); cache.put(cacheKey, result); result
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun calculate(activity: Activity, source: String, dark: Boolean, timeoutMillis: Long): EngineDiagram = suspendCancellableCoroutine { continuation ->
        check(Looper.myLooper() == Looper.getMainLooper())
        val handler = Handler(Looper.getMainLooper()); var disposed = false; val blocked = AtomicInteger(); var begun = false; var snapshotJob: Job? = null
        val web = object : WebView(activity) {
            override fun dispatchTouchEvent(event: MotionEvent?) = false
            override fun dispatchKeyEvent(event: KeyEvent?) = false
        }
        val parent = activity.window.decorView as ViewGroup
        fun close() {
            if (disposed) return
            disposed = true; handler.removeCallbacksAndMessages(null); snapshotJob?.cancel()
            (web.parent as? ViewGroup)?.removeView(web); web.stopLoading(); web.loadUrl("about:blank"); web.removeAllViews(); web.destroy()
        }
        fun fail(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException(message)); close() }
        fun empty() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        continuation.invokeOnCancellation { handler.post { close() } }
        web.apply {
            alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; isFocusable = false
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = false; databaseEnabled = false
                allowFileAccess = false; allowContentAccess = false; javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                blockNetworkLoads = true; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; mediaPlaybackRequiresUserGesture = true
                if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
                @Suppress("DEPRECATION") run { allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false; saveFormData = false }
            }
            removeJavascriptInterface("searchBoxJavaBridge_"); removeJavascriptInterface("accessibility"); removeJavascriptInterface("accessibilityTraversal")
            CookieManager.getInstance().setAcceptCookie(false); CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ -> }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val uri = request?.url
                    val name = uri?.lastPathSegment.orEmpty()
                    val local = uri?.scheme == "https" && uri.host == "diagram.ohio.invalid" && uri.port == -1 && uri.path == "/assets/diagram/$name" && !name.contains("..")
                    val asset = if (local && (name in setOf("index.html", "mermaid-runtime.js", "mermaid-runtime.css") || name.startsWith("font-KaTeX_") && name.endsWith(".woff2") && name.matches(Regex("[A-Za-z0-9_.-]+")))) "diagram/$name" else null
                    if (request?.method != "GET" || asset == null) { blocked.incrementAndGet(); return empty() }
                    return try { WebResourceResponse(when { asset.endsWith(".js") -> "application/javascript"; asset.endsWith(".css") -> "text/css"; asset.endsWith(".woff2") -> "font/woff2"; else -> "text/html" }, if (asset.endsWith(".woff2")) null else "UTF-8", activity.assets.open(asset)) }
                    catch (_: Exception) { empty() }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                @Suppress("DEPRECATION") override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (disposed || begun || url != INDEX) return
                    begun = true
                    val request = buildJsonObject { put("source", source); put("dark", dark) }.toString()
                    web.evaluateJavascript("window.OhioMermaid&&window.OhioMermaid.render($request)", null)
                    val poll = object : Runnable {
                        override fun run() {
                            if (disposed || !continuation.isActive) return
                            web.evaluateJavascript("JSON.stringify(window.OhioMermaid?window.OhioMermaid.poll():{status:'loading'})") { encoded ->
                                if (disposed || !continuation.isActive) return@evaluateJavascript
                                try {
                                    val json = Json.parseToJsonElement(encoded).jsonPrimitive.content
                                    val result = Json.parseToJsonElement(json).jsonObject
                                    when (result["status"]?.jsonPrimitive?.content) {
                                        "complete" -> {
                                            val svg = result["svg"]?.jsonPrimitive?.content ?: error("Empty diagram"); require(svg.length <= DiagramPolicy.MAX_SVG_CHARACTERS)
                                            if (!svg.contains("foreignObject", true)) { continuation.resume(EngineDiagram(svg, null, blocked.get())); close() }
                                            else {
                                                snapshotJob = CoroutineScope(Dispatchers.Main).launch {
                                                    try {
                                                        val safe = withContext(Dispatchers.Default) { DiagramPolicy.svg(svg, allowMath = true) }
                                                        if (disposed || !continuation.isActive) return@launch
                                                        web.evaluateJavascript("window.OhioMermaid.snapshot(${JsonPrimitive(safe)})") { encodedSize ->
                                                            if (disposed || !continuation.isActive) return@evaluateJavascript
                                                            try {
                                                                val size = Json.parseToJsonElement(Json.parseToJsonElement(encodedSize).jsonPrimitive.content).jsonObject
                                                                val width = size["width"]!!.jsonPrimitive.int.coerceIn(1, 1600); val height = size["height"]!!.jsonPrimitive.int.coerceIn(1, 1600)
                                                                web.layoutParams = ViewGroup.LayoutParams(width, height); web.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); web.layout(0, 0, width, height)
                                                                handler.postDelayed({
                                                                    if (!disposed && continuation.isActive) {
                                                                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                                                        try { web.draw(Canvas(bitmap)); continuation.resume(EngineDiagram(safe, bitmap, blocked.get())); close() }
                                                                        catch (_: Exception) { bitmap.recycle(); fail("Embedded diagram math could not render. View its source.") }
                                                                    }
                                                                }, 250)
                                                            } catch (_: Exception) { fail("Embedded diagram math could not render. View its source.") }
                                                        }
                                                    } catch (e: CancellationException) { throw e } catch (_: Exception) { fail("Embedded diagram HTML is not supported. View its source.") }
                                                }
                                            }
                                        }
                                        "error" -> fail(result["message"]?.jsonPrimitive?.content?.take(300) ?: "The diagram could not render")
                                        else -> handler.postDelayed(this, 100)
                                    }
                                } catch (_: Exception) { fail("The diagram engine could not return a preview") }
                            }
                        }
                    }; handler.post(poll)
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { fail("The diagram renderer stopped. Try a smaller diagram."); return true }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { if (request?.isForMainFrame == true) fail("The offline diagram engine could not start") }
            }
        }
        parent.addView(web, ViewGroup.LayoutParams(1, 1)); web.loadUrl(INDEX)
        handler.postDelayed({ fail("Diagram rendering took too long. Simplify the diagram or view its source.") }, timeoutMillis)
    }
}
