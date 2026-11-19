package com.prism.music.data.enhance

/**
 * One ONNX session, built when first asked for and kept afterwards: a walk through several songs pays the model's load
 * (five to nine seconds, and the memory it touches) once instead of once per song.
 *
 * Who lets it go: [EnhancementService] closes the slots when the queue has been empty for a while, so an idle phone
 * isn't holding a gigabyte it isn't using, and after a run that can't be trusted to carry on with the same session.
 * Everything that takes a session runs on the service's one heavy thread, so [get] and [drop] never race a run.
 */
internal class EngineSlot<T : AutoCloseable>(private val make: () -> T) : AutoCloseable {
    private var value: T? = null

    @Synchronized fun get(): T = value ?: make().also { value = it }

    /** Whether a session is built and waiting (for the room check before building the other one). */
    @Synchronized fun cached(): Boolean = value != null

    /** Closes the session; the next [get] builds a new one. Safe when there is none. */
    @Synchronized fun drop() {
        runCatching { value?.close() }
        value = null
    }

    override fun close() = drop()
}
