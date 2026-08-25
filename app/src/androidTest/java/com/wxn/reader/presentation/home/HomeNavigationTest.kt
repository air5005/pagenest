package com.wxn.reader.presentation.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.wxn.reader.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun navigation_has_four_stable_destinations_and_selects_discovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var destination = HomeTopLevelDestination.SHELF
        compose.setContent {
            MaterialTheme {
                HomeNavigationBar(
                    currentDestination = destination,
                    onDestinationSelected = { destination = it },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.ebooks)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.discovery)).performClick()
        assertEquals(HomeTopLevelDestination.DISCOVERY, destination)
        compose.onNodeWithText(context.getString(R.string.audio_books))
        compose.onNodeWithText(context.getString(R.string.mine))
    }
}
