package com.air5005.pagenest.speech.security

import java.nio.file.Files
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KeystoreSpeechCredentialStoreTest {
    @Test
    fun `stored blob hides all plaintext and clear removes it`() = runTest {
        val root = Files.createTempDirectory("speech-credentials").toFile()
        val backingFile = root.resolve("speech-secrets/azure.bin")
        val store = KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider())

        store.saveAzure("credential-value", "eastasia")

        val persisted = backingFile.readBytes()
        assertFalse(persisted.toString(Charsets.UTF_8).contains("credential-value"))
        assertFalse(persisted.toString(Charsets.UTF_8).contains("eastasia"))
        assertEquals(AzureCredentials("credential-value", "eastasia"), store.loadAzure())

        store.clearAzure()

        assertFalse(backingFile.exists())
        assertNull(store.loadAzure())
    }

    @Test
    fun `save replaces an existing credential without leaving a temporary publication`() = runTest {
        val root = Files.createTempDirectory("speech-credentials-replace").toFile()
        val store = KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider())

        store.saveAzure("first-value", "eastasia")
        store.saveAzure("replacement-value", "westus2")

        assertEquals(AzureCredentials("replacement-value", "westus2"), store.loadAzure())
        assertEquals(listOf("azure.bin"), root.resolve("speech-secrets").list()?.sorted())
    }

    @Test
    fun `invalid region is rejected before any ciphertext is published`() {
        val root = Files.createTempDirectory("speech-credentials-invalid").toFile()
        val store = KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider())

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.test.runTest { store.saveAzure("credential-value", "eastasia.example.com") }
        }
        assertFalse(root.resolve("speech-secrets/azure.bin").exists())
    }
}

private class FixedSecretKeyProvider : SecretKeyProvider {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")

    override fun getOrCreate(): SecretKey = key
}
