package com.air5005.pagenest.library.importing

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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

class PrivateBookStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun publishAtomically(source: File, target: File) {
                partParent = source.parentFile
                finalParent = target.parentFile
                SystemPrivateBookStoreFileOperations.publishAtomically(source, target)
            }
        }

        val result = PrivateBookStore(root, operations)
            .store("hello".byteInputStream(), "book.txt")

        assertEquals(root.canonicalFile, partParent?.canonicalFile)
        assertEquals(root.canonicalFile, finalParent?.canonicalFile)
        assertEquals(root.canonicalFile, result.file.parentFile!!.canonicalFile)
    }

    @Test
    fun readFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
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
            PrivateBookStore(root).store(input, "book.pdf")
        }

        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun writeFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
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
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.mobi")
        }

        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun publicationFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun publishAtomically(source: File, target: File) {
                throw IOException("publication failed")
            }
        }

        assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.azw3")
        }

        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun flushesAndSyncsPartBeforeAtomicPublication() {
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
                        events += "sync"
                        delegate.sync()
                    }

                    override fun close() {
                        events += "close"
                        delegate.close()
                    }
                }
            }

            override fun publishAtomically(source: File, target: File) {
                events += "publish"
                SystemPrivateBookStoreFileOperations.publishAtomically(source, target)
            }
        }

        PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")

        assertEquals(listOf("flush", "sync", "close", "publish"), events)
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
    fun existingHashNamedFileIsNeverOverwritten() {
        val root = temporaryFolder.newFolder("books")
        val store = PrivateBookStore(root)
        val first = store.store("hello".byteInputStream(), "book.epub")
        first.file.writeText("keep existing")

        val duplicate = store.store("hello".byteInputStream(), "renamed.epub")

        assertTrue(duplicate.wasExisting)
        assertEquals("keep existing", duplicate.file.readText())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
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
    fun separateProcessDoesNotReplacePreexistingHashNamedFile() {
        val root = temporaryFolder.newFolder("books")
        val coordination = temporaryFolder.newFolder("coordination")
        val finalFile = File(root, "$PROCESS_CONTENT_SHA256.pdf")
        finalFile.writeText("keep existing")
        val start = File(coordination, "start").apply { createNewFile() }
        val worker = launchWorker(root, coordination, "worker", start, waitAtPublication = false)
        try {
            assertEquals("EXISTING", awaitWorker(worker))
            assertEquals("keep existing", finalFile.readText())
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
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun openPart(file: File): DurableBookOutput =
                wrap(SystemPrivateBookStoreFileOperations.openPart(file))
        }

        val failure = assertThrows(IOException::class.java) {
            PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")
        }

        assertEquals("$phase failed", failure.message)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun launchWorker(
        root: File,
        coordination: File,
        id: String,
        start: File,
        waitAtPublication: Boolean,
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
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
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
