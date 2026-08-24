package com.air5005.pagenest.speech.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SpeechSettingsModule {
    @Binds
    @Singleton
    abstract fun bindSpeechPreferencesRepository(
        implementation: DataStoreSpeechPreferencesRepository,
    ): SpeechPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindSpeechPlaybackActions(
        implementation: ReaderSpeechManager,
    ): SpeechPlaybackActions
}
