package com.air5005.pagenest.discovery.importing

import android.content.Context
import com.air5005.pagenest.discovery.download.SecureBookDownloader
import com.air5005.pagenest.library.importing.BookImportService
import com.wxn.reader.domain.repository.BooksRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OnlineImportModule {
    @Provides
    @Singleton
    fun provideOnlineBookDownloader(
        downloader: SecureBookDownloader,
    ): OnlineBookDownloader = SecureOnlineBookDownloader(downloader)

    @Provides
    @Singleton
    fun provideOnlineBookImporter(
        importService: BookImportService,
    ): OnlineBookImporter = BookImportServiceAdapter(importService)

    @Provides
    @Singleton
    fun provideOnlineImportLedger(
        @ApplicationContext context: Context,
    ): OnlineImportLedger = FileOnlineImportLedger(ledgerFile(context.filesDir))

    @Provides
    @Singleton
    fun provideLocalBookLookup(
        repository: BooksRepository,
    ): LocalBookLookup = BooksRepositoryLocalBookLookup(repository)

    @Provides
    @Singleton
    fun provideOnlineBookImportCoordinator(
        downloader: OnlineBookDownloader,
        importer: OnlineBookImporter,
        ledger: OnlineImportLedger,
        localBookLookup: LocalBookLookup,
    ): OnlineImportCoordinator = OnlineBookImportCoordinator(
        downloader,
        importer,
        ledger,
        localBookLookup,
    )

    fun ledgerFile(filesDirectory: File): File =
        File(File(filesDirectory, "online-import"), "ledger.json")
}
