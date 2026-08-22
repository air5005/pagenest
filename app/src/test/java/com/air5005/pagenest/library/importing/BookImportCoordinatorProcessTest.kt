package com.air5005.pagenest.library.importing

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookImportCoordinatorProcessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun childProcessFileLockExcludesTheProductionCoordinator() = runBlocking {
        val lockDirectory = temporaryFolder.newFolder("child-process-locks")
        val sha256 = "1".repeat(64)
        val lockFile = File(lockDirectory, "$sha256.lock")
        val releaseFile = File(temporaryFolder.root, "release-child")
        val process = startLockHoldingChild(lockFile, releaseFile)
        try {
            assertEquals("LOCKED", process.inputStream.bufferedReader().readLine())
            val entered = AtomicBoolean(false)
            val result = async {
                PersistentHashBookImportCoordinator(lockDirectory).withHashLock(sha256) {
                    entered.set(true)
                    "entered"
                }
            }

            delay(200)
            assertFalse("A different process still owns the OS lock", entered.get())
            releaseFile.writeText("release")
            assertEquals("entered", withTimeout(5_000) { result.await() })
            assertTrue(lockFile.isFile)
        } finally {
            releaseFile.writeText("release")
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
    }

    @Test
    fun productionAcquisitionBlockedByAChildProcessIsCancellableAndReacquirable() = runBlocking {
        val lockDirectory = temporaryFolder.newFolder("child-cancel-locks")
        val sha256 = "7".repeat(64)
        val lockFile = File(lockDirectory, "$sha256.lock")
        val releaseFile = File(temporaryFolder.root, "release-cancel-child")
        val process = startLockHoldingChild(lockFile, releaseFile)
        try {
            assertEquals("LOCKED", process.inputStream.bufferedReader().readLine())
            var blockRan = false
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    withTimeout(200) {
                        PersistentHashBookImportCoordinator(lockDirectory).withHashLock(sha256) {
                            blockRan = true
                        }
                    }
                }
            }
            assertFalse(blockRan)

            releaseFile.writeText("release")
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val result = withTimeout(5_000) {
                PersistentHashBookImportCoordinator(lockDirectory).withHashLock(sha256) {
                    "reacquired"
                }
            }
            assertEquals("reacquired", result)
            assertTrue(lockFile.isFile)
        } finally {
            releaseFile.writeText("release")
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
    }

    @Test
    fun processSingletonMutexSerializesDifferentCoordinatorInstances() = runBlocking {
        val lockDirectory = temporaryFolder.newFolder("singleton-locks")
        val sha256 = "2".repeat(64)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = AtomicBoolean(false)
        val first = async {
            PersistentHashBookImportCoordinator(lockDirectory).withHashLock(sha256) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            PersistentHashBookImportCoordinator(lockDirectory).withHashLock(sha256) {
                secondEntered.set(true)
            }
        }

        delay(150)
        assertFalse(secondEntered.get())
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.get())
    }

    @Test
    fun acquisitionIsCancellableAndNeverRunsTheBlock() = runBlocking {
        val lockDirectory = temporaryFolder.newFolder("cancel-locks")
        val sha256 = "3".repeat(64)
        val operations = BlockingLockOperations()
        var blockRan = false
        val coordinator = PersistentHashBookImportCoordinator(lockDirectory, operations)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(150) {
                    coordinator.withHashLock(sha256) {
                        blockRan = true
                    }
                }
            }
        }

        assertTrue(thrown is CancellationException)
        assertFalse(blockRan)
        assertTrue(operations.closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun acquisitionFailureClosesTheChannelAndLeavesTheBlockUntouched() {
        val lockDirectory = temporaryFolder.newFolder("failure-locks")
        val primary = IOException("acquire failed")
        val closeFailure = IOException("close failed")
        val operations = FailingLockOperations(primary, closeFailure)
        var blockRan = false

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                PersistentHashBookImportCoordinator(lockDirectory, operations)
                    .withHashLock("4".repeat(64)) {
                        blockRan = true
                    }
            }
        }

        assertTrue(thrown === primary)
        assertTrue(thrown.suppressed.contains(closeFailure))
        assertFalse(blockRan)
        assertEquals(listOf("open", "acquire", "close"), operations.events)
    }

    @Test
    fun unlockAndCloseFailuresCannotReplaceACommittedResult() = runBlocking {
        val operations = CleanupFailingLockOperations()

        val result = PersistentHashBookImportCoordinator(
            temporaryFolder.newFolder("result-cleanup-locks"),
            operations,
        ).withHashLock("5".repeat(64)) { "committed" }

        assertEquals("committed", result)
        assertEquals(listOf("open", "acquire", "release", "close"), operations.events)
    }

    @Test
    fun unlockAndCloseFailuresAreSuppressedOnThePrimaryBlockFailure() {
        val primary = IOException("block failed")
        val operations = CleanupFailingLockOperations()

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                PersistentHashBookImportCoordinator(
                    temporaryFolder.newFolder("primary-cleanup-locks"),
                    operations,
                ).withHashLock("6".repeat(64)) { throw primary }
            }
        }

        assertTrue(thrown === primary)
        assertEquals(listOf("release failed", "close failed"), thrown.suppressed.map { it.message })
    }

    private fun startLockHoldingChild(lockFile: File, releaseFile: File): Process {
        val sourceDirectory = temporaryFolder.newFolder("lock-child-source")
        val source = File(sourceDirectory, "LockHolder.java")
        source.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.channels.FileLock;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public final class LockHolder {
                public static void main(String[] args) throws Exception {
                    Path lockPath = Path.of(args[0]);
                    Path releasePath = Path.of(args[1]);
                    try (FileChannel channel = FileChannel.open(lockPath,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                         FileLock ignored = channel.lock()) {
                        System.out.println("LOCKED");
                        System.out.flush();
                        while (!Files.exists(releasePath)) Thread.sleep(20L);
                    }
                }
            }
            """.trimIndent(),
        )
        val javac = File(System.getProperty("java.home"), "bin/javac.exe")
            .takeIf { it.isFile } ?: File(System.getProperty("java.home"), "bin/javac")
        val compilation = ProcessBuilder(
            javac.absolutePath,
            "-d",
            sourceDirectory.absolutePath,
            source.absolutePath,
        ).redirectErrorStream(true).start()
        val compilerOutput = compilation.inputStream.bufferedReader().readText()
        assertTrue("javac failed: $compilerOutput", compilation.waitFor() == 0)
        val java = File(System.getProperty("java.home"), "bin/java.exe")
            .takeIf { it.isFile } ?: File(System.getProperty("java.home"), "bin/java")
        return ProcessBuilder(
            java.absolutePath,
            "-cp",
            sourceDirectory.absolutePath,
            "LockHolder",
            lockFile.absolutePath,
            releaseFile.absolutePath,
        ).redirectErrorStream(true).start()
    }

    private class BlockingLockOperations : BookImportFileLockOperations {
        val closed = CountDownLatch(1)

        override fun open(file: File): Any = Any()

        override suspend fun acquire(handle: Any): Any {
            try {
                while (true) delay(1_000)
            } finally {
                // Cancellation must unwind through coordinator cleanup.
            }
        }

        override fun release(lock: Any) = Unit

        override fun close(handle: Any) {
            closed.countDown()
        }
    }

    private class FailingLockOperations(
        private val acquireFailure: IOException,
        private val closeFailure: IOException,
    ) : BookImportFileLockOperations {
        val events = mutableListOf<String>()

        override fun open(file: File): Any = Any().also { events += "open" }

        override suspend fun acquire(handle: Any): Any {
            events += "acquire"
            throw acquireFailure
        }

        override fun release(lock: Any) = error("No lock was acquired")

        override fun close(handle: Any) {
            events += "close"
            throw closeFailure
        }
    }

    private class CleanupFailingLockOperations : BookImportFileLockOperations {
        val events = mutableListOf<String>()

        override fun open(file: File): Any = Any().also { events += "open" }

        override suspend fun acquire(handle: Any): Any = Any().also { events += "acquire" }

        override fun release(lock: Any) {
            events += "release"
            throw IOException("release failed")
        }

        override fun close(handle: Any) {
            events += "close"
            throw IOException("close failed")
        }
    }
}
