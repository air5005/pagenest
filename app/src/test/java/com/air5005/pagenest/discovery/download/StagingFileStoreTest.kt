package com.air5005.pagenest.discovery.download

import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagingFileStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `cleanup only removes old regular part files`() {
        val directory = temporaryFolder.newFolder("staging")
        val oldPart = File(directory, "old.part").apply { writeText("old") }
        val recentPart = File(directory, "recent.part").apply { writeText("recent") }
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        oldPart.setLastModified(Instant.now().minus(Duration.ofDays(2)).toEpochMilli())

        StagingFileStore(directory).cleanupOldParts(
            olderThan = Duration.ofDays(1),
            now = Instant.now(),
        )

        assertFalse(oldPart.exists())
        assertTrue(recentPart.exists())
        assertTrue(unrelated.exists())
    }
}
