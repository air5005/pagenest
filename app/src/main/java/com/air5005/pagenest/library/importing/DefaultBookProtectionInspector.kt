package com.air5005.pagenest.library.importing

import java.io.File
import java.util.concurrent.CancellationException

class DefaultBookProtectionInspector(
    private val mobiEncrypted: (File) -> Boolean,
    private val pdfEncrypted: (File) -> Boolean,
    private val epubProtected: (File) -> Boolean,
) : BookProtectionInspector {
    override fun inspect(file: File, format: SupportedBookFormat): ProtectionVerdict = try {
        val protected = when (format) {
            SupportedBookFormat.MOBI,
            SupportedBookFormat.AZW3,
            -> mobiEncrypted(file)

            SupportedBookFormat.PDF -> pdfEncrypted(file)
            SupportedBookFormat.EPUB -> epubProtected(file)
            SupportedBookFormat.TXT -> false
        }

        if (protected) ProtectionVerdict.PROTECTED else ProtectionVerdict.CLEAR
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failure.promotedCancellation()?.let { throw it }
        ProtectionVerdict.UNREADABLE
    } catch (failure: LinkageError) {
        failure.promotedCancellation()?.let { throw it }
        ProtectionVerdict.UNREADABLE
    }
}
