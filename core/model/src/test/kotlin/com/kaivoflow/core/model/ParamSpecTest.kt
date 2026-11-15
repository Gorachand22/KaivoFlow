package com.kaivoflow.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamSpecTest {
    private fun spec(type: ParamType, supportsExpression: Boolean? = null) =
        if (supportsExpression == null) ParamSpec(key = "k", label = "K", type = type)
        else ParamSpec(key = "k", label = "K", type = type, supportsExpression = supportsExpression)

    @Test fun `code and secret fields are not expression expanded by default`() {
        assertFalse(spec(ParamType.Code).supportsExpression)
        assertFalse(spec(ParamType.Secret).supportsExpression)
    }

    @Test fun `ordinary fields still support expressions`() {
        assertTrue(spec(ParamType.Text).supportsExpression)
        assertTrue(spec(ParamType.Multiline).supportsExpression)
        assertTrue(spec(ParamType.Json).supportsExpression)
    }

    @Test fun `explicit override is respected`() {
        assertTrue(spec(ParamType.Code, supportsExpression = true).supportsExpression)
    }
}
