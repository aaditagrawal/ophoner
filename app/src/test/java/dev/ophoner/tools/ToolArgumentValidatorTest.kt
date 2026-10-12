package dev.ophoner.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentValidatorTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["read","write"]},"count":{"type":"integer","minimum":1},"enabled":{"type":"boolean"},"options":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}},"required":["action"],"additionalProperties":false}""").jsonObject
    private fun check(json: String) = ToolArgumentValidator.validate(Json.parseToJsonElement(json).jsonObject, schema)
    @Test fun validArgumentsPass() { assertNull(check("""{"action":"read","count":2,"enabled":true,"options":{"path":"file"}}""")) }
    @Test fun missingRequiredFieldFails() { assertTrue(check("{}")!!.contains("required")) }
    @Test fun typesAreNotCoerced() {
        assertTrue(check("""{"action":"read","count":"2"}""")!!.contains("integer"))
        assertTrue(check("""{"action":"read","enabled":"true"}""")!!.contains("boolean"))
    }
    @Test fun enumBoundsAndNestedRequiredAreChecked() {
        assertNotNull(check("""{"action":"delete"}"""))
        assertNotNull(check("""{"action":"read","count":0}"""))
        assertTrue(check("""{"action":"read","options":{}}""")!!.contains("options.path"))
        assertNotNull(check("""{"action":"read","unknown":true}"""))
    }
}
