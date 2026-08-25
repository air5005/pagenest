package com.air5005.pagenest.speech

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.air5005.pagenest.speech.security.KeystoreSpeechCredentialStore
import com.wxn.reader.R
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class SpeechKeystoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearCredentialFixture() = runTest {
        KeystoreSpeechCredentialStore(context.filesDir).clearAzure()
    }

    @Test
    fun encryptedCredentialSurvivesStoreReopenAndClears() = runTest {
        val firstStore = KeystoreSpeechCredentialStore(context.filesDir)
        firstStore.clearAzure()
        firstStore.saveAzure("instrumented-secret", "eastasia")

        val reopened = KeystoreSpeechCredentialStore(context.filesDir)
        assertEquals("instrumented-secret", reopened.loadAzure()?.key)
        assertEquals("eastasia", reopened.loadAzure()?.region)

        reopened.clearAzure()
        assertNull(KeystoreSpeechCredentialStore(context.filesDir).loadAzure())
    }

    @Test
    fun credentialFileContainsCiphertextInsteadOfPlaintext() = runTest {
        val secret = "instrumented-secret-never-plaintext"
        KeystoreSpeechCredentialStore(context.filesDir).apply {
            clearAzure()
            saveAzure(secret, "eastasia")
        }

        val stored = File(context.filesDir, "speech-secrets/azure.bin").readBytes()

        assertFalse(stored.toString(Charsets.UTF_8).contains(secret))
        assertTrue(stored.size > secret.toByteArray().size)
    }

    @Test
    fun packagedBackupPoliciesExcludeEverySpeechSensitiveStore() {
        val expected = setOf(
            "speech-secrets/",
            "speech-cache/",
            "speech-cache-staging/",
            "datastore/speech_preferences.preferences_pb",
        )

        assertEquals(expected, excludedFilePaths(R.xml.backup_rules))
        assertEquals(expected, excludedFilePaths(R.xml.data_extraction_rules))
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    private fun excludedFilePaths(resourceId: Int): Set<String> {
        val parser = context.resources.getXml(resourceId)
        return buildSet {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" &&
                    parser.getAttributeValue(null, "domain") == "file"
                ) {
                    parser.getAttributeValue(null, "path")?.let(::add)
                }
                parser.next()
            }
        }.also { parser.close() }
    }
}
