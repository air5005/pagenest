package com.wxn.reader.presentation.home

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicDomainBookSeedPolicyTest {
    @Test
    fun nativeLibraryFailureIsRecoverable() = runTest {
        val result = seedPublicDomainBookSafely {
            throw UnsatisfiedLinkError("libappmobi.so not found")
        }

        assertEquals(PublicDomainBookSeedResult.Failed, result)
    }

    @Test
    fun ordinaryParserFailureIsRecoverable() = runTest {
        val result = seedPublicDomainBookSafely { error("broken sample") }

        assertEquals(PublicDomainBookSeedResult.Failed, result)
    }

    @Test(expected = CancellationException::class)
    fun cancellationStillPropagates() = runTest {
        seedPublicDomainBookSafely { throw CancellationException("stop") }
    }

    @Test
    fun successfulSeedReportsSeeded() = runTest {
        assertEquals(
            PublicDomainBookSeedResult.Seeded,
            seedPublicDomainBookSafely { },
        )
    }
}
