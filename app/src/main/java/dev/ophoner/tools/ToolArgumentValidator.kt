package dev.ophoner.tools

import kotlinx.serialization.json.*

/** Validates the JSON Schema subset used by built-in tools before approval or execution. */
object ToolArgumentValidator {
    fun validate(arguments: JsonObject, schema: JsonObject): String? = checkValue(arguments, schema, "arguments", 0)

    private fun checkValue(value: JsonElement, schema: JsonObject, path: String, depth: Int): String? {
        if (depth > 32) return "$path exceeds the nesting limit"
        val type = (schema["type"] as? JsonPrimitive)?.content
        val valid = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
            "null" -> value == JsonNull
            else -> true
        }
        if (!valid) return "$path must be $type"
        (schema["enum"] as? JsonArray)?.let { if (value !in it) return "$path must be one of $it" }
        if (value is JsonObject) {
            val required = (schema["required"] as? JsonArray).orEmpty()
            for (key in required) {
                val name = (key as? JsonPrimitive)?.content ?: continue
                if (name !in value) return "$path.$name is required"
            }
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            for ((key, child) in value) {
                val childSchema = properties[key] as? JsonObject
                if (childSchema != null) checkValue(child, childSchema, "$path.$key", depth + 1)?.let { return it }
                else if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) return "$path.$key is not allowed"
            }
        }
        if (value is JsonArray) {
            val items = schema["items"] as? JsonObject
            if (items != null) value.forEachIndexed { index, child ->
                checkValue(child, items, "$path[$index]", depth + 1)?.let { return it }
            }
        }
        if (value is JsonPrimitive && !value.isString) {
            value.doubleOrNull?.let { number ->
                (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number < it) return "$path must be at least $it" }
                (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number > it) return "$path must be at most $it" }
            }
        }
        return null
    }
}
