package dev.ophoner.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8CostTest {
    @Test fun byteLengthMatchesJavaUtf8IncludingUnpairedSurrogates() {
        val samples = listOf(
            "",
            "hello",
            "café",
            "héllo 🌟",
            "line\n",
            "\uD800\uDC00",
            "\uD800",
            "\uDFFF",
            "a\uD800b",
        )
        for (sample in samples) {
            assertEquals(sample, sample.toByteArray(Charsets.UTF_8).size, ContextBudget.utf8Size(sample))
        }
    }
}
