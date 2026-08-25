package com.wxn.reader.presentation.home

import java.util.concurrent.CancellationException

sealed interface PublicDomainBookSeedResult {
    data object Seeded : PublicDomainBookSeedResult

    data object Failed : PublicDomainBookSeedResult
}

suspend fun seedPublicDomainBookSafely(
    seed: suspend () -> Unit,
): PublicDomainBookSeedResult = try {
    seed()
    PublicDomainBookSeedResult.Seeded
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: LinkageError) {
    PublicDomainBookSeedResult.Failed
} catch (failure: Exception) {
    PublicDomainBookSeedResult.Failed
}
