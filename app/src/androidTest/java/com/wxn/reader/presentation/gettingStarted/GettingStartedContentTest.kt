package com.wxn.reader.presentation.gettingStarted

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.wxn.reader.R
import com.wxn.reader.ui.theme.PageNestTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GettingStartedContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsPageNestBrandAndExposesBothActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected = false
        var skipped = false

        compose.setContent {
            PageNestTheme(darkTheme = false) {
                GettingStartedContent(
                    buttonsEnabled = true,
                    onSelectDirectory = { selected = true },
                    onSkip = { skipped = true },
                )
            }
        }

        compose.onNodeWithTag("pagenest_onboarding").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.welcome_to_uread)).assertIsDisplayed()
        compose.onNodeWithTag("pagenest_select_directory").performClick()
        compose.onNodeWithTag("pagenest_skip").performClick()

        assertTrue(selected)
        assertTrue(skipped)
    }
}
