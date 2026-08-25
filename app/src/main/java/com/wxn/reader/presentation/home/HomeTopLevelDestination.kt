package com.wxn.reader.presentation.home

enum class HomeTopLevelDestination(val index: Int) {
    SHELF(0),
    DISCOVERY(1),
    AUDIO(2),
    MINE(3),
    ;

    val showsLibraryContent: Boolean
        get() = this == SHELF || this == AUDIO

    companion object {
        fun fromIndex(index: Int): HomeTopLevelDestination? = entries.firstOrNull { it.index == index }
    }
}
