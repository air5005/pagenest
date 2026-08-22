package com.air5005.pagenest.library.importing

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal object StrongTestPrivateBookStoreFileOperations :
    PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
    override fun openExistingBook(file: File): ExistingBookInput =
        JvmExistingBookInput.open(file, StrongTestJvmExistingBookFileOperations)

    override fun readJvmAttributes(file: File): BasicFileAttributes =
        StrongTestJvmExistingBookFileOperations.readAttributes(file.toPath())
}

internal object StrongTestJvmExistingBookFileOperations : JvmExistingBookFileOperations {
    override fun readAttributes(path: Path): BasicFileAttributes {
        val delegate = SystemJvmExistingBookFileOperations.readAttributes(path)
        val created = delegate.creationTime().toInstant()
        val digest = if (delegate.isRegularFile) digest(path) else null
        val identity = StrongTestFileIdentity(
            delegate.isDirectory,
            created.epochSecond,
            created.nano,
            delegate.size(),
            digest,
        )
        return object : BasicFileAttributes by delegate {
            override fun fileKey(): Any = identity
        }
    }

    override fun openChannel(path: Path): FileChannel =
        SystemJvmExistingBookFileOperations.openChannel(path)

    override fun openVerificationChannel(path: Path): FileChannel =
        SystemJvmExistingBookFileOperations.openVerificationChannel(path)

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(
            path,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.allocate(8 * 1024)
            while (channel.read(buffer) >= 0) {
                if (buffer.position() == 0) continue
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private data class StrongTestFileIdentity(
    val directory: Boolean,
    val createdSeconds: Long,
    val createdNanoseconds: Int,
    val size: Long,
    val digest: String?,
)

class PrivateBookStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyAbsoluteRootConstructorFailsClosedOnAndroidRuntime() {
        val originalVmName = System.getProperty("java.vm.name")
        val originalRuntimeName = System.getProperty("java.runtime.name")
        try {
            System.setProperty("java.vm.name", "Dalvik")
            System.setProperty("java.runtime.name", "Android Runtime")

            assertThrows(IOException::class.java) {
                PrivateBookStore(File(temporaryFolder.root, "books"))
            }
        } finally {
            System.setProperty("java.vm.name", originalVmName)
            System.setProperty("java.runtime.name", originalRuntimeName)
        }
    }

    @Test
    fun trustedRootRejectsSymlinkChildWithoutExternalWrites() {
        val trustedParent = temporaryFolder.newFolder("trusted-parent")
        val attacker = temporaryFolder.newFolder("trusted-child-attacker")
        Files.createSymbolicLink(File(trustedParent, "books").toPath(), attacker.toPath())
        val store = PrivateBookStore.inTrustedDirectory(
            trustedParent,
            "books",
            SystemPrivateBookStoreFileOperations,
        )

        assertThrows(IOException::class.java) {
            store.store("hello".byteInputStream(), "book.epub")
        }

        assertTrue(attacker.listFiles()!!.isEmpty())
    }

    @Test
    fun trustedRootRejectsInvalidChildBasenames() {
        val trustedParent = temporaryFolder.newFolder("trusted-name-parent")

        listOf("", ".", "..", "nested/books", "nested\\books", "nul\u0000name").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivateBookStore.inTrustedDirectory(
                    trustedParent,
                    name,
                    SystemPrivateBookStoreFileOperations,
                )
            }
        }
    }

    @Test
    fun duplicateContentUsesOnePrivateCopy() {
        val root = temporaryFolder.newFolder("books")
        val store = PrivateBookStore(root)

        val first = store.store("hello".byteInputStream(), "one.EPUB")
        val second = store.store("hello".byteInputStream(), "two.epub")

        assertEquals(first.file, second.file)
        assertFalse(first.wasExisting)
        assertTrue(second.wasExisting)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            first.sha256,
        )
        assertEquals("${first.sha256}.epub", first.file.name)
        assertArrayEquals("hello".toByteArray(), first.file.readBytes())
        assertEquals(1, root.listFiles()!!.count { !it.name.endsWith(".part") })
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun rejectsNamesWithoutASupportedBookExtension() {
        val root = temporaryFolder.newFolder("books")
        val store = PrivateBookStore(root)

        assertThrows(IllegalArgumentException::class.java) {
            store.store("hello".byteInputStream(), "archive.zip")
        }

        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun createsMissingPrivateDirectoryAndKeepsPartBesideFinalFile() {
        val root = File(temporaryFolder.root, "private/books")
        var partParent: File? = null
        var finalParent: File? = null
        val syncedDirectories = mutableListOf<File>()
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun publishAtomically(source: File, target: File) {
                partParent = source.parentFile
                finalParent = target.parentFile
                SystemPrivateBookStoreFileOperations.publishAtomically(source, target)
            }

            override fun syncDirectory(directory: File) {
                syncedDirectories += directory.canonicalFile
            }
        }

        val result = PrivateBookStore(root, operations)
            .store("hello".byteInputStream(), "book.txt")

        assertEquals(root.canonicalFile, partParent?.canonicalFile)
        assertEquals(root.canonicalFile, finalParent?.canonicalFile)
        assertEquals(root.canonicalFile, result.file.parentFile!!.canonicalFile)
        assertEquals(
            listOf(
                temporaryFolder.root.canonicalFile,
                File(temporaryFolder.root, "private").canonicalFile,
                root.canonicalFile,
                root.canonicalFile,
            ),
            syncedDirectories,
        )
    }

    @Test
    fun parentSyncFailureRollsBackNewPrivateRootBeforeCopying() {
        val parent = temporaryFolder.newFolder("private")
        val root = File(parent, "books")
        var parentSyncs = 0
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == parent.canonicalFile && parentSyncs++ == 0) {
                    throw IOException("parent sync failed")
                }
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("parent sync failed", failure.message)
        assertFalse(root.exists())
        assertTrue(parent.listFiles()!!.isEmpty())
    }

    @Test
    fun competingSymlinkAtMissingRootIsRejectedWithoutFollowingIt() {
        val parent = temporaryFolder.newFolder("private")
        val root = File(parent, "books")
        val symlinkTarget = temporaryFolder.newFolder("attacker-controlled")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun createDirectory(directory: File) {
                if (directory.absoluteFile == root.absoluteFile) {
                    Files.createSymbolicLink(directory.toPath(), symlinkTarget.toPath())
                    throw FileAlreadyExistsException(directory.path)
                }
                SystemPrivateBookStoreFileOperations.createDirectory(directory)
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("Private book path is not a directory: ${root.absoluteFile}", failure.message)
        assertTrue(Files.isSymbolicLink(root.toPath()))
        assertTrue(symlinkTarget.listFiles()!!.isEmpty())
    }

    @Test
    fun rootSwapAfterRevalidationDoesNotWriteThroughSymlink() {
        val root = temporaryFolder.newFolder("books")
        val symlinkTarget = temporaryFolder.newFolder("post-revalidation-attacker")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openRoot(
                rootDirectory: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = SystemPrivateBookStoreFileOperations.openRoot(
                    rootDirectory,
                    operations,
                )
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun createPart(): File {
                        Files.delete(rootDirectory.toPath())
                        Files.createSymbolicLink(rootDirectory.toPath(), symlinkTarget.toPath())
                        return delegate.createPart()
                    }
                }
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertTrue(Files.isSymbolicLink(root.toPath()))
        assertTrue(symlinkTarget.listFiles()!!.isEmpty())
    }

    @Test
    fun readFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
        var cleanupDirectorySyncs = 0
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) cleanupDirectorySyncs += 1
            }
        }
        val input = object : InputStream() {
            private var reads = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ == 0) {
                    buffer[offset] = 1
                    return 1
                }
                throw IOException("read failed")
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store(input, "book.pdf")
        }

        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1, cleanupDirectorySyncs)
    }

    @Test
    fun writeFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
        var cleanupDirectorySyncs = 0
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openPart(file: File): DurableBookOutput {
                val delegate = SystemPrivateBookStoreFileOperations.openPart(file)
                return object : DurableBookOutput by delegate {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        delegate.write(buffer, offset, 1)
                        throw IOException("write failed")
                    }
                }
            }

            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) cleanupDirectorySyncs += 1
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.mobi")
        }

        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1, cleanupDirectorySyncs)
    }

    @Test
    fun publicationFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
        var cleanupDirectorySyncs = 0
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun publishAtomically(source: File, target: File) {
                throw IOException("publication failed")
            }

            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) cleanupDirectorySyncs += 1
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.azw3")
        }

        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1, cleanupDirectorySyncs)
    }

    @Test
    fun syncsFileAndDirectoryEntriesInDurableOrder() {
        val root = temporaryFolder.newFolder("books")
        val events = mutableListOf<String>()
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openPart(file: File): DurableBookOutput {
                val delegate = SystemPrivateBookStoreFileOperations.openPart(file)
                return object : DurableBookOutput by delegate {
                    override fun flush() {
                        events += "flush"
                        delegate.flush()
                    }

                    override fun sync() {
                        events += "fileSync"
                        delegate.sync()
                    }

                    override fun close() {
                        events += "close"
                        delegate.close()
                    }
                }
            }

            override fun publishAtomically(source: File, target: File) {
                SystemPrivateBookStoreFileOperations.publishAtomically(source, target)
                events += "publish"
            }

            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile != root.canonicalFile) {
                    events += "parentSync"
                    return
                }
                val files = directory.listFiles()!!
                events += "directorySync(parts=${files.count { it.name.endsWith(".part") }}," +
                    "finals=${files.count { !it.name.endsWith(".part") }})"
            }
        }

        PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")

        assertEquals(
            listOf(
                "parentSync",
                "flush",
                "fileSync",
                "close",
                "publish",
                "directorySync(parts=1,finals=1)",
                "directorySync(parts=0,finals=1)",
            ),
            events,
        )
    }

    @Test
    fun publicationDirectorySyncFailureKeepsFinalAndRecoverablePart() {
        val root = temporaryFolder.newFolder("books")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) {
                    throw IOException("publication directory sync failed")
                }
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals(IOException::class.java, failure.javaClass)
        assertEquals("publication directory sync failed", failure.message)
        val files = root.listFiles()!!
        assertEquals(2, files.size)
        assertEquals(1, files.count { it.name.endsWith(".part") })
        assertEquals(1, files.count { it.name.endsWith(".epub") })
        files.forEach { file -> assertArrayEquals("hello".toByteArray(), file.readBytes()) }
    }

    @Test
    fun cleanupDirectorySyncFailureReportsAlreadyPublishedBook() {
        val root = temporaryFolder.newFolder("books")
        var directorySyncs = 0
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) {
                    directorySyncs += 1
                    if (directorySyncs == 2) throw IOException("cleanup directory sync failed")
                }
            }
        }

        val failure = assertThrows(PublishedBookCleanupException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("cleanup directory sync failed", failure.cause?.message)
        assertFalse(failure.storedBook.wasExisting)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.epub",
            failure.storedBook.file.name,
        )
        assertArrayEquals("hello".toByteArray(), failure.storedBook.file.readBytes())
        assertEquals(1, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun rootHandleCloseFailureReportsAlreadyPublishedBook() {
        val root = temporaryFolder.newFolder("root-close-books")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openRoot(
                rootDirectory: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = SystemPrivateBookStoreFileOperations.openRoot(
                    rootDirectory,
                    operations,
                )
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun close() {
                        delegate.close()
                        throw IOException("root close failed")
                    }
                }
            }
        }

        val failure = assertThrows(PublishedBookCleanupException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("root close failed", failure.cause?.message)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.epub",
            failure.storedBook.file.name,
        )
        assertArrayEquals("hello".toByteArray(), failure.storedBook.file.readBytes())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun loserDirectorySyncFailureKeepsExistingFinalAndRecoverablePart() {
        val root = temporaryFolder.newFolder("books")
        val finalFile = File(
            root,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.epub",
        ).apply { writeText("hello") }
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) {
                    throw IOException("loser directory sync failed")
                }
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "renamed.epub")
        }

        assertEquals(IOException::class.java, failure.javaClass)
        assertEquals("loser directory sync failed", failure.message)
        assertEquals("hello", finalFile.readText())
        val parts = root.listFiles()!!.filter { it.name.endsWith(".part") }
        assertEquals(1, parts.size)
        assertArrayEquals("hello".toByteArray(), parts.single().readBytes())
    }

    @Test
    fun prepublicationCleanupSyncFailureIsSuppressedOnOriginalFailure() {
        val root = temporaryFolder.newFolder("books")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun publishAtomically(source: File, target: File) {
                throw IOException("publication failed")
            }

            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) {
                    throw IOException("cleanup directory sync failed")
                }
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("publication failed", failure.message)
        assertEquals(listOf("cleanup directory sync failed"), failure.suppressed.map { it.message })
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun prepublicationCleanupUnlinkFailureIsSuppressedAndKeepsPart() {
        val root = temporaryFolder.newFolder("books")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun publishAtomically(source: File, target: File) {
                throw IOException("publication failed")
            }

            override fun deletePart(file: File): Boolean {
                throw IOException("cleanup unlink failed")
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("publication failed", failure.message)
        assertEquals(listOf("cleanup unlink failed"), failure.suppressed.map { it.message })
        assertEquals(1, root.listFiles()!!.count { it.name.endsWith(".part") })
        assertEquals(0, root.listFiles()!!.count { it.name.endsWith(".epub") })
    }

    @Test
    fun earlyFailureUsesOwnedDurableCleanupAfterRootPathSwap() {
        val root = temporaryFolder.newFolder("cleanup-swap-books")
        val pinnedRoot = File(temporaryFolder.root, "cleanup-swap-pinned")
        val attacker = temporaryFolder.newFolder("cleanup-swap-attacker")
        var ownedCleanupCalled = false
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openRoot(
                rootDirectory: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = SystemPrivateBookStoreFileOperations.openRoot(
                    rootDirectory,
                    operations,
                )
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun publishAtomically(source: File, target: File) {
                        Files.move(rootDirectory.toPath(), pinnedRoot.toPath())
                        Files.createSymbolicLink(rootDirectory.toPath(), attacker.toPath())
                        throw IOException("publication failed after root swap")
                    }

                    override fun cleanupPartDurably(file: File): Boolean {
                        ownedCleanupCalled = true
                        return Files.deleteIfExists(File(pinnedRoot, file.name).toPath())
                    }
                }
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("publication failed after root swap", failure.message)
        assertTrue(ownedCleanupCalled)
        assertTrue(pinnedRoot.listFiles()!!.isEmpty())
        assertTrue(attacker.listFiles()!!.isEmpty())
        assertTrue(Files.isSymbolicLink(root.toPath()))
    }

    @Test
    fun flushFailureRemovesPartAndDoesNotPublishFinalFile() {
        assertDurabilityFailureIsCleaned("flush") { delegate ->
            object : DurableBookOutput by delegate {
                override fun flush() {
                    delegate.flush()
                    throw IOException("flush failed")
                }
            }
        }
    }

    @Test
    fun syncFailureRemovesPartAndDoesNotPublishFinalFile() {
        assertDurabilityFailureIsCleaned("sync") { delegate ->
            object : DurableBookOutput by delegate {
                override fun sync() {
                    delegate.sync()
                    throw IOException("sync failed")
                }
            }
        }
    }

    @Test
    fun closeFailureRemovesPartAndDoesNotPublishFinalFile() {
        assertDurabilityFailureIsCleaned("close") { delegate ->
            object : DurableBookOutput by delegate {
                override fun close() {
                    delegate.close()
                    throw IOException("close failed")
                }
            }
        }
    }

    @Test
    fun mismatchedHashNamedFileIsRejectedAndNeverOverwritten() {
        val root = temporaryFolder.newFolder("books")
        val store = PrivateBookStore(root)
        val first = store.store("hello".byteInputStream(), "book.epub")
        first.file.writeText("keep existing")

        val failure = assertThrows(IOException::class.java) {
            store.store("hello".byteInputStream(), "renamed.epub")
        }

        assertEquals("Existing book does not match its SHA-256 file name", failure.message)
        assertEquals("keep existing", first.file.readText())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun hashNamedDirectoryIsRejectedAsAnExistingBook() {
        val root = temporaryFolder.newFolder("books")
        val finalDirectory = File(
            root,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.epub",
        ).apply { mkdir() }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("Existing book target is not a regular file", failure.message)
        assertTrue(finalDirectory.isDirectory)
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun hashNamedSymlinkToMatchingBytesIsRejectedAsAnExistingBook() {
        val root = temporaryFolder.newFolder("books")
        val matchingTarget = File(temporaryFolder.root, "matching-target").apply {
            writeText("hello")
        }
        val finalPath = File(
            root,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.epub",
        ).toPath()
        Files.createSymbolicLink(finalPath, matchingTarget.toPath())

        assertThrows(IOException::class.java) {
            PrivateBookStore(root).store("hello".byteInputStream(), "book.epub")
        }

        assertTrue(Files.isSymbolicLink(finalPath))
        assertEquals("hello", matchingTarget.readText())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun adversarialProcessSwapToMatchingSymlinkIsRejectedByTheSafeOpen() {
        val root = temporaryFolder.newFolder("books")
        val finalFile = File(root, "$PROCESS_CONTENT_SHA256.pdf").apply {
            writeText(PROCESS_CONTENT)
        }
        val matchingTarget = File(temporaryFolder.root, "matching-process-target").apply {
            writeText(PROCESS_CONTENT)
        }
        val coordination = temporaryFolder.newFolder("symlink-swap-coordination")
        val ready = File(coordination, "ready")
        val trigger = File(coordination, "trigger")
        val result = File(coordination, "result")
        val log = File(coordination, "attacker.log")
        val java = File(System.getProperty("java.home"), "bin/java")
        val attacker = ProcessBuilder(
            java.absolutePath,
            "-cp",
            workerClasspath(),
            PrivateBookStoreSymlinkSwapWorker::class.java.name,
            finalFile.absolutePath,
            matchingTarget.absolutePath,
            ready.absolutePath,
            trigger.absolutePath,
            result.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        try {
            awaitCondition("adversarial process to await the swap trigger") { ready.isFile }
            val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
                override fun openExistingBook(file: File): ExistingBookInput {
                    val opened = SystemPrivateBookStoreFileOperations.openExistingBook(file)
                    check(trigger.createNewFile())
                    awaitCondition("adversarial process to replace final with a symlink") {
                        result.isFile
                    }
                    check(result.readText() == "SWAPPED") {
                        "attacker failed: ${result.readText()}; output=${log.readText()}"
                    }
                    return opened
                }
            }

            assertThrows(IOException::class.java) {
                PrivateBookStore(root, operations).store(
                    PROCESS_CONTENT.byteInputStream(),
                    "book.pdf",
                )
            }

            assertTrue(attacker.waitFor(30, TimeUnit.SECONDS))
            assertEquals("attacker output=${log.readText()}", 0, attacker.exitValue())
            assertTrue(Files.isSymbolicLink(finalFile.toPath()))
            assertEquals(PROCESS_CONTENT, matchingTarget.readText())
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            trigger.createNewFile()
            if (attacker.isAlive) attacker.destroyForcibly()
        }
    }

    @Test
    fun sameInodeMutationAfterHashEofIsRejected() {
        val root = temporaryFolder.newFolder("same-inode-books")
        val finalFile = File(root, "$PROCESS_CONTENT_SHA256.pdf").apply {
            writeText(PROCESS_CONTENT)
        }
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openExistingBook(file: File): ExistingBookInput {
                val delegate = SystemPrivateBookStoreFileOperations.openExistingBook(file)
                return object : ExistingBookInput by delegate {
                    override fun verifiedStateAfterHash(): ExistingBookFileState {
                        FileOutputStream(finalFile, true).use { it.write('!'.code) }
                        return delegate.verifiedStateAfterHash()
                    }
                }
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store(
                PROCESS_CONTENT.byteInputStream(),
                "book.pdf",
            )
        }

        assertEquals("$PROCESS_CONTENT!", finalFile.readText())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun sameInodeMutationBetweenSecondStatAndEntryStatIsRejected() {
        val root = temporaryFolder.newFolder("between-stat-books")
        val finalFile = File(root, "$PROCESS_CONTENT_SHA256.pdf").apply {
            writeText(PROCESS_CONTENT)
        }
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openRoot(
                rootDirectory: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = SystemPrivateBookStoreFileOperations.openRoot(
                    rootDirectory,
                    operations,
                )
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun openExistingBook(file: File): ExistingBookInput {
                        val input = delegate.openExistingBook(file)
                        return object : ExistingBookInput by input {
                            override fun verifiedStateAfterHash(): ExistingBookFileState {
                                val state = input.verifiedStateAfterHash()
                                FileOutputStream(finalFile, true).use { it.write('!'.code) }
                                return state
                            }
                        }
                    }
                }
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store(
                PROCESS_CONTENT.byteInputStream(),
                "book.pdf",
            )
        }

        assertEquals("$PROCESS_CONTENT!", finalFile.readText())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun jvmAdapterRejectsRegularFileSwapInsideOpenIdentityWindow() {
        val root = temporaryFolder.newFolder("jvm-open-window")
        val finalFile = File(root, "final.pdf").apply { writeText("original") }
        val operations = object : JvmExistingBookFileOperations by SystemJvmExistingBookFileOperations {
            override fun openChannel(path: Path): FileChannel {
                val opened = SystemJvmExistingBookFileOperations.openChannel(path)
                Files.delete(path)
                path.toFile().writeText("attacker")
                return opened
            }
        }

        assertThrows(IOException::class.java) {
            JvmExistingBookInput.open(finalFile, operations).close()
        }

        assertEquals("attacker", finalFile.readText())
    }

    @Test
    fun jvmAdapterRejectsRestoredRegularFileSwapInsideOpenIdentityWindow() {
        val root = temporaryFolder.newFolder("jvm-restored-open-window")
        val finalFile = File(root, "final.pdf").apply { writeText("original") }
        val heldOriginal = File(root, "held-original.pdf")
        val heldAttacker = File(root, "held-attacker.pdf")
        val attacker = File(root, "attacker.pdf").apply { writeText("attacker") }
        val originalModified = Files.getLastModifiedTime(finalFile.toPath())
        Files.setLastModifiedTime(attacker.toPath(), originalModified)
        val operations = object : JvmExistingBookFileOperations by SystemJvmExistingBookFileOperations {
            override fun openChannel(path: Path): FileChannel {
                Files.move(path, heldOriginal.toPath())
                Files.move(attacker.toPath(), path)
                val opened = SystemJvmExistingBookFileOperations.openChannel(path)
                Files.move(path, heldAttacker.toPath())
                Files.move(heldOriginal.toPath(), path)
                return opened
            }
        }

        assertThrows(IOException::class.java) {
            JvmExistingBookInput.open(finalFile, operations).close()
        }

        assertEquals("original", finalFile.readText())
        assertEquals("attacker", heldAttacker.readText())
    }

    @Test
    fun jvmAdapterFailsClosedWhenFileKeyIsUnavailable() {
        val root = temporaryFolder.newFolder("jvm-null-file-key")
        val finalFile = File(root, "final.pdf").apply { writeText("original") }
        val operations = object : JvmExistingBookFileOperations by SystemJvmExistingBookFileOperations {
            override fun readAttributes(path: Path): BasicFileAttributes {
                val delegate = SystemJvmExistingBookFileOperations.readAttributes(path)
                return object : BasicFileAttributes by delegate {
                    override fun fileKey(): Any? = null
                }
            }
        }

        assertThrows(IOException::class.java) {
            JvmExistingBookInput.open(finalFile, operations).close()
        }
    }

    @Test
    fun jvmAdapterRejectsSameInodeRewriteWithForgedTimestamp() {
        val root = temporaryFolder.newFolder("jvm-forged-rewrite")
        val finalFile = File(root, "$PROCESS_CONTENT_SHA256.pdf").apply {
            writeText(PROCESS_CONTENT)
        }
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openRoot(
                rootDirectory: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = SystemPrivateBookStoreFileOperations.openRoot(rootDirectory, operations)
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun openExistingBook(file: File): ExistingBookInput {
                        val input = delegate.openExistingBook(file)
                        return object : ExistingBookInput by input {
                            override fun verifiedStateAfterHash(): ExistingBookFileState {
                                val state = input.verifiedStateAfterHash()
                                val modified = Files.getLastModifiedTime(finalFile.toPath())
                                val bytes = finalFile.readBytes()
                                finalFile.writeBytes(bytes.mapIndexed { index, byte ->
                                    if (index == 0) (byte.toInt() xor 1).toByte() else byte
                                }.toByteArray())
                                Files.setLastModifiedTime(finalFile.toPath(), modified)
                                return state
                            }
                        }
                    }
                }
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store(PROCESS_CONTENT.byteInputStream(), "book.pdf")
        }
    }

    @Test
    fun concurrentIdenticalContentPublishesExactlyOneCompleteFile() {
        val root = temporaryFolder.newFolder("books")
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }
        val workerCount = 8
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val futures = (0 until workerCount).map { index ->
                executor.submit(Callable {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    PrivateBookStore(root).store(
                        ByteArrayInputStream(content),
                        "book-$index.pdf",
                    )
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()

            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { !it.wasExisting })
            assertEquals(workerCount - 1, results.count { it.wasExisting })
            assertEquals(1, results.map { it.file }.distinct().size)
            assertArrayEquals(content, results.first().file.readBytes())
            assertEquals(1, root.listFiles()!!.size)
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun separateProcessesPublishIdenticalContentWithoutOverwriting() {
        val root = temporaryFolder.newFolder("books")
        val coordination = temporaryFolder.newFolder("coordination")
        val start = File(coordination, "start")
        val first = launchWorker(root, coordination, "first", start, waitAtPublication = true)
        val second = launchWorker(root, coordination, "second", start, waitAtPublication = true)
        val workers = listOf(first, second)
        try {
            awaitCondition("both workers reached publication") {
                File(coordination, "first.ready").isFile &&
                    File(coordination, "second.ready").isFile
            }
            assertTrue(start.createNewFile())

            val outcomes = workers.map(::awaitWorker).sorted()

            assertEquals(listOf("EXISTING", "NEW"), outcomes)
            val files = root.listFiles()!!
            assertEquals(1, files.size)
            assertEquals("$PROCESS_CONTENT_SHA256.pdf", files.single().name)
            assertArrayEquals(PROCESS_CONTENT.toByteArray(), files.single().readBytes())
            assertTrue(files.none { it.name.endsWith(".part") })
        } finally {
            start.createNewFile()
            workers.forEach { worker ->
                if (worker.process.isAlive) worker.process.destroyForcibly()
            }
        }
    }

    @Test
    fun separateProcessesCreateMissingRootAndPublishExactlyOnce() {
        val root = File(temporaryFolder.root, "missing/books")
        val coordination = temporaryFolder.newFolder("missing-root-coordination")
        val start = File(coordination, "start")
        val first = launchWorker(
            root,
            coordination,
            "first",
            start,
            waitAtPublication = false,
            waitAtDirectoryCreate = true,
        )
        val second = launchWorker(
            root,
            coordination,
            "second",
            start,
            waitAtPublication = false,
            waitAtDirectoryCreate = true,
        )
        val workers = listOf(first, second)
        try {
            awaitCondition("both workers are ready to create the missing root") {
                File(coordination, "first.ready").isFile &&
                    File(coordination, "second.ready").isFile
            }
            assertTrue(start.createNewFile())

            val outcomes = workers.map(::awaitWorker).sorted()

            assertEquals(listOf("EXISTING", "NEW"), outcomes)
            val files = root.listFiles()!!
            assertEquals(1, files.size)
            assertEquals("$PROCESS_CONTENT_SHA256.pdf", files.single().name)
            assertArrayEquals(PROCESS_CONTENT.toByteArray(), files.single().readBytes())
            assertTrue(files.none { it.name.endsWith(".part") })
        } finally {
            start.createNewFile()
            workers.forEach { worker ->
                if (worker.process.isAlive) worker.process.destroyForcibly()
            }
        }
    }

    @Test
    fun separateProcessDoesNotReplacePreexistingHashNamedFile() {
        val root = temporaryFolder.newFolder("books")
        val coordination = temporaryFolder.newFolder("coordination")
        val finalFile = File(root, "$PROCESS_CONTENT_SHA256.pdf")
        finalFile.writeText(PROCESS_CONTENT)
        val start = File(coordination, "start").apply { createNewFile() }
        val worker = launchWorker(root, coordination, "worker", start, waitAtPublication = false)
        try {
            assertEquals("EXISTING", awaitWorker(worker))
            assertEquals(PROCESS_CONTENT, finalFile.readText())
            assertEquals(1, root.listFiles()!!.size)
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            if (worker.process.isAlive) worker.process.destroyForcibly()
        }
    }

    private fun assertDurabilityFailureIsCleaned(
        phase: String,
        wrap: (DurableBookOutput) -> DurableBookOutput,
    ) {
        val root = temporaryFolder.newFolder("books-$phase")
        var cleanupDirectorySyncs = 0
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openPart(file: File): DurableBookOutput =
                wrap(SystemPrivateBookStoreFileOperations.openPart(file))

            override fun syncDirectory(directory: File) {
                if (directory.canonicalFile == root.canonicalFile) cleanupDirectorySyncs += 1
            }
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("$phase failed", failure.message)
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1, cleanupDirectorySyncs)
    }

    private fun launchWorker(
        root: File,
        coordination: File,
        id: String,
        start: File,
        waitAtPublication: Boolean,
        waitAtDirectoryCreate: Boolean = false,
    ): WorkerProcess {
        val result = File(coordination, "$id.result")
        val log = File(coordination, "$id.log")
        val java = File(System.getProperty("java.home"), "bin/java")
        val process = ProcessBuilder(
            java.absolutePath,
            "-cp",
            workerClasspath(),
            PrivateBookStoreProcessWorker::class.java.name,
            root.absolutePath,
            File(coordination, "$id.ready").absolutePath,
            start.absolutePath,
            result.absolutePath,
            waitAtPublication.toString(),
            waitAtDirectoryCreate.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        return WorkerProcess(process, result, log)
    }

    private fun awaitWorker(worker: WorkerProcess): String {
        assertTrue(
            "worker timed out; output=${worker.log.readText()}",
            worker.process.waitFor(30, TimeUnit.SECONDS),
        )
        assertEquals(
            "worker failed; output=${worker.log.readText()}",
            0,
            worker.process.exitValue(),
        )
        return worker.result.readText()
    }

    private fun workerClasspath(): String = listOf(
        PrivateBookStoreProcessWorker::class.java,
        PrivateBookStore::class.java,
        Unit::class.java,
    ).map { type ->
        File(type.protectionDomain!!.codeSource.location.toURI()).absolutePath
    }.distinct().joinToString(File.pathSeparator)

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for $description")
            }
            Thread.sleep(10)
        }
    }

    private data class WorkerProcess(
        val process: Process,
        val result: File,
        val log: File,
    )

    internal companion object {
        const val PROCESS_CONTENT = "cross-process-content"
        const val PROCESS_CONTENT_SHA256 =
            "8e0db690d629a34a645639f3869de4bc4ec2159991b0b643d8065f9bc29ee579"
    }
}

internal object PrivateBookStoreProcessWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = File(arguments[0])
        val ready = File(arguments[1])
        val start = File(arguments[2])
        val resultFile = File(arguments[3])
        val waitAtPublication = arguments[4].toBoolean()
        val waitAtDirectoryCreate = arguments[5].toBoolean()
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun createDirectory(directory: File) {
                if (waitAtDirectoryCreate && directory.absoluteFile == root.parentFile!!.absoluteFile) {
                    check(ready.createNewFile())
                    awaitStart(start)
                }
                SystemPrivateBookStoreFileOperations.createDirectory(directory)
            }

            override fun publishAtomically(source: File, target: File) {
                if (waitAtPublication) {
                    check(ready.createNewFile())
                    awaitStart(start)
                }
                SystemPrivateBookStoreFileOperations.publishAtomically(source, target)
            }
        }

        try {
            val stored = PrivateBookStore(root, operations).store(
                PrivateBookStoreTest.PROCESS_CONTENT.byteInputStream(),
                "book.pdf",
            )
            resultFile.writeText(if (stored.wasExisting) "EXISTING" else "NEW")
        } catch (failure: Throwable) {
            failure.printStackTrace()
            resultFile.writeText("ERROR:${failure::class.java.name}:${failure.message}")
        }
    }

    private fun awaitStart(start: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!start.isFile) {
            check(System.nanoTime() < deadline) { "Timed out waiting for publication start" }
            Thread.sleep(10)
        }
    }
}

internal object PrivateBookStoreSymlinkSwapWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val finalFile = File(arguments[0])
        val matchingTarget = File(arguments[1])
        val ready = File(arguments[2])
        val trigger = File(arguments[3])
        val result = File(arguments[4])
        try {
            check(ready.createNewFile())
            awaitTrigger(trigger)
            Files.delete(finalFile.toPath())
            Files.createSymbolicLink(finalFile.toPath(), matchingTarget.toPath())
            result.writeText("SWAPPED")
        } catch (failure: Throwable) {
            failure.printStackTrace()
            result.writeText("ERROR:${failure::class.java.name}:${failure.message}")
        }
    }

    private fun awaitTrigger(trigger: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!trigger.isFile) {
            check(System.nanoTime() < deadline) { "Timed out waiting for swap trigger" }
            Thread.sleep(10)
        }
    }
}
