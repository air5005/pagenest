package com.air5005.pagenest.skin

import kotlinx.coroutines.CancellationException
import com.wxn.base.skin.SkinCanonicalState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinServiceTest {

    @Test
    fun `imported image is applied to home and reader together`() = runTest {
        val store = RecordingSkinPreferencesStore(home = "old-home", reader = "old-reader")
        val service = SkinService(
            imageImporter = FakeSkinImageImporter(SkinImageImportResult.Success("/private/skin.jpg")),
            preferencesStore = store,
        )

        val result = service.importAndApply("content://gallery/skin")

        assertEquals(SkinApplyResult.Success("/private/skin.jpg"), result)
        assertEquals("/private/skin.jpg", store.home)
        assertEquals("/private/skin.jpg", store.reader)
        assertEquals(
            listOf(
                "canonical:old-home",
                "reader:/private/skin.jpg",
                "home:/private/skin.jpg",
                "canonical:/private/skin.jpg",
            ),
            store.writes,
        )
    }

    @Test
    fun `preference failure restores both previous backgrounds`() = runTest {
        val store = RecordingSkinPreferencesStore(
            home = "old-home",
            reader = "old-reader",
            failHomeOnceFor = "/private/skin.jpg",
        )
        val service = SkinService(
            imageImporter = FakeSkinImageImporter(SkinImageImportResult.Success("/private/skin.jpg")),
            preferencesStore = store,
        )

        val result = service.importAndApply("content://gallery/skin")

        assertEquals(SkinApplyResult.Failure(SkinApplyFailure.PREFERENCES), result)
        assertEquals("old-home", store.home)
        assertEquals("old-reader", store.reader)
        assertTrue(store.writes.contains("reader:old-reader"))
        assertTrue(store.writes.contains("home:old-home"))
    }

    @Test
    fun `invalid image leaves current skin unchanged`() = runTest {
        val store = RecordingSkinPreferencesStore(home = "old-home", reader = "old-reader")
        val service = SkinService(
            imageImporter = FakeSkinImageImporter(
                SkinImageImportResult.Failure(SkinImageImportFailure.INVALID_IMAGE),
            ),
            preferencesStore = store,
        )

        val result = service.importAndApply("content://gallery/not-an-image")

        assertEquals(SkinApplyResult.Failure(SkinApplyFailure.INVALID_IMAGE), result)
        assertEquals("old-home", store.home)
        assertEquals("old-reader", store.reader)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `canonical commit point remains old when legacy rollback also fails`() = runTest {
        val store = RecordingSkinPreferencesStore(
            home = "old-home",
            reader = "old-reader",
            failHomeOnceFor = "/private/skin.jpg",
            failReaderFor = "old-reader",
        )
        val service = SkinService(
            imageImporter = FakeSkinImageImporter(SkinImageImportResult.Success("/private/skin.jpg")),
            preferencesStore = store,
        )

        val result = service.importAndApply("content://gallery/skin")

        assertEquals(SkinApplyResult.Failure(SkinApplyFailure.PREFERENCES), result)
        assertEquals(SkinCanonicalState("old-home", "old-reader"), store.canonical)
        assertEquals("old-home", service.effectiveHomeBackground("new-home"))
    }

    @Test
    fun `transaction baseline captures current preset instead of stale canonical skin`() = runTest {
        val store = RecordingSkinPreferencesStore(
            home = "current-home",
            reader = "ic_read_bg2",
            canonical = SkinCanonicalState("stale-home", "stale-reader"),
            failHomeOnceFor = "/private/new-skin.jpg",
        )
        val service = SkinService(
            imageImporter = FakeSkinImageImporter(SkinImageImportResult.Success("/private/new-skin.jpg")),
            preferencesStore = store,
        )

        service.importAndApply("content://gallery/new-skin")

        assertEquals(SkinCanonicalState("current-home", "ic_read_bg2"), store.canonical)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation during preference write still cleans imported orphan`() = runTest {
        val importer = RecordingSkinImageImporter("/private/new-skin.jpg")
        val store = RecordingSkinPreferencesStore(
            home = "old-home",
            reader = "old-reader",
            cancelHomeFor = "/private/new-skin.jpg",
        )
        val service = SkinService(importer, store)

        try {
            service.importAndApply("content://gallery/new-skin")
        } finally {
            assertEquals(setOf("old-home", "old-reader"), importer.lastRetained)
        }
    }

    @Test
    fun `reset clears home and reader backgrounds together`() = runTest {
        val store = RecordingSkinPreferencesStore(home = "skin-home", reader = "skin-reader")
        val service = SkinService(
            imageImporter = FakeSkinImageImporter(SkinImageImportResult.Success("unused")),
            preferencesStore = store,
        )

        val result = service.reset()

        assertEquals(SkinApplyResult.Success(""), result)
        assertEquals("", store.home)
        assertEquals("", store.reader)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is propagated without preference writes`() = runTest {
        val store = RecordingSkinPreferencesStore(home = "old-home", reader = "old-reader")
        var retained: Set<String>? = null
        val service = SkinService(
            imageImporter = object : SkinImageImporter {
                override suspend fun import(source: String): SkinImageImportResult {
                    throw CancellationException("cancelled")
                }

                override suspend fun retainOnly(paths: Set<String>) {
                    retained = paths
                }
            },
            preferencesStore = store,
        )

        try {
            service.importAndApply("content://gallery/skin")
        } finally {
            assertTrue(store.writes.isEmpty())
            assertEquals(emptySet<String>(), retained)
        }
    }

    @Test
    fun `startup without canonical state cleans first import artifacts`() = runTest {
        val importer = RecordingSkinImageImporter("unused")
        val store = RecordingSkinPreferencesStore(home = "", reader = "")
        val service = SkinService(importer, store)

        assertEquals(null, service.reconcile())
        assertEquals(emptySet<String>(), importer.lastRetained)
        assertEquals(1, importer.retainCalls)
    }

    private class FakeSkinImageImporter(
        private val result: SkinImageImportResult,
    ) : SkinImageImporter {
        override suspend fun import(source: String): SkinImageImportResult = result
    }

    private class RecordingSkinImageImporter(
        private val path: String,
    ) : SkinImageImporter {
        var lastRetained: Set<String> = emptySet()
        var retainCalls: Int = 0

        override suspend fun import(source: String): SkinImageImportResult =
            SkinImageImportResult.Success(path)

        override suspend fun retainOnly(paths: Set<String>) {
            retainCalls += 1
            lastRetained = paths
        }
    }

    private class RecordingSkinPreferencesStore(
        var home: String,
        var reader: String,
        var canonical: SkinCanonicalState? = null,
        private val failHomeOnceFor: String? = null,
        private val failReaderFor: String? = null,
        private val cancelHomeFor: String? = null,
    ) : SkinPreferencesStore {
        val writes = mutableListOf<String>()
        private var homeFailureConsumed = false

        override suspend fun snapshot(): SkinPreferenceSnapshot =
            SkinPreferenceSnapshot(
                homeBackground = home,
                readerBackground = reader,
                canonicalState = canonical,
            )

        override suspend fun setHomeBackground(path: String) {
            writes += "home:$path"
            if (path == cancelHomeFor) throw CancellationException("cancelled during home write")
            if (!homeFailureConsumed && path == failHomeOnceFor) {
                homeFailureConsumed = true
                throw IllegalStateException("home write failed")
            }
            home = path
        }

        override suspend fun setReaderBackground(path: String) {
            writes += "reader:$path"
            if (path == failReaderFor) throw IllegalStateException("reader write failed")
            reader = path
        }

        override suspend fun canonicalState(): SkinCanonicalState? = canonical

        override suspend fun setCanonicalState(state: SkinCanonicalState?) {
            writes += "canonical:${state?.homeBackground}"
            canonical = state
        }
    }
}
