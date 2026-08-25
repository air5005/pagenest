package com.wxn.reader.branding

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider
import com.wxn.reader.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageNestBrandResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(@StringRes id: Int, language: String): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration).getString(id)
    }

    @Test
    fun appNameAndWelcomeCopyUsePageNestBrand() {
        assertEquals("页栖", text(R.string.app_name, "zh-CN"))
        assertEquals("欢迎来到页栖", text(R.string.welcome_to_uread, "zh-CN"))
        assertEquals("Welcome to PageNest", text(R.string.welcome_to_uread, "en-US"))
    }

    @Test
    fun launcherIconIsResolvableOnSupportedAndroid() {
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher))
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher_round))
    }
}
