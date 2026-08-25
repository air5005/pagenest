package com.air5005.pagenest.discovery.download

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides
    @Singleton
    fun provideBookDownloadTransport(): BookDownloadTransport = OkHttpBookDownloadTransport()

    @Provides
    @Singleton
    fun provideStagingFileStore(
        @ApplicationContext context: Context,
    ): StagingFileStore = StagingFileStore(stagingDirectory(context.filesDir)).also {
        it.cleanupOldParts()
    }

    @Provides
    @Singleton
    fun provideSecureBookDownloader(
        transport: BookDownloadTransport,
        stagingFileStore: StagingFileStore,
    ): SecureBookDownloader = SecureBookDownloader(
        transport = transport,
        urlPolicy = DownloadUrlPolicy(),
        validator = DownloadedBookValidator(),
        stagingFileStore = stagingFileStore,
    )

    fun stagingDirectory(filesDirectory: File): File =
        File(filesDirectory, "online-book-staging")
}
