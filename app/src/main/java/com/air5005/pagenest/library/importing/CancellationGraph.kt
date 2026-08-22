package com.air5005.pagenest.library.importing

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

internal fun Throwable.promotedCancellation(): CancellationException? {
    val cancellation = findCancellationInGraph() ?: return null
    if (cancellation !== this && cancellation.suppressed.none { it === this }) {
        cancellation.addSuppressed(this)
    }
    return cancellation
}

internal fun Throwable.findCancellationInGraph(): CancellationException? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.addLast(this)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current is CancellationException) return current
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return null
}
