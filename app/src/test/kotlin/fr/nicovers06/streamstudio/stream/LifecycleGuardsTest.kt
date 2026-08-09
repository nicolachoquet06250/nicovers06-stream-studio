package fr.nicovers06.streamstudio.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleGuardsTest {
    @Test
    fun `un ancien apercu ne peut pas liberer le nouvel apercu`() {
        val lease = IdentityLease<Any>()
        val oldPreview = Any()
        val newPreview = Any()

        assertTrue(lease.claim(oldPreview))
        assertTrue(lease.claim(newPreview))
        assertFalse(lease.release(oldPreview))
        assertTrue(lease.release(newPreview))
    }

    @Test
    fun `les callbacks d une ancienne generation sont ignores`() {
        val gate = GenerationGate()
        val firstGeneration = gate.open()
        val currentGeneration = gate.open()

        assertFalse(gate.isCurrent(firstGeneration))
        assertTrue(gate.isCurrent(currentGeneration))

        gate.invalidate()

        assertFalse(gate.isCurrent(currentGeneration))
    }
}
