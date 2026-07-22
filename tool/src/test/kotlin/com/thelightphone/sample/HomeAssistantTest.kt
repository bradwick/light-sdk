package com.thelightphone.sample

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeAssistantTest {

    @Test
    fun testParseParams() {
        val inputText = "brightness=255\ntemp=22.5\nname=Living Room\ninvalidline"
        val parsed = parseParams(inputText)

        assertEquals(3, parsed.size)
        assertEquals("255", parsed["brightness"])
        assertEquals("22.5", parsed["temp"])
        assertEquals("Living Room", parsed["name"])
        assertTrue(!parsed.containsKey("invalidline"))
    }

    @Test
    fun testHaEntityDomain() {
        val entity = HaEntity(
            entityId = "light.living_room",
            name = "Living Room Light",
            state = "off"
        )
        assertEquals("light", entity.domain)
    }

    private fun parseParams(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .forEach { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (key.isNotEmpty()) {
                    result[key] = value
                }
            }
        return result
    }
}
