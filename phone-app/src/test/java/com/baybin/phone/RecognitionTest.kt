package com.baybin.phone

import org.junit.Assert.*
import org.junit.Test

class RecognitionTest {
    private val ids = setOf("plastic_cup", "plastic_tub", "plastic_cap", "foam_food_container", "food_in_container",
        "carton", "paperboard_box", "paper_takeout", "aerosol_can", "aluminum_can", "plastic_bottle",
        "ceramic", "aluminum_tray", "aluminum_foil", "glass_jar", "food_can", "drinking_glass",
        "cardboard_box", "pizza_box_greasy", "aseptic_carton")
    private fun seen(id: String, food: String = "none", material: String = "unknown", empty: String = "unknown",
                     paper: String = "unknown", label: String = "unknown", multiple: String = "no") =
        Recognition.Observation(id, food, material, empty, paper, label, multiple)

    @Test fun ambiguousOrProseReplyCannotSelectAConfidentBin() {
        for (text in listOf("unknown or plastic_cup", "plastic_cup plastic_tub", "not plastic_cup", "id: plastic_cup"))
            assertEquals(text, "unknown", Recognition.parseId(text, ids))
        assertEquals("plastic_cup", Recognition.parseId("\"plastic_cup\".", ids))
        assertEquals("unknown", Recognition.parseId("unknown", ids))
    }

    @Test fun missingOrInvalidProbabilityDoesNotPass() {
        for (p in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.01, 0.49))
            assertFalse(Recognition.hasConfidence(p))
        assertTrue(Recognition.hasConfidence(0.5))
    }

    @Test fun verificationRequiresAllFieldsAndRejectsInventedValues() {
        val text = "item=plastic_cup\nfood=none\nmaterial=1\nempty=yes\npaper=unknown\nlabel=other\nmultiple=no"
        assertNotNull(Recognition.parseObservation(text, ids))
        assertNull(Recognition.parseObservation(text + "\nmaterial=5", ids))
        assertNull(Recognition.parseObservation(text.replace("material=1", "material=PET"), ids))
        assertNull(Recognition.parseObservation(text.substringBefore("\nmultiple"), ids))
    }

    @Test fun confidentPunctuationOrUnrelatedTokenCannotProveAnItem() {
        for (token in listOf(null, "", "\"", "```", "aluminum"))
            assertFalse(Recognition.reliableToken(0.99, token, "plastic_cup"))
        assertTrue(Recognition.reliableToken(0.8, "plastic", "plastic_cup"))
    }

    @Test fun visibleFoodInAnIndependentlyIdentifiedContainerUsesTheFoodCategory() {
        val text = "item=plastic_tub\nfood=present\nmaterial=unknown\nempty=no\npaper=unknown\nlabel=unknown\nmultiple=no"
        val meal = Recognition.parseObservation(text, ids)
        assertEquals("food_in_container", meal?.item)
        assertNull(Recognition.help("food_in_container", meal, emptySet()))
        val hidden = Recognition.parseObservation(text.replace("food=present", "food=unknown"), ids)
        assertEquals("plastic_tub", hidden?.item)
        assertEquals(Recognition.Help.OPEN, Recognition.help("food_in_container", hidden, emptySet()))
    }

    @Test fun oneStructuredReplyKeepsAnItemTokenAndCompleteEvidence() {
        val text = "plastic_tub\nfood=present\nmaterial=unknown\nempty=no\npaper=unknown\nlabel=unknown\nmultiple=no"
        val result = Recognition.parseInspection(text, ids)
        assertEquals("food_in_container", Recognition.canonicalId("plastic_tub", result))
        assertNull(Recognition.parseInspection("item=" + text, ids))
        assertNull(Recognition.parseInspection(text.substringBefore("\nmultiple"), ids))
    }

    @Test fun foodInCeramicMetalAndGlassMustNotDisposeOfTheServingContainer() {
        for (container in listOf("ceramic", "aluminum_tray", "aluminum_foil", "glass_jar", "food_can", "drinking_glass")) {
            val text = "$container\nfood=present\nmaterial=unknown\nempty=no\npaper=unknown\nlabel=unknown\nmultiple=no"
            val result = Recognition.parseInspection(text, ids)
            assertEquals(container, "food_in_container", Recognition.canonicalId(container, result))
            assertNull(Recognition.help("food_in_container", result, emptySet()))
        }
        for (container in listOf("ceramic", "aluminum_tray", "aluminum_foil", "glass_jar", "food_can", "drinking_glass")) {
            assertEquals(container, Recognition.Help.OPEN,
                Recognition.help(container, seen(container, food = "unknown"), emptySet()))
            assertNull(Recognition.help(container, seen(container), emptySet()))
        }
    }

    @Test fun foodInAPlasticCupIsSeparatedFromDrinksAndHiddenContents() {
        val text = "plastic_cup\nfood=present\nmaterial=1\nempty=no\npaper=unknown\nlabel=other\nmultiple=no"
        val meal = Recognition.parseInspection(text, ids)
        assertEquals("food_in_container", Recognition.canonicalId("plastic_cup", meal))
        val drink = Recognition.parseInspection(text.replace("food=present", "food=none"), ids)
        assertEquals("plastic_cup", Recognition.canonicalId("plastic_cup", drink))
        assertEquals(Recognition.Help.OPEN, Recognition.help("plastic_cup", seen("plastic_cup", food = "unknown"), emptySet()))
    }

    @Test fun cupRecyclingNeedsVisibleSupportedNumber() {
        val checks = setOf("resin_recyclable")
        for (code in listOf("unknown", "6", "compostable"))
            assertEquals(Recognition.Help.MARK, Recognition.help("plastic_cup", seen("plastic_cup", material = code), checks))
        for (code in listOf("1", "2", "3", "4", "5", "7"))
            assertNull(Recognition.help("plastic_cup", seen("plastic_cup", material = code), checks))
        assertEquals(Recognition.Help.MARK,
            Recognition.help("plastic_cup", seen("plastic_cup", material = "7"), setOf("resin_1_5")))
    }

    @Test fun supportedMaterialVariantComesOnlyFromTheSelectedCityRule() {
        val garbage = Rules.Rule("landfill", "Empty this #6 plastic cup, then put it in garbage.", null)
        val sanJose = Rules.Rule("recycling", "Recycle marked cups", null, setOf("resin_recyclable"), mapOf("6" to garbage))
        val otherCity = Rules.Rule("recycling", "Empty cups", null)
        assertSame(garbage, sanJose.forMaterial("6"))
        assertSame(sanJose, sanJose.forMaterial("unknown"))
        assertSame(otherCity, otherCity.forMaterial("6"))
        assertNull(Recognition.help("plastic_cup", seen("plastic_cup", material = "6"), garbage.checks))
        assertEquals(Recognition.Help.MARK,
            Recognition.help("plastic_cup", seen("plastic_cup"), sanJose.forMaterial("unknown").checks))
    }

    @Test fun foodAndHiddenContentsCannotBecomeAnEmptyContainer() {
        for (food in listOf("present", "unknown"))
            assertEquals(Recognition.Help.OPEN, Recognition.help("foam_food_container", seen("foam_food_container", food), emptySet()))
        assertNull(Recognition.help("food_in_container", seen("food_in_container", "present"), emptySet()))
        assertEquals(Recognition.Help.OPEN, Recognition.help("food_in_container", seen("food_in_container"), emptySet()))
    }

    @Test fun paperLiningAndAerosolEmptyStateAreRequiredOnlyByTheirCityRule() {
        assertEquals(Recognition.Help.LINING, Recognition.help("paper_takeout", seen("paper_takeout"), setOf("uncoated")))
        assertNull(Recognition.help("paper_takeout", seen("paper_takeout", paper = "uncoated"), setOf("uncoated")))
        assertNull(Recognition.help("paper_takeout", seen("paper_takeout"), emptySet()))
        assertEquals(Recognition.Help.EMPTY, Recognition.help("aerosol_can", seen("aerosol_can"), setOf("empty")))
    }

    @Test fun conflictingLabelsAndUnrelatedObjectsAskForRetake() {
        assertEquals(Recognition.Help.VERIFY, Recognition.help("aluminum_can", null, emptySet()))
        assertEquals(Recognition.Help.VERIFY, Recognition.help("plastic_cap", seen("plastic_tub"), emptySet()))
        assertEquals(Recognition.Help.ONE, Recognition.help("aluminum_can", seen("aluminum_can", multiple = "yes"), emptySet()))
        assertEquals(Recognition.Help.LABEL, Recognition.help("plastic_bottle", seen("plastic_bottle", label = "cleaner"), emptySet()))
        assertEquals(Recognition.Help.LABEL, Recognition.help("carton", seen("carton"), emptySet()))
    }

    @Test fun hiddenOrGreasyPaperBoxesCannotBecomeCleanRecycling() {
        for (id in listOf("cardboard_box", "paperboard_box")) {
            assertEquals(Recognition.Help.OPEN, Recognition.help(id, seen(id, food = "unknown"), emptySet()))
            for (paper in listOf("unknown", "soiled", "coated"))
                assertEquals(Recognition.Help.CLEAN, Recognition.help(id, seen(id, paper = paper), emptySet()))
            assertNull(Recognition.help(id, seen(id, paper = "clean"), emptySet()))
        }
        assertEquals(Recognition.Help.CLEAN,
            Recognition.help("pizza_box_greasy", seen("pizza_box_greasy", paper = "clean"), emptySet()))
        assertNull(Recognition.help("pizza_box_greasy", seen("pizza_box_greasy", paper = "soiled"), emptySet()))
        assertEquals(Recognition.Help.LABEL,
            Recognition.help("paperboard_box", seen("paperboard_box", paper = "clean", label = "milk"), emptySet()))
    }

    @Test fun uncertainCartonConstructionCannotChooseADifferentCityBin() {
        assertEquals(Recognition.Help.LABEL,
            Recognition.help("carton", seen("carton", label = "milk"), setOf("carton_gable")))
        assertEquals(Recognition.Help.LABEL,
            Recognition.help("aseptic_carton", seen("aseptic_carton", label = "milk"), setOf("carton_aseptic")))
        assertNull(Recognition.help("carton", seen("carton", paper = "gable", label = "milk"), setOf("carton_gable")))
        assertNull(Recognition.help("aseptic_carton", seen("aseptic_carton", paper = "aseptic"), setOf("carton_aseptic")))
        // Other cities may have the same ruling for both: no invented extra city condition.
        assertNull(Recognition.help("carton", seen("carton", label = "milk"), emptySet()))
    }

    @Test fun visibleCartonConstructionCorrectsTheNameAndMixedTypesStillAskForOneItem() {
        val text = "carton\nfood=none\nmaterial=unknown\nempty=unknown\npaper=aseptic\nlabel=milk\nmultiple=no"
        val result = Recognition.parseInspection(text, ids)
        assertEquals("aseptic_carton", Recognition.canonicalId("carton", result))
        assertNull(Recognition.help("aseptic_carton", result, setOf("carton_aseptic")))
        assertEquals(Recognition.Help.ONE, Recognition.help("aseptic_carton",
            Recognition.parseInspection(text.replace("multiple=no", "multiple=yes"), ids), setOf("carton_aseptic")))
    }

    @Test fun lensPreservesRetakeGuidanceAndPreparation() {
        assertEquals("拍清回收编号或可堆肥标志。",
            Lang.lensDetail("plastic_cup", "Plastic cup", Recognition.Help.MARK.text, "zh", 1))
        assertEquals("先倒出食物，空盒再拍",
            Lang.lensDetail("food_in_container", "Food", "Scrape the food into the compost cart first, then scan the empty container.", "zh"))
        assertEquals("碎纸先装袋，再放进回收桶。",
            Lang.lensDetail("shredded_paper", "Shredded paper", "Bag shredded paper first, then put it in recycling.", "zh"))
    }

    @Test fun qualityRejectsBlankExposureButAcceptsAnOrdinaryScene() {
        assertEquals(Recognition.Help.LIGHT, PhotoQuality.exposure(IntArray(100) { 12 }))
        assertEquals(Recognition.Help.GLARE, PhotoQuality.exposure(IntArray(100) { 252 }))
        assertEquals(Recognition.Help.CLOSER, PhotoQuality.exposure(IntArray(100) { 100 }))
        assertNull(PhotoQuality.exposure(IntArray(100) { it * 2 }))
    }
}
