package com.wxn.reader.presentation.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTopLevelDestinationTest {
    @Test
    fun `destination indices remain stable`() {
        assertEquals(0, HomeTopLevelDestination.SHELF.index)
        assertEquals(1, HomeTopLevelDestination.DISCOVERY.index)
        assertEquals(2, HomeTopLevelDestination.AUDIO.index)
        assertEquals(3, HomeTopLevelDestination.MINE.index)
    }

    @Test
    fun `index lookup rejects invalid persisted values`() {
        HomeTopLevelDestination.entries.forEach { destination ->
            assertEquals(destination, HomeTopLevelDestination.fromIndex(destination.index))
        }
        assertNull(HomeTopLevelDestination.fromIndex(-1))
        assertNull(HomeTopLevelDestination.fromIndex(4))
    }
}
