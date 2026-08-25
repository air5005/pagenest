package com.air5005.pagenest.skin

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SkinModule {
    @Binds
    @Singleton
    abstract fun bindSkinImageImporter(implementation: AndroidSkinImageImporter): SkinImageImporter

    @Binds
    @Singleton
    abstract fun bindSkinPreferencesStore(implementation: DataStoreSkinPreferencesStore): SkinPreferencesStore
}
