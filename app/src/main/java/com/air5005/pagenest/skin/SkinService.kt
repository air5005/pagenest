package com.air5005.pagenest.skin

import com.wxn.base.skin.SkinCanonicalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface SkinImageImporter {
    suspend fun import(source: String): SkinImageImportResult
    suspend fun retainOnly(paths: Set<String>) = Unit
}

sealed interface SkinImageImportResult {
    data class Success(val path: String) : SkinImageImportResult
    data class Failure(val reason: SkinImageImportFailure) : SkinImageImportResult
}

enum class SkinImageImportFailure {
    INVALID_IMAGE,
    IMAGE_TOO_LARGE,
    IO,
}

data class SkinPreferenceSnapshot(
    val homeBackground: String,
    val readerBackground: String,
    val canonicalState: SkinCanonicalState? = null,
)

interface SkinPreferencesStore {
    suspend fun snapshot(): SkinPreferenceSnapshot
    suspend fun setHomeBackground(path: String)
    suspend fun setReaderBackground(path: String)
    suspend fun canonicalState(): SkinCanonicalState? = null
    suspend fun setCanonicalState(state: SkinCanonicalState?) = Unit
}

sealed interface SkinApplyResult {
    data class Success(val path: String) : SkinApplyResult
    data class Failure(val reason: SkinApplyFailure) : SkinApplyResult
}

enum class SkinApplyFailure {
    INVALID_IMAGE,
    IMAGE_TOO_LARGE,
    IO,
    PREFERENCES,
}

@Singleton
class SkinService @Inject constructor(
    private val imageImporter: SkinImageImporter,
    private val preferencesStore: SkinPreferencesStore,
) {
    private val operationMutex = Mutex()

    suspend fun importAndApply(source: String): SkinApplyResult = operationMutex.withLock {
        val imported = try {
            imageImporter.import(source)
        } catch (cancelled: CancellationException) {
            cleanupToCanonicalState()
            throw cancelled
        }
        when (imported) {
            is SkinImageImportResult.Success -> applyAtomically(imported.path)
            is SkinImageImportResult.Failure -> SkinApplyResult.Failure(imported.reason.toApplyFailure())
        }
    }

    suspend fun reset(): SkinApplyResult = operationMutex.withLock {
        applyAtomically("")
    }

    suspend fun effectiveHomeBackground(fallback: String): String =
        preferencesStore.canonicalState()?.homeBackground ?: fallback

    suspend fun reconcile(): SkinCanonicalState? = operationMutex.withLock {
        val canonical = preferencesStore.canonicalState()
        if (canonical == null) {
            runCatching { imageImporter.retainOnly(emptySet()) }
            return@withLock null
        }
        val current = preferencesStore.snapshot()
        runCatching { preferencesStore.setHomeBackground(canonical.homeBackground) }
        if (current.readerBackground !in READER_PRESET_BACKGROUNDS) {
            runCatching { preferencesStore.setReaderBackground(canonical.readerBackground) }
        }
        runCatching {
            imageImporter.retainOnly(setOf(canonical.homeBackground, canonical.readerBackground))
        }
        canonical
    }

    private suspend fun applyAtomically(path: String): SkinApplyResult {
        val previous = try {
            preferencesStore.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val canonical = runCatching { preferencesStore.canonicalState() }.getOrNull()
            runCatching {
                imageImporter.retainOnly(
                    setOfNotNull(canonical?.homeBackground, canonical?.readerBackground),
                )
            }
            return SkinApplyResult.Failure(SkinApplyFailure.PREFERENCES)
        }

        val transactionBaseline = SkinCanonicalState(
            homeBackground = previous.homeBackground,
            readerBackground = previous.readerBackground,
        )
        val restorable = previous.copy(canonicalState = transactionBaseline)
        return try {
            preferencesStore.setCanonicalState(transactionBaseline)
            preferencesStore.setReaderBackground(path)
            preferencesStore.setHomeBackground(path)
            preferencesStore.setCanonicalState(SkinCanonicalState(path, path))
            runCatching { imageImporter.retainOnly(setOf(path)) }
            SkinApplyResult.Success(path)
        } catch (cancelled: CancellationException) {
            rollbackAndCleanup(restorable)
            throw cancelled
        } catch (_: Exception) {
            rollbackAndCleanup(restorable)
            SkinApplyResult.Failure(SkinApplyFailure.PREFERENCES)
        }
    }

    private suspend fun rollbackAndCleanup(previous: SkinPreferenceSnapshot) {
        withContext(NonCancellable) {
            runCatching { preferencesStore.setReaderBackground(previous.readerBackground) }
            runCatching { preferencesStore.setHomeBackground(previous.homeBackground) }
            runCatching { preferencesStore.setCanonicalState(previous.canonicalState) }
            retainPreviousImages(previous)
        }
    }

    private suspend fun retainPreviousImages(previous: SkinPreferenceSnapshot) {
        runCatching {
            imageImporter.retainOnly(
                setOfNotNull(
                    previous.homeBackground,
                    previous.readerBackground,
                    previous.canonicalState?.homeBackground,
                    previous.canonicalState?.readerBackground,
                ),
            )
        }
    }

    private suspend fun cleanupToCanonicalState() {
        withContext(NonCancellable) {
            val canonical = runCatching { preferencesStore.canonicalState() }.getOrNull()
            val retained = setOfNotNull(
                canonical?.homeBackground,
                canonical?.readerBackground,
            )
            runCatching { imageImporter.retainOnly(retained) }
        }
    }

    private fun SkinImageImportFailure.toApplyFailure(): SkinApplyFailure = when (this) {
        SkinImageImportFailure.INVALID_IMAGE -> SkinApplyFailure.INVALID_IMAGE
        SkinImageImportFailure.IMAGE_TOO_LARGE -> SkinApplyFailure.IMAGE_TOO_LARGE
        SkinImageImportFailure.IO -> SkinApplyFailure.IO
    }

    private companion object {
        val READER_PRESET_BACKGROUNDS = setOf(
            "ic_read_bg1",
            "ic_read_bg2",
            "ic_read_bg3",
            "ic_read_bg4",
        )
    }
}
