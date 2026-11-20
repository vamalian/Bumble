package com.example

import com.example.assistant.MathEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathEvaluatorTest {

    @Test
    fun `evaluates arithmetic and functions accurately`() {
        val res = MathEvaluator.evaluate("(125 * 4) + sqrt(144) - 2^3")
        assertTrue(res.isSuccess)
        assertEquals("504", MathEvaluator.formatResult(res.getOrThrow()))
    }

    @Test
    fun `rejects division by zero`() {
        val res = MathEvaluator.evaluate("42 / (5 - 5)")
        assertTrue(res.isFailure)
    }
}
