package com.wxn.reader.presentation.mainReader

import com.wxn.base.bean.BookChapter

internal object ReaderProgressNavigator {
    fun navigate(
        newProgress: Double,
        chapters: List<BookChapter>,
        changeChapter: (chapterIndex: Int, progress: Double) -> Boolean,
    ): Boolean {
        val target = chapters.firstOrNullIndexed { index, chapter ->
            val start = chapter.chapterProgress.toDouble()
            val end = chapters.getOrNull(index + 1)?.chapterProgress?.toDouble() ?: 1.001
            newProgress >= start && newProgress < end
        } ?: return false

        return changeChapter(target.chapterIndex, newProgress)
    }

    private inline fun <T> List<T>.firstOrNullIndexed(predicate: (Int, T) -> Boolean): T? {
        for (index in indices) {
            val value = this[index]
            if (predicate(index, value)) return value
        }
        return null
    }
}
