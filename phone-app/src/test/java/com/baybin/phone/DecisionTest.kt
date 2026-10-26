package com.baybin.phone

import com.baybin.protocol.Proto
import org.junit.Assert.*
import org.junit.Test

class DecisionTest {
    private val ids = listOf("plastic_takeout", "plastic_cup", "paper_cup", "plastic_bag", "cardboard_box", "pizza_box_greasy", "food_in_container")
    private val garbage = Rules.Rule("landfill", "Empty first, then garbage.", "https://example.com/garbage")
    private val recycling = Rules.Rule("recycling", "Empty and scrape first.", "https://example.com/recycling")
    private val city = Rules.City("san_jose", "San Jose", mapOf("landfill" to "Garbage", "recycling" to "Recycling"),
        mapOf("plastic_takeout" to Rules.Rule("recycling", "Empty and scrape first.", null, setOf("not_compostable")),
            "plastic_cup" to Rules.Rule("recycling", "Empty cups.", null, setOf("resin_recyclable"), mapOf("6" to garbage)),
            "paper_cup" to garbage, "plastic_bag" to garbage, "cardboard_box" to recycling,
            "pizza_box_greasy" to garbage, "food_in_container" to garbage), "https://example.com/guide")
    private val rules = Rules(ids.map { Rules.Item(it, it, "") }, mapOf(city.id to city), "", "")
    private val decision = Decision(rules)
    private fun photo(id: String, p: Double = 0.9, food: String = "unknown", paper: String = "unknown") =
        decision.decide(city, QwenClient.Reply(id, id, p, emptyList(), observation =
            Recognition.Observation(id, food, "unknown", "unknown", paper, "unknown", "no")))

    @Test fun foodAndMaterialAreAskedOneAtATimeWithoutAnotherCloudCall() {
        val first = photo("plastic_takeout")
        assertEquals("food", Clarification.next(first)?.field)
        val empty = decision.supplement(first, "none")!!
        assertEquals("material", Clarification.next(empty)?.field)
        val marked = decision.supplement(empty, "5")!!
        assertEquals(Proto.KIND_OK, marked.kind)
        assertEquals("recycling", marked.bin)
        assertEquals(setOf("food", "material"), marked.reply!!.userFields)
        assertEquals(0, marked.calls)
    }

    @Test fun foodStillInContainerChangesToTheFoodRuleRatherThanDisposingOfPackaging() {
        val answered = decision.supplement(photo("plastic_takeout"), "present")!!
        assertEquals("food_in_container", answered.itemId)
        assertEquals("landfill", answered.bin)
        assertEquals(Proto.KIND_OK, answered.kind)
    }

    @Test fun unknownDoesNotLoopAndUnrecognizedChoicesAreRejected() {
        val first = photo("plastic_takeout")
        assertNull(decision.supplement(first, "invented"))
        val unknown = decision.supplement(first, "unknown")!!
        assertEquals(Proto.KIND_UNSURE, unknown.kind)
        assertNull(Clarification.next(unknown))
    }

    @Test fun weakConfidenceCannotBeOverriddenByAnsweringAMaterialQuestion() {
        val weak = photo("plastic_takeout", p = 0.1, food = "none")
        assertNull(Clarification.next(weak))
        assertNull(decision.supplement(weak, "5"))
        assertEquals(Proto.KIND_UNSURE, weak.kind)
    }

    @Test fun resinSixUsesItsOwnCityVariantAndPaperBoxesStillNeedCleanliness() {
        val cup = decision.supplement(photo("plastic_cup", food = "none"), "6")!!
        assertEquals("landfill", cup.bin)
        val box = photo("cardboard_box", food = "none")
        assertEquals("paper", Clarification.next(box)?.field)
        assertEquals(Proto.KIND_OK, decision.supplement(box, "clean")!!.kind)
        assertEquals(Proto.KIND_UNSURE, decision.supplement(box, "soiled")!!.kind)
    }

    @Test fun manualCatalogLookupHasNoInventedModelConfidenceAndStillRequiresEvidence() {
        val bag = decision.lookup("plastic_bag", city.id)
        assertEquals(Proto.KIND_OK, bag.kind)
        assertNull(bag.pFirst)
        assertTrue(bag.reply!!.userIdentified)
        assertEquals(0, bag.calls)
        assertEquals(Proto.KIND_UNSURE, decision.lookup("plastic_takeout", city.id).kind)
        assertEquals(Proto.KIND_UNSURE, decision.lookup("not_in_catalog", city.id).kind)
        assertEquals(Proto.KIND_UNSURE, decision.lookup("plastic_bag", "unsupported").kind)
    }

    @Test fun soiledCardboardUsesOnlyAnExplicitlyCitedPaperVariant() {
        val variant = Rules.Rule("landfill", "Soiled cardboard goes in garbage.", "https://example.com/cardboard", setOf("soiled"))
        val conditional = Rules.Rule("recycling", "Clean cardboard.", null, paperRules = mapOf("soiled" to variant))
        val local = Rules.City("san_jose", "San Jose", city.binLabels, city.rules + ("cardboard_box" to conditional), city.guideUrl)
        val localRules = Rules(rules.items, mapOf(local.id to local), "", "")
        val seen = Recognition.Observation("cardboard_box", "none", "unknown", "unknown", "soiled", "unknown", "no")
        val reply = QwenClient.Reply("cardboard_box", "cardboard", 0.9, emptyList(), observation = seen)
        val selected = Decision(localRules).decide(local, reply)
        assertEquals(Proto.KIND_OK, selected.kind)
        assertEquals("landfill", selected.bin)
        assertEquals(variant.sourceUrl, selected.sourceUrl)
        assertEquals(Proto.KIND_UNSURE, decision.decide(city, reply).kind)
        assertEquals(Proto.KIND_UNSURE, Decision(localRules).decide(local,
            reply.copy(observation = seen.copy(paper = "unknown"))).kind)
    }
}
