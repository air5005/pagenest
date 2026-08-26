package com.air5005.pagenest.discovery.importing

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineImportLedgerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `ledger atomically persists stable key to book id without download URL`() = runBlocking {
        val file = File(temporaryFolder.root, "online-import-ledger.json")
        val ledger = FileOnlineImportLedger(file)

        ledger.put("gutenberg:1342", 42L)

        assertEquals(42L, FileOnlineImportLedger(file).get("gutenberg:1342"))
        val persisted = file.readText()
        assertTrue(persisted.contains("gutenberg:1342"))
        assertFalse(persisted.contains("https://www.gutenberg.org/files/1342.epub"))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `remove survives restart and malformed ledger fails closed`() = runBlocking {
        val file = File(temporaryFolder.root, "online-import-ledger.json")
        val ledger = FileOnlineImportLedger(file)
        ledger.put("gutenberg:1", 1L)
        ledger.remove("gutenberg:1")
        assertNull(FileOnlineImportLedger(file).get("gutenberg:1"))

        file.writeText("not json")
        assertNull(FileOnlineImportLedger(file).get("gutenberg:1"))
    }
}
