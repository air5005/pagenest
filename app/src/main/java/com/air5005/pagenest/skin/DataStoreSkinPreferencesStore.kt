package com.air5005.pagenest.skin

import android.content.Context
import com.wxn.base.skin.SkinCanonicalStore
import com.wxn.base.skin.SkinCanonicalState
import com.wxn.bookread.data.source.local.ReaderPreferencesUtil
import com.wxn.reader.data.source.local.AppPreferencesUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DataStoreSkinPreferencesStore @Inject constructor(
    @ApplicationContext context: Context,
    private val appPreferencesUtil: AppPreferencesUtil,
    private val readerPreferencesUtil: ReaderPreferencesUtil,
) : SkinPreferencesStore {
    private val canonicalStore = SkinCanonicalStore(context)

    override suspend fun snapshot(): SkinPreferenceSnapshot = SkinPreferenceSnapshot(
        homeBackground = appPreferencesUtil.appPrefsFlow.first().homeBackgroundImage,
        readerBackground = readerPreferencesUtil.readerPrefsFlow.first().backgroundImage,
        canonicalState = canonicalState(),
    )

    override suspend fun setHomeBackground(path: String) {
        appPreferencesUtil.updateHomeBackgroundImage(path)
    }

    override suspend fun setReaderBackground(path: String) {
        readerPreferencesUtil.updateBackgroundImage(path)
    }

    override suspend fun canonicalState(): SkinCanonicalState? = withContext(Dispatchers.IO) {
        canonicalStore.read()
    }

    override suspend fun setCanonicalState(state: SkinCanonicalState?) = withContext(Dispatchers.IO) {
        if (state == null) canonicalStore.clear() else canonicalStore.write(state)
    }
}
