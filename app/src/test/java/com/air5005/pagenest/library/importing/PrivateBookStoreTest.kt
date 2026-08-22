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
            override fun moveAtomically(source: File, target: File) {
                partParent = source.parentFile
                finalParent = target.parentFile
                SystemPrivateBookStoreFileOperations.moveAtomically(source, target)
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
    fun moveFailureRemovesPartAndDoesNotPublishFinalFile() {
        val root = temporaryFolder.newFolder("books")
        val operations = object : PrivateBookStoreFileOperations by SystemPrivateBookStoreFileOperations {
            override fun moveAtomically(source: File, target: File) {
                throw IOException("move failed")
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

            override fun moveAtomically(source: File, target: File) {
                events += "move"
                SystemPrivateBookStoreFileOperations.moveAtomically(source, target)
            }
        }

        PrivateBookStore(root, operations).store("hello".byteInputStream(), "book.epub")

        assertEquals(listOf("flush", "sync", "close", "move"), events)
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
}
