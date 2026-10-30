package cloud.ohiofiles.app.ui.preview

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Choreographer
import android.view.TextureView
import android.view.MotionEvent
import android.widget.FrameLayout
import android.opengl.Matrix
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.document.*
import cloud.ohiofiles.app.data.model.FileItem
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.google.android.filament.utils.Manipulator
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer

object NativeModelLoader {
    suspend fun load(url: String, file: FileItem, api: OhioApiClient?, key: String?): NativeModelAsset {
        val bytes = PreviewIO.bytes(url, 64 * 1024 * 1024)
        val suffix = file.displayName.substringAfterLast('.').lowercase()
        if (suffix == "obj") return withContext(Dispatchers.Default) { ModelGeometry.glb(ModelGeometry.obj(bytes.toString(Charsets.UTF_8))) }
        if (suffix == "stl") return withContext(Dispatchers.Default) { ModelGeometry.glb(ModelGeometry.stl(bytes)) }
        val base = NativeModelAsset(bytes, suffix == "gltf")
        val (document, internalResources) = withContext(Dispatchers.Default) { ModelGeometry.unpack(base) }
        val references = ((document["buffers"] as? JsonArray).orEmpty() + (document["images"] as? JsonArray).orEmpty()).mapNotNull { it.jsonObject["uri"]?.jsonPrimitive?.content }.distinct()
        require(references.size <= 100) { "Model references too many external assets" }
        val resources = internalResources.toMutableMap(); var total = bytes.size.toLong()
        val folderFiles = mutableMapOf<String, List<FileItem>>()
        for (reference in references) {
            currentCoroutineContext().ensureActive()
            if (reference in resources) continue
            val data = if (reference.startsWith("data:")) {
                require(reference.substringBefore(',').endsWith(";base64")) { "Unsupported embedded model asset" }; require(reference.length <= 32 * 1024 * 1024) { "Embedded model asset is too large" }; Base64.decode(reference.substringAfter(','), Base64.DEFAULT)
            } else {
                require(!reference.startsWith("//") && !reference.startsWith("file:") && !reference.startsWith("content:")) { "Model asset location is not allowed" }
                val resourceUrl = url.toHttpUrl().resolve(reference) ?: error("Invalid model asset address")
                val origin = url.toHttpUrl(); require(resourceUrl.scheme == "https" && resourceUrl.host == origin.host && resourceUrl.port == origin.port) { "External model assets must use the same HTTPS origin" }
                var resolved: String? = null
                if (api != null && !reference.contains("://") && !reference.startsWith('/') && reference.substringBefore('?') == reference) {
                    val parts = android.net.Uri.decode(reference.removePrefix("./")).split('/')
                    if (parts.none { it == ".." || it.isBlank() }) {
                        var folder = file.folderId; var found = true
                        for (part in parts.dropLast(1)) { val child = api.listFolders(key, folder).getOrThrow().folders.firstOrNull { it.name == part }; if (child == null) { found = false; break }; folder = child.id }
                        if (found) {
                            val cacheKey = folder ?: "root"
                            if (cacheKey !in folderFiles) { val all = mutableListOf<FileItem>(); var page = 1; do { val listing = api.listFiles(key, folder, page = page, limit = 100).getOrThrow(); all += listing.files; page++ } while (listing.hasMore && page <= 100); folderFiles[cacheKey] = all }
                            folderFiles[cacheKey]?.firstOrNull { it.displayName == parts.last() }?.let { resolved = api.getFileDownloadUrl(it.id, key, it.slug) }
                        }
                    }
                }
                PreviewIO.bytes(resolved ?: resourceUrl.toString(), 24 * 1024 * 1024)
            }
            total += data.size; require(total <= 128L * 1024 * 1024) { "Model and assets exceed the 128 MiB preview limit" }; resources[reference] = data
        }
        // Validate image dimensions before native decoders allocate texture memory.
        (document["images"] as? JsonArray).orEmpty().forEach { value ->
            val image = value.jsonObject; val uri = image["uri"]?.jsonPrimitive?.content
            val data: ByteArray?; val offset: Int; val length: Int
            if (uri != null) { data = resources[uri]; offset = 0; length = data?.size ?: 0 }
            else {
                val view = image["bufferView"]?.jsonPrimitive?.int?.let { document["bufferViews"]!!.jsonArray[it].jsonObject }
                val buffer = view?.get("buffer")?.jsonPrimitive?.int?.let { document["buffers"]!!.jsonArray[it].jsonObject }
                data = buffer?.get("uri")?.jsonPrimitive?.content?.let(resources::get); offset = view?.get("byteOffset")?.jsonPrimitive?.int ?: 0; length = view?.get("byteLength")?.jsonPrimitive?.int ?: 0
            }
            if (data != null && length > 0) { require(offset >= 0 && length <= data.size - offset) { "Model texture range is invalid" }; val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, offset, length, bounds); require(bounds.outWidth <= 8192 && bounds.outHeight <= 8192 && bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216L) { "Model texture exceeds the 16 megapixel preview limit" } }
        }
        // Use JSON plus explicitly preloaded resource buffers: no native loader performs arbitrary network requests.
        return NativeModelAsset(document.toString().toByteArray(), true, resources)
    }
}

private class ModelTextureView(context: Context) : TextureView(context) {
    var modelViewer: ModelViewer? = null
    var beforeDetach: (() -> Unit)? = null
    private var downX = 0f; private var downY = 0f; private var dragged = false
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragged = false }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) > 12 * resources.displayMetrics.density) dragged = true
            MotionEvent.ACTION_POINTER_DOWN -> dragged = true
            MotionEvent.ACTION_UP -> if (!dragged) performClick()
        }
        modelViewer?.onTouchEvent(event); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { beforeDetach?.invoke(); beforeDetach = null; modelViewer = null; super.onDetachedFromWindow() }
}
private class FilamentPreviewView(context: Context) : FrameLayout(context) {
    val texture = ModelTextureView(context)
    val manipulator: Manipulator
    val viewer: ModelViewer
    private var indirect: IndirectLight? = null; private var skybox: Skybox? = null
    private var environmentReleased = false
    private val choreographer = Choreographer.getInstance(); private var rendering = false; private var animate = true; private var start = 0L; private var autoRotate = false
    private var initialTransform: FloatArray? = null
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!rendering) return
            if (start == 0L) start = time
            if (animate) viewer.animator?.let { animator -> if (animator.animationCount > 0) { animator.applyAnimation(0, (time - start) / 1_000_000_000f); animator.updateBoneMatrices() } }
            if (autoRotate) initialTransform?.let { base -> val rotation = FloatArray(16); Matrix.setIdentityM(rotation, 0); Matrix.translateM(rotation, 0, 0f, 0f, -4f); Matrix.rotateM(rotation, 0, ((time - start) / 100_000_000f) % 360f, 0f, 1f, 0f); Matrix.translateM(rotation, 0, 0f, 0f, 4f); val transform = FloatArray(16); Matrix.multiplyMM(transform, 0, rotation, 0, base, 0); viewer.asset?.let { viewer.engine.transformManager.setTransform(viewer.engine.transformManager.getInstance(it.root), transform) } }
            viewer.render(time); choreographer.postFrameCallback(this)
        }
    }
    init {
        Utils.init(); addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        manipulator = Manipulator.Builder().targetPosition(0f, 0f, -4f).viewport(1080, 1920).build(Manipulator.Mode.ORBIT)
        viewer = ModelViewer(texture, manipulator = manipulator)
        texture.contentDescription = "3D model. Drag to orbit, use two fingers to pan, and pinch to zoom."
        texture.modelViewer = viewer
        texture.beforeDetach = { releaseEnvironment() }
        viewer.engine.lightManager.setDirection(viewer.engine.lightManager.getInstance(viewer.light), .3f, -1f, -1f)
        indirect = IndirectLight.Builder().irradiance(3, floatArrayOf(.7f, .7f, .7f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).intensity(30_000f).build(viewer.engine)
        skybox = Skybox.Builder().color(.055f, .065f, .09f, 1f).build(viewer.engine)
        viewer.scene.indirectLight = indirect; viewer.scene.skybox = skybox
    }
    fun load(asset: NativeModelAsset) {
        if (asset.json) viewer.loadModelGltf(ByteBuffer.wrap(asset.bytes)) { uri -> asset.resources[uri]?.let(ByteBuffer::wrap) }
        else viewer.loadModelGlb(ByteBuffer.wrap(asset.bytes))
        check(viewer.asset != null) { "This model could not be decoded" }; viewer.transformToUnitCube(); start = 0L
        initialTransform = FloatArray(16).also { viewer.engine.transformManager.getTransform(viewer.engine.transformManager.getInstance(viewer.asset!!.root), it) }
    }
    fun animate(enabled: Boolean) { animate = enabled }
    fun zoom(inward: Boolean) { manipulator.scroll(texture.width / 2, texture.height / 2, if (inward) -12f else 12f) }
    fun moveCamera(horizontal: Int, vertical: Int, pan: Boolean) {
        val x = texture.width / 2; val y = texture.height / 2; val distance = (minOf(texture.width, texture.height) * .12f).toInt().coerceAtLeast(12)
        manipulator.grabBegin(x, y, pan); manipulator.grabUpdate(x + horizontal * distance, y + vertical * distance); manipulator.grabEnd()
    }
    fun autoRotate(enabled: Boolean) { autoRotate = enabled }
    fun dimensions(): FloatArray = viewer.asset?.boundingBox?.halfExtent?.map { it * 2 }?.toFloatArray() ?: FloatArray(3)
    fun background(theme: String) { viewer.scene.skybox = null; skybox?.let(viewer.engine::destroySkybox); val color = when (theme) { "Light" -> floatArrayOf(.93f, .94f, .96f); "Neutral" -> floatArrayOf(.16f, .17f, .19f); "Dark" -> floatArrayOf(.025f, .025f, .03f); else -> floatArrayOf(.055f, .065f, .09f) }; skybox = Skybox.Builder().color(color[0], color[1], color[2], 1f).build(viewer.engine); viewer.scene.skybox = skybox }
    fun reset() { manipulator.jumpToBookmark(manipulator.homeBookmark); initialTransform?.let { transform -> viewer.asset?.let { viewer.engine.transformManager.setTransform(viewer.engine.transformManager.getInstance(it.root), transform) } } }
    fun resume() { if (!rendering) { rendering = true; choreographer.postFrameCallback(frame) } }
    fun pause() { rendering = false; choreographer.removeFrameCallback(frame) }
    private fun releaseEnvironment() {
        if (environmentReleased) return
        environmentReleased = true; pause(); viewer.scene.indirectLight = null; viewer.scene.skybox = null
        indirect?.let(viewer.engine::destroyIndirectLight); skybox?.let(viewer.engine::destroySkybox); indirect = null; skybox = null
    }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativeModelPreview(url: String, file: FileItem, api: OhioApiClient? = null, sessionKey: String? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var asset by remember(url) { mutableStateOf<NativeModelAsset?>(null) }; var wireAsset by remember(url) { mutableStateOf<NativeModelAsset?>(null) }
    var view by remember(url) { mutableStateOf<FilamentPreviewView?>(null) }; var wireframe by remember { mutableStateOf(false) }; var animate by remember { mutableStateOf(true) }
    var grid by remember { mutableStateOf(true) }; var rotate by remember { mutableStateOf(false) }; var background by remember { mutableStateOf("Studio") }; var stats by remember { mutableStateOf("") }; var counts by remember { mutableStateOf(0 to 0) }
    var error by remember { mutableStateOf<String?>(null) }; var wireError by remember { mutableStateOf<String?>(null) }
    var cameraControls by remember { mutableStateOf(false) }
    LaunchedEffect(url) { try { val model = NativeModelLoader.load(url, file, api, sessionKey); asset = model; counts = withContext(Dispatchers.Default) { ModelGeometry.statistics(model) } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Model could not load" } }
    LaunchedEffect(asset, background) { val model = asset ?: return@LaunchedEffect; wireAsset = null; withContext(Dispatchers.Default) { runCatching { ModelGeometry.wireframe(model, background == "Light") } }.onSuccess { wireAsset = it; wireError = null }.onFailure { wireError = it.message } }
    val displayedAsset = if (wireframe) wireAsset else asset
    LaunchedEffect(view, displayedAsset, grid) { val target = view; val model = displayedAsset; if (target != null && model != null) try { target.load(model); val dimensions = target.dimensions(); if (grid) { val visible = withContext(Dispatchers.Default) { ModelGeometry.grid(model) }; target.load(visible) }; stats = "${counts.first} vertices · ${counts.second} triangles · " + dimensions.joinToString(" × ") { "%.2f".format(it) }; target.background(background); target.autoRotate(rotate); target.animate(animate); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    DisposableEffect(view, lifecycle) {
        val target = view; val observer = LifecycleEventObserver { _, event -> when (event) { Lifecycle.Event.ON_START -> target?.resume(); Lifecycle.Event.ON_STOP -> target?.pause(); else -> Unit } }
        lifecycle.addObserver(observer); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) target?.resume()
        onDispose { lifecycle.removeObserver(observer); target?.pause() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(wireframe, { wireframe = !wireframe }, { Text("Wireframe") }, enabled = wireAsset != null)
            Spacer(Modifier.width(8.dp)); FilterChip(grid, { grid = !grid }, { Text("Grid") }); Spacer(Modifier.width(8.dp)); FilterChip(rotate, { rotate = !rotate; view?.autoRotate(rotate) }, { Text("Auto rotate") })
            IconButton(onClick = { animate = !animate; view?.animate(animate) }) { Icon(if (animate) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (animate) "Pause model animation" else "Play model animation") }
            IconButton(onClick = { view?.reset() }) { Icon(Icons.Outlined.CenterFocusStrong, "Reset model camera") }
            IconButton(onClick = { cameraControls = true }, enabled = view != null) { Icon(Icons.Outlined.OpenWith, "Accessible camera controls") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Studio", "Dark", "Light", "Neutral").forEach { theme -> FilterChip(background == theme, { background = theme; view?.background(theme) }, { Text(theme) }) } }
        if (asset == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        wireError?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Drag to orbit · two fingers to pan · pinch to zoom", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
        if (stats.isNotBlank()) Text(stats, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        if (asset != null) AndroidView(factory = { context -> FilamentPreviewView(context).also { view = it } }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
    if (cameraControls) ModalBottomSheet(onDismissRequest = { cameraControls = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera controls", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { view?.zoom(true) }) { Text("Zoom in") }; OutlinedButton(onClick = { view?.zoom(false) }) { Text("Zoom out") }; TextButton(onClick = { view?.reset() }) { Text("Reset") } }
            Text("Orbit model", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Left" to (-1 to 0), "Right" to (1 to 0), "Up" to (0 to -1), "Down" to (0 to 1)).forEach { (label, direction) -> TextButton(onClick = { view?.moveCamera(direction.first, direction.second, false) }) { Text(label) } } }
            Text("Pan model", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Left" to (-1 to 0), "Right" to (1 to 0), "Up" to (0 to -1), "Down" to (0 to 1)).forEach { (label, direction) -> TextButton(onClick = { view?.moveCamera(direction.first, direction.second, true) }) { Text(label) } } }
        }
    }
}
