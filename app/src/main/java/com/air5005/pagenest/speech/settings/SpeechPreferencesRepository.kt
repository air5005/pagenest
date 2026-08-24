package com.air5005.pagenest.speech.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.air5005.pagenest.speech.model.SpeechMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.speechPreferencesDataStore by preferencesDataStore(name = "speech_preferences")

data class SpeechPreferences(
    val mode: SpeechMode = SpeechMode.AUTO,
    val localeTag: String = "zh-CN",
    val voiceId: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val onlineConsentGranted: Boolean = false,
)

interface SpeechPreferencesRepository {
    val preferences: Flow<SpeechPreferences>
    suspend fun update(transform: (SpeechPreferences) -> SpeechPreferences)
}

@Singleton
class DataStoreSpeechPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechPreferencesRepository {
    override val preferences: Flow<SpeechPreferences> = context.speechPreferencesDataStore.data.map { values ->
        SpeechPreferences(
            mode = values[MODE]?.let { runCatching { SpeechMode.valueOf(it) }.getOrNull() } ?: SpeechMode.AUTO,
            localeTag = values[LOCALE] ?: "zh-CN",
            voiceId = values[VOICE],
            rate = (values[RATE] ?: 1f).coerceIn(MIN_VALUE, MAX_VALUE),
            pitch = (values[PITCH] ?: 1f).coerceIn(MIN_VALUE, MAX_VALUE),
            onlineConsentGranted = values[ONLINE_CONSENT] ?: false,
        )
    }

    override suspend fun update(transform: (SpeechPreferences) -> SpeechPreferences) {
        context.speechPreferencesDataStore.edit { values ->
            val current = SpeechPreferences(
                mode = values[MODE]?.let { runCatching { SpeechMode.valueOf(it) }.getOrNull() } ?: SpeechMode.AUTO,
                localeTag = values[LOCALE] ?: "zh-CN",
                voiceId = values[VOICE],
                rate = values[RATE] ?: 1f,
                pitch = values[PITCH] ?: 1f,
                onlineConsentGranted = values[ONLINE_CONSENT] ?: false,
            )
            val updated = transform(current)
            values[MODE] = updated.mode.name
            values[LOCALE] = updated.localeTag
            if (updated.voiceId == null) values.remove(VOICE) else values[VOICE] = updated.voiceId
            values[RATE] = updated.rate.coerceIn(MIN_VALUE, MAX_VALUE)
            values[PITCH] = updated.pitch.coerceIn(MIN_VALUE, MAX_VALUE)
            values[ONLINE_CONSENT] = updated.onlineConsentGranted
        }
    }

    private companion object {
        const val MIN_VALUE = 0.25f
        const val MAX_VALUE = 2f
        val MODE = stringPreferencesKey("mode")
        val LOCALE = stringPreferencesKey("locale")
        val VOICE = stringPreferencesKey("voice")
        val RATE = floatPreferencesKey("rate")
        val PITCH = floatPreferencesKey("pitch")
        val ONLINE_CONSENT = booleanPreferencesKey("online_consent")
    }
}
