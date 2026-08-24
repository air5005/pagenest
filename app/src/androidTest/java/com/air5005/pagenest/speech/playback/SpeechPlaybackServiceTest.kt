package com.air5005.pagenest.speech.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.NotificationManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeechPlaybackServiceTest {
    @Test
    fun serviceIsPrivateMediaPlaybackForegroundServiceAndRecreatesPaused() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, SpeechPlaybackService::class.java)
        val info = context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))

        assertFalse(info.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        val controller = MediaController.Builder(context, SessionToken(context, component)).buildAsync().get(10, TimeUnit.SECONDS)
        try {
            assertNotNull(
                context.getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(SpeechPlaybackService.NOTIFICATION_CHANNEL_ID),
            )
            controller.play()
            controller.pause()
        } finally {
            controller.release()
        }
    }
}
