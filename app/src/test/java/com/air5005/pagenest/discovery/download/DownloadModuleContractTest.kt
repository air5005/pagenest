package com.air5005.pagenest.discovery.download

import dagger.Module
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModuleContractTest {
    @Test
    fun `download module uses a dedicated app-private staging directory`() {
        assertNotNull(DownloadModule::class.java.getAnnotation(Module::class.java))
        assertEquals(
            File("private-files", "online-book-staging"),
            DownloadModule.stagingDirectory(File("private-files")),
        )
        assertTrue(DownloadModule.provideBookDownloadTransport() is OkHttpBookDownloadTransport)
    }
}
