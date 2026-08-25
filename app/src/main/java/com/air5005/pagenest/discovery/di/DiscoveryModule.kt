package com.air5005.pagenest.discovery.di

import android.content.Context
import com.air5005.pagenest.discovery.cache.CatalogCache
import com.air5005.pagenest.discovery.cache.FileCatalogCache
import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.repository.DiscoveryCatalogRepository
import com.air5005.pagenest.discovery.repository.OnlineDiscoveryRepository
import com.air5005.pagenest.discovery.source.gutendex.GutendexCatalogSource
import com.air5005.pagenest.discovery.source.opds.KnownOpdsSources
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DiscoveryNetworkClient

@Module
@InstallIn(SingletonComponent::class)
object DiscoveryModule {
    @Provides
    @Singleton
    @DiscoveryNetworkClient
    fun provideDiscoveryHttpClient(): HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
    }

    @Provides
    @Singleton
    fun provideDiscoverySourceRegistry(
        @DiscoveryNetworkClient client: HttpClient,
    ): DiscoverySourceRegistry = DiscoverySourceRegistry.create(
        gutendex = GutendexCatalogSource(client),
        gutenberg = KnownOpdsSources.gutenberg(client),
        standardEbooksFactory = { KnownOpdsSources.standardEbooks(client) },
        standardEbooksAuthorized = false,
    )

    @Provides
    @Singleton
    fun provideDiscoveryCache(
        @ApplicationContext context: Context,
    ): CatalogCache = FileCatalogCache(cacheDirectory(context.filesDir))

    @Provides
    @Singleton
    fun provideDiscoveryRepository(
        registry: DiscoverySourceRegistry,
        cache: CatalogCache,
    ): DiscoveryCatalogRepository = OnlineDiscoveryRepository(
        sources = registry.enabledSources,
        cache = cache,
    )

    fun cacheDirectory(filesDirectory: File): File = File(filesDirectory, "discovery-cache")
}
