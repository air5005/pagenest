package com.air5005.pagenest.discovery.importing

import dagger.Module
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnlineImportModuleContractTest {
    @Test
    fun `module stores ledger in dedicated private directory`() {
        assertNotNull(OnlineImportModule::class.java.getAnnotation(Module::class.java))
        assertEquals(
            File(File("private-files", "online-import"), "ledger.json"),
            OnlineImportModule.ledgerFile(File("private-files")),
        )
    }
}
