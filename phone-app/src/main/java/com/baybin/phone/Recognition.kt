package com.baybin.phone

/** Photo evidence is separate from the city's disposal rules. No model-picked bin. */
object Recognition {
    data class Observation(
        val item: String,
        val food: String,
        val material: String,
        val empty: String,
        val paper: String,
        val label: String,
        val multiple: String,
    )

    enum class Help(val text: String) {
        CLOSER("Move closer, hold steady, and photograph one item."),
        LIGHT("Too dark. Move into better light and retake."),
        GLARE("Too bright. Change angle to avoid glare and retake."),
        INVALID("Couldn't read the photo. Choose another or retake."),
        ONE("Photograph one item; keep its matching lid beside it."),
        OPEN("Open the container and photograph what is inside."),
        MARK("Photograph the recycling number or COMPOSTABLE marking."),
        LABEL("Turn the printed label toward the camera and retake."),
        LINING("Check the inner lining; photograph the open paper container."),
        CLEAN("Open the box; photograph the inside and any food stains or grease."),
        EMPTY("Confirm it is completely empty; photograph the opening."),
        VERIFY("Couldn't confirm the item. Change angle and retake."),
        CITY("This city has no clear rule. Check the official guide."),
    }

    /** A prose answer or several ids is not a single reliable identification. */
    fun parseId(text: String, ids: Set<String>): String {
        val bare = text.trim().removeSurrounding("```").trim()
            .removeSuffix(".").trim().removeSurrounding("\"").removeSurrounding("'").removeSurrounding("`")
            .lowercase()
        return bare.takeIf { it in ids } ?: "unknown"
    }

    fun hasConfidence(p: Double?): Boolean = p != null && p.isFinite() && p in 0.5..1.0

    /** A high probability of punctuation is not confidence in an item. */
    fun reliableToken(p: Double?, token: String?, id: String): Boolean {
        val prefix = token?.trim()?.lowercase() ?: return false
        return hasConfidence(p) && prefix.isNotEmpty() &&
            prefix.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } && id.startsWith(prefix)
    }

    /** Bare id first, then six evidence lines: confidence belongs to the actual item token. */
    fun parseInspection(text: String, ids: Set<String>): Observation? {
        val lines = text.trim().lines()
        if (lines.size != 7) return null
        val id = parseId(lines.first(), ids)
        if (id == "unknown" && lines.first().trim() != "unknown") return null
        return parseObservation("item=$id\n" + lines.drop(1).joinToString("\n"), ids)
    }

    fun canonicalId(id: String, seen: Observation?): String = when {
        seen?.food == "present" && seen.item == "food_in_container" && id in FOOD_CONTAINERS -> "food_in_container"
        id in CARTONS && seen?.item in CARTONS && seen?.paper in setOf("gable", "aseptic") -> seen!!.item
        else -> id
    }

    /** Strict, small line format: missing, duplicate, or invented fields fail closed. */
    fun parseObservation(text: String, ids: Set<String>): Observation? {
        val fields = LinkedHashMap<String, String>()
        for (line in text.trim().lines()) {
            val parts = line.trim().split('=', limit = 2)
            if (parts.size != 2) return null
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (fields.put(key, value) != null) return null
        }
        if (fields.keys != setOf("item", "food", "material", "empty", "paper", "label", "multiple")) return null
        if (fields["item"] !in ids && fields["item"] != "unknown") return null
        if (fields["food"] !in setOf("present", "none", "unknown")) return null
        if (fields["material"] !in setOf("1", "2", "3", "4", "5", "6", "7", "compostable", "unknown")) return null
        if (fields["empty"] !in setOf("yes", "no", "unknown")) return null
        if (fields["paper"] !in setOf("clean", "soiled", "uncoated", "coated", "gable", "aseptic", "unknown")) return null
        if (fields["label"] !in setOf("milk", "juice", "cleaner", "oil", "other", "unknown")) return null
        if (fields["multiple"] !in setOf("yes", "no", "unknown")) return null
        // Some replies name the box while separately reporting visible food. In this
        // catalog that physical evidence defines food_in_container, regardless of box material.
        val observedItem = fields.getValue("item")
        val carton = when (fields["paper"]) { "gable" -> "carton"; "aseptic" -> "aseptic_carton"; else -> null }
        val canonical = when {
            fields["food"] == "present" && observedItem in FOOD_CONTAINERS && "food_in_container" in ids -> "food_in_container"
            observedItem in CARTONS && carton in ids -> carton!!
            else -> observedItem
        }
        return Observation(canonical, fields.getValue("food"), fields.getValue("material"),
            fields.getValue("empty"), fields.getValue("paper"), fields.getValue("label"), fields.getValue("multiple"))
    }

    val CHECKS = setOf("resin_1_5", "resin_recyclable", "uncoated", "empty", "not_compostable", "carton_gable", "carton_aseptic")

    fun help(id: String, seen: Observation?, checks: Set<String>): Help? {
        if (seen == null) return Help.VERIFY
        if (seen.multiple != "no") return Help.ONE
        if (seen.item != id) return when {
            id in CONTAINERS || seen.item == "food_in_container" -> Help.OPEN
            id in setOf("carton", "paperboard_box", "plastic_bottle", "plastic_jug") -> Help.LABEL
            else -> Help.VERIFY
        }
        if (id == "food_scraps" && seen.food != "present") return Help.VERIFY
        if (id == "food_in_container" && seen.food != "present") return Help.OPEN
        if (id in FOOD_CONTAINERS && id != "food_in_container" && seen.food != "none") return Help.OPEN
        if (id in setOf("plastic_bottle", "plastic_jug")) {
            if (seen.label in setOf("unknown", "cleaner", "oil")) return Help.LABEL
        }
        if (id == "carton" && seen.label !in setOf("milk", "juice")) return Help.LABEL
        if (id == "paperboard_box" && seen.label in setOf("milk", "juice", "cleaner", "oil")) return Help.LABEL
        if (id in setOf("cardboard_box", "paperboard_box") && seen.paper != "clean") return Help.CLEAN
        if (id == "pizza_box_greasy" && seen.paper != "soiled") return Help.CLEAN
        if (id == "compostable_plastic" && seen.material != "compostable") return Help.MARK
        if (id in PLASTIC && seen.material == "compostable") return Help.MARK
        for (check in checks) {
            when (check) {
                "resin_1_5" -> if (seen.material !in setOf("1", "2", "3", "4", "5")) return Help.MARK
                "resin_recyclable" -> if (seen.material !in setOf("1", "2", "3", "4", "5", "7")) return Help.MARK
                "uncoated" -> if (seen.paper != "uncoated") return Help.LINING
                "empty" -> if (seen.empty != "yes") return Help.EMPTY
                "not_compostable" -> if (seen.material == "unknown" || seen.material == "compostable") return Help.MARK
                "carton_gable" -> if (seen.paper != "gable") return Help.LABEL
                "carton_aseptic" -> if (seen.paper != "aseptic") return Help.LABEL
                else -> return Help.VERIFY
            }
        }
        return null
    }

    private val PLASTIC = setOf("plastic_bottle", "plastic_jug", "plastic_tub", "plastic_clamshell",
        "plastic_cup", "plastic_takeout", "plastic_utensils", "plastic_cap")
    private val CONTAINERS = setOf("food_in_container", "pizza_box_greasy", "paper_cup", "paper_plate_used",
        "paper_takeout", "plastic_tub", "plastic_clamshell", "plastic_takeout", "foam_food_container", "frozen_food_box",
        "ceramic", "aluminum_tray", "aluminum_foil", "glass_jar", "food_can", "cardboard_box", "paperboard_box")
    private val FOOD_CONTAINERS = CONTAINERS + setOf("plastic_cup", "drinking_glass")
    private val CARTONS = setOf("carton", "aseptic_carton")
}
