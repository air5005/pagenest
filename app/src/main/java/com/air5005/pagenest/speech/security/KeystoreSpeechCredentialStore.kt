package com.air5005.pagenest.speech.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SecretKeyProvider {
    fun getOrCreate(): SecretKey
}

internal interface SecretEncoder {
    fun encode(value: String): ByteArray
}

private object Utf8SecretEncoder : SecretEncoder {
    override fun encode(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
}

internal interface CredentialPublication {
    fun prepare(directory: File)
    fun writeAndSync(file: File, bytes: ByteArray)
    fun atomicReplace(source: File, target: File)
    fun deleteIfExists(file: File)
}

private object AtomicFileCredentialPublication : CredentialPublication {
    override fun prepare(directory: File) {
        Files.createDirectories(directory.toPath())
    }

    override fun writeAndSync(file: File, bytes: ByteArray) {
        java.io.FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override fun atomicReplace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun deleteIfExists(file: File) {
        Files.deleteIfExists(file.toPath())
    }
}

class AndroidKeystoreSecretKeyProvider : SecretKeyProvider {
    override fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pagenest_speech_credentials_v1"
        const val AES_KEY_SIZE_BITS = 256
    }
}

class KeystoreSpeechCredentialStore private constructor(
    filesDir: File,
    private val secretKeyProvider: SecretKeyProvider,
    private val secureRandom: SecureRandom,
    private val publication: CredentialPublication,
    private val secretEncoder: SecretEncoder,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) : SpeechCredentialStore {
    constructor(
        filesDir: File,
        secretKeyProvider: SecretKeyProvider = AndroidKeystoreSecretKeyProvider(),
        secureRandom: SecureRandom = SecureRandom(),
    ) : this(
        filesDir,
        secretKeyProvider,
        secureRandom,
        AtomicFileCredentialPublication,
        Utf8SecretEncoder,
        Unit,
    )

    internal constructor(
        filesDir: File,
        secretKeyProvider: SecretKeyProvider,
        publication: CredentialPublication,
    ) : this(filesDir, secretKeyProvider, SecureRandom(), publication, Utf8SecretEncoder, Unit)

    internal constructor(
        filesDir: File,
        secretKeyProvider: SecretKeyProvider,
        secretEncoder: SecretEncoder,
    ) : this(
        filesDir,
        secretKeyProvider,
        SecureRandom(),
        AtomicFileCredentialPublication,
        secretEncoder,
        Unit,
    )

    private val secretDirectory = File(filesDir, SECRET_DIRECTORY)
    private val backingFile = File(secretDirectory, AZURE_FILE)
    private val mutex = Mutex()

    override suspend fun saveAzure(key: String, region: String) = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Azure credential is empty" }
        require(key.length <= MAX_KEY_CHARS) { "Azure credential is too large" }
        AzureRegionValidator.requireValid(region)
        mutex.withLock {
            var keyBytes: ByteArray? = null
            var regionBytes: ByteArray? = null
            var plaintext: ByteArray? = null
            var iv: ByteArray? = null
            var ciphertext: ByteArray? = null
            var published: ByteArray? = null
            try {
                keyBytes = secretEncoder.encode(key)
                require(keyBytes.size <= MAX_KEY_BYTES) { "Azure credential is too large" }
                regionBytes = secretEncoder.encode(region)
                plaintext = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + keyBytes.size + regionBytes.size)
                    .putInt(keyBytes.size)
                    .put(keyBytes)
                    .putInt(regionBytes.size)
                    .put(regionBytes)
                    .array()
                iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, secretKeyProvider.getOrCreate(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                ciphertext = cipher.doFinal(plaintext)
                published = iv + ciphertext
                publishAtomically(published)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                throw invalid
            } catch (_: Exception) {
                throw CredentialStorageException()
            } finally {
                keyBytes?.fill(0)
                regionBytes?.fill(0)
                plaintext?.fill(0)
                iv?.fill(0)
                ciphertext?.fill(0)
                published?.fill(0)
            }
        }
    }

    override suspend fun loadAzure(): AzureCredentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!backingFile.isFile) return@withLock null
            var published: ByteArray? = null
            var plaintext: ByteArray? = null
            try {
                if (backingFile.length() !in MIN_BLOB_BYTES.toLong()..MAX_BLOB_BYTES.toLong()) {
                    return@withLock null
                }
                published = backingFile.readBytes()
                val iv = published.copyOfRange(0, GCM_IV_BYTES)
                val encrypted = published.copyOfRange(GCM_IV_BYTES, published.size)
                try {
                    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                        init(Cipher.DECRYPT_MODE, secretKeyProvider.getOrCreate(), GCMParameterSpec(GCM_TAG_BITS, iv))
                    }
                    plaintext = cipher.doFinal(encrypted)
                } finally {
                    iv.fill(0)
                    encrypted.fill(0)
                }
                decodeCredentials(plaintext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                published?.fill(0)
                plaintext?.fill(0)
            }
        }
    }

    override suspend fun clearAzure() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                publication.deleteIfExists(backingFile)
                publication.deleteIfExists(File(secretDirectory, TEMP_FILE))
                Unit
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw CredentialStorageException()
            }
        }
    }

    private fun publishAtomically(bytes: ByteArray) {
        val temporary = File(secretDirectory, TEMP_FILE)
        var completed = false
        try {
            publication.prepare(secretDirectory)
            publication.writeAndSync(temporary, bytes)
            publication.atomicReplace(temporary, backingFile)
            completed = true
        } finally {
            if (!completed) {
                try {
                    publication.deleteIfExists(temporary)
                } catch (_: Exception) {
                    // Preserve the fixed outer storage failure and never attach a path-bearing cause.
                }
            }
        }
    }

    private fun decodeCredentials(bytes: ByteArray): AzureCredentials? {
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.remaining() < Int.SIZE_BYTES * 2) return null
        val keySize = buffer.int
        if (keySize !in 1..MAX_KEY_BYTES || buffer.remaining() < keySize + Int.SIZE_BYTES) return null
        val keyBytes = ByteArray(keySize).also(buffer::get)
        val regionSize = buffer.int
        if (regionSize !in 2..MAX_REGION_BYTES || buffer.remaining() != regionSize) {
            keyBytes.fill(0)
            return null
        }
        val regionBytes = ByteArray(regionSize).also(buffer::get)
        return try {
            val key = String(keyBytes, StandardCharsets.UTF_8)
            val region = String(regionBytes, StandardCharsets.UTF_8)
            if (key.isBlank() || !AzureRegionValidator.isValid(region)) null else AzureCredentials(key, region)
        } finally {
            keyBytes.fill(0)
            regionBytes.fill(0)
        }
    }

    private class CredentialStorageException : IllegalStateException("Credential storage operation failed")

    private companion object {
        const val SECRET_DIRECTORY = "speech-secrets"
        const val AZURE_FILE = "azure.bin"
        const val TEMP_FILE = "azure.bin.tmp"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_KEY_BYTES = 4_096
        const val MAX_KEY_CHARS = 4_096
        const val MAX_REGION_BYTES = 32
        const val MIN_BLOB_BYTES = GCM_IV_BYTES + (GCM_TAG_BITS / 8) + Int.SIZE_BYTES * 2 + 3
        const val MAX_BLOB_BYTES = GCM_IV_BYTES + (GCM_TAG_BITS / 8) + Int.SIZE_BYTES * 2 + MAX_KEY_BYTES + MAX_REGION_BYTES
    }
}
