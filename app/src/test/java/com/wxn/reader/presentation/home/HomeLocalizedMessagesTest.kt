package com.wxn.reader.presentation.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wxn.reader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeLocalizedMessagesTest {
    @Test
    fun libraryRefreshMessageIsReadableChineseCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("正在刷新书架", context.getString(R.string.refreshing_library))
    }
}
