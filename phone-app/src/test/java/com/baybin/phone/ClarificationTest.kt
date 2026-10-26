package com.baybin.phone

import com.baybin.protocol.Proto
import org.junit.Assert.*
import org.junit.Test

class ClarificationTest {
    private val items = listOf("plastic_bottle", "plastic_jug", "carton", "aseptic_carton")
    private val city = Rules.City("test", "Test", mapOf("recycling" to "Recycling", "landfill" to "Garbage"),
        mapOf("plastic_bottle" to Rules.Rule("recycling", "Empty first", null),
            "plastic_jug" to Rules.Rule("recycling", "Empty first", null),
            "carton" to Rules.Rule("recycling", "Fresh cartons", null, setOf("carton_gable")),
            "aseptic_carton" to Rules.Rule("landfill", "Aseptic cartons", null, setOf("carton_aseptic"))), "https://example.com")
    private val engine = Decision(Rules(items.map { Rules.Item(it, it, "") }, mapOf(city.id to city), "", ""))

    @Test fun drinkLabelsCanBeSuppliedWithoutACloudCall() {
        for (id in listOf("plastic_bottle", "plastic_jug")) {
            val first = engine.lookup(id, city.id)
            assertEquals("label", Clarification.next(first)?.field)
            val drink = engine.supplement(first, "other")!!
            assertEquals(Proto.KIND_OK, drink.kind)
            assertEquals(setOf("label"), drink.reply!!.userFields)
            assertNull(drink.pFirst)
            assertEquals(0, drink.calls)
        }
    }

    @Test fun hazardousOilAndUnknownLabelsCannotBecomeDrinkRecycling() {
        for (id in listOf("plastic_bottle", "plastic_jug", "carton")) for (label in listOf("cleaner", "oil", "unknown")) {
            val blocked = engine.supplement(engine.lookup(id, city.id), label)!!
            assertEquals(Proto.KIND_UNSURE, blocked.kind)
            assertNull(Clarification.next(blocked))
            assertNull(engine.supplement(blocked, "other"))
        }
    }

    @Test fun cartonConstructionCannotOverrideKnownHazardousOrOilLabels() {
        for (id in listOf("carton", "aseptic_carton")) for (label in listOf("cleaner", "oil")) {
            val text = "item=$id\nfood=none\nmaterial=unknown\nempty=unknown\npaper=aseptic\nlabel=$label\nmultiple=no"
            val seen = Recognition.parseObservation(text, items.toSet())!!
            val answer = engine.decide(city, QwenClient.Reply(id, id, 0.9, emptyList(), observation = seen))
            assertEquals(Proto.KIND_UNSURE, answer.kind)
            assertNull(Clarification.next(answer))
            assertNull(engine.supplement(answer, "gable"))
        }
    }

    @Test fun cartonConstructionUsesItsOwnCityRuleAfterLabelConfirmation() {
        val first = engine.lookup("carton", city.id)
        val other = engine.supplement(first, "other")!!
        assertEquals(Proto.KIND_UNSURE, other.kind)
        assertNull(Clarification.next(other))
        val milk = engine.supplement(first, "milk")!!
        assertEquals(Proto.KIND_UNSURE, milk.kind)
        assertEquals("paper", Clarification.next(milk)?.field)
        val fresh = engine.supplement(milk, "gable")!!
        assertEquals(Proto.KIND_OK, fresh.kind)
        assertEquals("recycling", fresh.bin)
        val aseptic = engine.supplement(milk, "aseptic")!!
        assertEquals(Proto.KIND_OK, aseptic.kind)
        assertEquals("aseptic_carton", aseptic.itemId)
        assertEquals("landfill", aseptic.bin)
        val unsure = engine.supplement(milk, "unknown")!!
        assertEquals(Proto.KIND_UNSURE, unsure.kind)
        assertNull(Clarification.next(unsure))
    }

    @Test fun lowConfidenceAndConflictingItemEvidenceCannotBeOverriddenByLabelAnswers() {
        val seen = Recognition.Observation("plastic_bottle", "none", "unknown", "unknown", "unknown", "unknown", "no")
        val weak = engine.decide(city, QwenClient.Reply("plastic_bottle", "plastic", 0.1, emptyList(), observation = seen))
        assertNull(Clarification.next(weak))
        val conflict = engine.decide(city, QwenClient.Reply("plastic_bottle", "plastic", 0.9, emptyList(),
            observation = seen.copy(item = "carton")))
        assertNull(Clarification.next(conflict))
    }
}
