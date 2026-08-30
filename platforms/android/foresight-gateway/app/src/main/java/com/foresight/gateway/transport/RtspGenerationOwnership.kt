package com.foresight.gateway.transport

/** Prevents callbacks from a retired RootEncoder transport from affecting its replacement. */
internal class RtspGenerationOwnership {
    private var activeGeneration: Int? = null

    fun activate(generation: Int) {
        activeGeneration = generation
    }

    fun retire(generation: Int) {
        if (activeGeneration == generation) activeGeneration = null
    }

    fun accepts(generation: Int): Boolean = activeGeneration == generation
}
