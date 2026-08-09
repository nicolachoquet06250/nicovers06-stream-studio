package fr.nicovers06.streamstudio.stream

import java.util.concurrent.atomic.AtomicLong

internal class IdentityLease<T : Any> {
    private var owner: T? = null

    @Synchronized
    fun claim(candidate: T): Boolean {
        val changed = owner !== candidate
        owner = candidate
        return changed
    }

    @Synchronized
    fun release(candidate: T): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }

    @Synchronized
    fun clear() {
        owner = null
    }
}

internal class GenerationGate {
    private val generation = AtomicLong(0)

    fun open(): Long = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate
}
