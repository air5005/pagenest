package com.air5005.pagenest.speech.security

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `write failure preserves old credential and removes temporary ciphertext`() = runTest {
        assertFailedPublicationIsClean(PublicationFailure.WRITE)
    }

    @Test
    fun `sync failure preserves old credential and removes temporary ciphertext`() = runTest {
        assertFailedPublicationIsClean(PublicationFailure.SYNC)
    }

    @Test
    fun `move failure preserves old credential and removes temporary ciphertext`() = runTest {
        assertFailedPublicationIsClean(PublicationFailure.MOVE)
    }

    @Test
    fun `oversized UTF-16 key is rejected before secret encoding allocates bytes`() = runTest {
        val root = Files.createTempDirectory("speech-credentials-char-bound").toFile()
        val encoder = RecordingSecretEncoder()
        val store = KeystoreSpeechCredentialStore(
            root,
            FixedSecretKeyProvider(),
            secretEncoder = encoder,
        )

        val failure = runCatching { store.saveAzure("x".repeat(4_097), "eastasia") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, encoder.calls)
        assertFalse(root.resolve("speech-secrets/azure.bin").exists())
    }

    @Test
    fun `multibyte key exceeding byte bound is wiped after validation failure`() = runTest {
        val root = Files.createTempDirectory("speech-credentials-byte-bound").toFile()
        val encoder = RecordingSecretEncoder()
        val store = KeystoreSpeechCredentialStore(
            root,
            FixedSecretKeyProvider(),
            secretEncoder = encoder,
        )

        val failure = runCatching { store.saveAzure("界".repeat(2_000), "eastasia") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, encoder.calls)
        assertTrue(encoder.lastBytes!!.all { it == 0.toByte() })
        assertFalse(root.resolve("speech-secrets/azure.bin").exists())
    }

    @Test
    fun `corrupted and truncated ciphertext are rejected`() = runTest {
        val root = Files.createTempDirectory("speech-credentials-corrupt").toFile()
        val file = root.resolve("speech-secrets/azure.bin")
        val store = KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider())
        store.saveAzure("credential-value", "eastasia")
        val valid = file.readBytes()

        file.writeBytes(valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertNull(store.loadAzure())

        file.writeBytes(valid.copyOf(10))
        assertNull(store.loadAzure())
    }

    @Test
    fun `ciphertext cannot be loaded with a different secret key`() = runTest {
        val root = Files.createTempDirectory("speech-credentials-wrong-key").toFile()
        KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider(seed = 1)).saveAzure(
            "credential-value",
            "eastasia",
        )

        val loaded = KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider(seed = 31)).loadAzure()

        assertNull(loaded)
    }

    @Test
    fun `concurrent save load and clear never expose partial credentials or temporary files`() = runTest {
        val root = Files.createTempDirectory("speech-credentials-concurrent").toFile()
        val store = KeystoreSpeechCredentialStore(root, FixedSecretKeyProvider())
        val candidates = (0 until 12).map { AzureCredentials("value-$it", "eastasia") }
        val observed = Collections.synchronizedList(mutableListOf<AzureCredentials?>())

        coroutineScope {
            candidates.forEach { candidate ->
                launch {
                    store.saveAzure(candidate.key, candidate.region)
                    observed += store.loadAzure()
                }
            }
            launch { store.clearAzure() }
        }

        assertTrue(observed.all { it == null || it in candidates })
        store.clearAzure()
        assertNull(store.loadAzure())
        assertFalse(root.resolve("speech-secrets/azure.bin.tmp").exists())
    }

    private suspend fun assertFailedPublicationIsClean(failurePoint: PublicationFailure) {
        val root = Files.createTempDirectory("speech-credentials-publication").toFile()
        val keyProvider = FixedSecretKeyProvider()
        val healthy = KeystoreSpeechCredentialStore(root, keyProvider)
        healthy.saveAzure("original-value", "eastasia")
        val failing = KeystoreSpeechCredentialStore(
            root,
            keyProvider,
            publication = FailingCredentialPublication(failurePoint),
        )

        val failure = runCatching {
            failing.saveAzure("replacement-private-value", "westus2")
        }.exceptionOrNull()

        assertEquals("Credential storage operation failed", failure?.message)
        assertNull(failure?.cause)
        assertFalse(failure?.message.orEmpty().contains(root.absolutePath))
        assertFalse(failure?.message.orEmpty().contains("replacement-private-value"))
        assertEquals(AzureCredentials("original-value", "eastasia"), healthy.loadAzure())
        assertFalse(root.resolve("speech-secrets/azure.bin.tmp").exists())
    }
}

private class FixedSecretKeyProvider(seed: Int = 1) : SecretKeyProvider {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + seed).toByte() }, "AES")

    override fun getOrCreate(): SecretKey = key
}

private enum class PublicationFailure { WRITE, SYNC, MOVE }

private class FailingCredentialPublication(
    private val failure: PublicationFailure,
) : CredentialPublication {
    override fun prepare(directory: java.io.File) {
        Files.createDirectories(directory.toPath())
    }

    override fun writeAndSync(file: java.io.File, bytes: ByteArray) {
        java.io.FileOutputStream(file).use { output ->
            if (failure == PublicationFailure.WRITE) throw java.io.IOException("write failed")
            output.write(bytes)
            output.flush()
            if (failure == PublicationFailure.SYNC) throw java.io.IOException("sync failed")
            output.fd.sync()
        }
    }

    override fun atomicReplace(source: java.io.File, target: java.io.File) {
        if (failure == PublicationFailure.MOVE) throw java.io.IOException("move failed")
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun deleteIfExists(file: java.io.File) {
        Files.deleteIfExists(file.toPath())
    }
}

private class RecordingSecretEncoder : SecretEncoder {
    var calls = 0
    var lastBytes: ByteArray? = null

    override fun encode(value: String): ByteArray {
        calls++
        return value.toByteArray(Charsets.UTF_8).also { lastBytes = it }
    }
}
