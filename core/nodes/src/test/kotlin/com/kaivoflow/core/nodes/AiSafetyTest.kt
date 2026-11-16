package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.nodes.ai.chains.AiExtractNode
import com.kaivoflow.core.nodes.ai.shared.AiExtractionSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiSafetyTest {
    @Test fun jsonParserAcceptsValidJsonWithEscapesAndDecimals() {
        val parsed = JsonLite.parse("""{"v":-12.5e2,"text":"line\nnext","nested":[true,null,{"ok":false}]}""") as Map<*, *>
        assertEquals(-1250.0, parsed["v"])
        assertEquals("line\nnext", parsed["text"])
        assertEquals(listOf(true, null, mapOf("ok" to false)), parsed["nested"])
    }

    @Test fun validWhitespaceAndStringEscapesAreParsedCorrectly() {
        assertEquals(mapOf("x" to "line\nnext", "symbol" to "A"),
            JsonLite.parse("  \n{\"x\":\"line\\nnext\", \"symbol\":\"\\u0041\"}\t"))
        assertNull(JsonLite.parse("\"bad\ntext\""))
    }

    @Test fun parserRejectsIncompleteAndMalformedJson() {
        val bad = listOf("{\"a\":1", "[1,2", "\"unclosed", "{\"a\":}", "{\"a\":1,}",
            "[1,]", "{\"a\":1,\"a\":2}", "+1", "01", "1.", "1e999", "\"bad\\q\"", "{\"x\":true} garbage")
        bad.forEach { assertNull("Unexpected acceptance: $it", JsonLite.parse(it)) }
        assertNull(JsonLite.parse("[".repeat(70) + "0" + "]".repeat(70)))
    }

    @Test fun shallowSchemaAcceptsJsonTextAndChecksTypes() {
        val schema = AiExtractionSchema.parse("""{"name":"string","age":"integer","active":"boolean"}""")
        assertNull(AiExtractionSchema.validate(schema, mapOf("name" to "A", "age" to 2.0, "active" to null)))
        assertNotNull(AiExtractionSchema.validate(schema, mapOf("name" to "A", "age" to 2.5, "active" to true)))
        assertNotNull(AiExtractionSchema.validate(schema, mapOf("name" to "A", "age" to 2.0)))
        assertNotNull(AiExtractionSchema.validate(schema, mapOf("name" to "A", "age" to 2.0, "active" to true, "extra" to "x")))
    }

    @Test fun invalidSchemaIsRejectedBeforeNetworkRequest() = runBlocking {
        val http = RecordingHttp()
        val failure = runCatching {
            AiExtractNode().execute(ctx(stepOf("ai.extract", params = mapOf("credential" to "cred-1", "model" to "m", "text" to "x", "schema" to "{broken")), listOf(item()), http = http))
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun wrongModelOutputTypeFailsInsteadOfPassingDataDownstream() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("choices" to listOf(mapOf("message" to mapOf("content" to """{"amount":"not a number"}"""))))) }
        val failure = runCatching {
            AiExtractNode().execute(ctx(stepOf("ai.extract", params = mapOf("credential" to "cred-1", "model" to "m", "text" to "x", "schema" to """{"amount":"number"}""")), listOf(item()), http = http))
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("wrong type"))
    }

    @Test fun jsonStringSchemaIsSentAsAnObjectDescriptionNotQuotedJson() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("choices" to listOf(mapOf("message" to mapOf("content" to """{"amount":2}"""))))) }
        val result = AiExtractNode().execute(ctx(stepOf("ai.extract", params = mapOf("credential" to "cred-1", "model" to "m", "text" to "x", "schema" to """{"amount":"number"}""")), listOf(item()), http = http))
        assertEquals(2.0, result.port(0)[0].json["amount"])
        val body = http.requests.single().body.toString()
        assertTrue(body.contains("{\"amount\":\"number\"}"))
        assertFalse(body.contains("\\\"amount\\\""))
    }
}
