package cloud.ohiofiles.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.ui.screens.nativefeatures.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.unifiedpush.android.connector.UnifiedPush

@RunWith(AndroidJUnit4::class)
class PrivatePushParityTest {
    @Test fun ownedQaReceivesActualEncryptedPushWithTheActivityClosed() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName == "cloud.ohiofiles.app.qa")
        val api = OhioApiClient(); val sessions = SessionRepository(context, api)
        val key = sessions.getSessionKey() ?: error("Run the guarded account fixture first")
        check(key in context.getSharedPreferences("qa_identity_registry", Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty())
        api.setBaseUrl(sessions.getServerUrl())
        if (Build.VERSION.SDK_INT >= 33) check(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Grant notifications to the isolated QA companion externally before this test" }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val distributors = UnifiedPush.getDistributors(context)
            check(context.packageName in distributors) { "The embedded push distributor is unavailable on this device" }
            withContext(Dispatchers.Main) { UnifiedPush.saveDistributor(context, context.packageName) }
            NotificationDelivery.setEnabled(context, true)
            NativePush.bind(context, api.getBaseUrl(), key)
            NativePush.current(context)?.let { NativePush.work(context, it, "register") }
            withTimeout(120_000) { while (!NativePush.state(context).startsWith("Push connected")) delay(250) }
            scenario.close()
            val before = NativePush.prefs(context).getLong("last_delivery", 0)
            api.request("notifications/push/test", "POST", api.body("sessionKey" to key)).getOrThrow()
            withTimeout(60_000) { while (NativePush.prefs(context).getLong("last_delivery", 0) <= before) delay(250) }
            assertTrue((context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.any { Build.VERSION.SDK_INT < 26 || it.notification.channelId == "ohiofiles_updates" })
        } finally {
            scenario.close()
            NativePush.disable(context)
            withTimeoutOrNull(30_000) { while (NativePush.current(context)?.let { NativePush.record(context, it) } != null) delay(250) }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
            // QA is a separate UID. Its granted OS permission disappears when the companion is removed.
        }
    }
}
