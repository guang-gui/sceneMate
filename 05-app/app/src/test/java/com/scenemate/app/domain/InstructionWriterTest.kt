package com.scenemate.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionWriterTest {

    @Test
    fun `后退 0_45m 输出半步`() {
        assertEquals("让她后退半步", InstructionWriter.forStance(Actor.SUBJECT, 0.45, 0.0))
    }

    @Test
    fun `向前 0_7m 输出一步`() {
        assertEquals("让她向前一步", InstructionWriter.forStance(Actor.SUBJECT, -0.7, 0.0))
    }

    @Test
    fun `摄影师视角用第二人称`() {
        assertEquals("你后退半步", InstructionWriter.forStance(Actor.PHOTOGRAPHER, 0.45, 0.0))
    }

    @Test
    fun `死区内不给指令`() {
        assertNull(InstructionWriter.forStance(Actor.SUBJECT, 0.20, 0.20))
    }

    @Test
    fun `横向小于 0_3m 不提示`() {
        assertNull(InstructionWriter.forStance(Actor.SUBJECT, 0.0, 0.28))
    }

    @Test
    fun `横向 0_5m 给方向`() {
        assertEquals("让她向右一步", InstructionWriter.forStance(Actor.SUBJECT, 0.0, 0.5))
        assertEquals("让她向左一步", InstructionWriter.forStance(Actor.SUBJECT, 0.0, -0.5))
    }

    @Test
    fun `距离优先于横向`() {
        assertEquals("让她后退半步", InstructionWriter.forStance(Actor.SUBJECT, 0.4, 0.9))
    }

    @Test
    fun `文案永远不含形容词`() {
        val samples = listOf(
            InstructionWriter.forStance(Actor.SUBJECT, 0.45, 0.0),
            InstructionWriter.forStance(Actor.PHOTOGRAPHER, -1.5, 0.0),
            InstructionWriter.forStance(Actor.SUBJECT, 0.0, 0.8),
        )
        samples.filterNotNull().forEach {
            assertTrue("命中禁止词: $it", InstructionLint.check(it).isEmpty())
        }
    }

    @Test
    fun `自定义代词生效`() {
        assertEquals("让他后退半步", InstructionWriter.forStance(Actor.SUBJECT, 0.45, 0.0, "他"))
        assertEquals("让TA后退半步", InstructionWriter.forStance(Actor.SUBJECT, 0.45, 0.0, "TA"))
    }
}
