package com.baybin.phone

/**
 * What to show for a bin the rules already chose. Does not decide the bin.
 *
 * Colors are the carts those cities tell residents to set out, not a guess:
 * Cupertino (cupertino.gov): blue recycling, green organics, gray landfill.
 * San José single-family: recycling is a gray cart with a blue lid (city guide),
 * garbage is the black cart (GreenWaste San Jose; GreenTeam spec is black too),
 * yard trimmings are the green cart. San José's green cart is not for food.
 * Palo Alto single-family (GreenWaste): blue recycling, green compost, black garbage.
 * Food goes in the green cart, not the black one.
 * Los Altos single-family (Mission Trail residential guide): blue recycling,
 * green organics, gray garbage. Line1 stays "Landfill" so the lens says gray, not black.
 * Berkeley households: blue recycling, green compost, grey trash. Line1 stays "Landfill".
 * Recycling for 1–9 units is the Ecology Center cart, split into paper and containers.
 */
object BinFace {

    class Face(
        val title: String,
        /** Null: keep the answer's own second line (a reason, an error). */
        val note: String?,
        val background: Int,
        val onLight: Boolean,
    )

    /** [zh] only picks the words. The color still comes from [line1], which the rules already chose. */
    fun of(city: String, line1: String, zh: Boolean): Face {
        val (title, note) = words(city, line1, zh)
        val (background, onLight) = paint(line1)
        return Face(title, note, background, onLight)
    }

    /** Short enough for the glasses lens. The log still keeps the official bin name. */
    fun lens(line1: String, zh: Boolean): String = when {
        line1.startsWith("Recycling") -> if (zh) "蓝桶" else "Blue cart"
        line1.startsWith("Compost") -> if (zh) "绿桶" else "Green cart"
        line1.startsWith("Landfill") -> if (zh) "灰桶" else "Gray cart"
        line1.startsWith("Garbage") -> if (zh) "黑桶" else "Black cart"
        line1.startsWith("Yard") -> if (zh) "绿桶" else "Green cart"
        line1.startsWith("Special") -> if (zh) "别放桶里" else "Not in carts"
        line1.startsWith("Not sure") -> if (zh) "不确定" else "Not sure"
        line1.startsWith("No connection") -> if (zh) "没连上" else "No connection"
        else -> line1
    }

    private fun words(city: String, line1: String, zh: Boolean): Pair<String, String?> = when {
        line1.startsWith("Recycling") -> lens(line1, zh) to when (city) {
            "san_jose" -> if (zh) "回收。桶身是灰的，盖子是蓝的。" else "Recycling. Gray body, blue lid."
            "berkeley" -> if (zh) "蓝桶。一边放纸，一边放瓶罐。" else "Blue cart. Paper on one side, bottles and cans on the other."
            else -> if (zh) "回收。" else "Recycling."
        }
        line1.startsWith("Compost") -> lens(line1, zh) to when (city) {
            "cupertino", "palo_alto", "los_altos", "berkeley" ->
                if (zh) "厨余、食物脏了的纸、庭院。" else "Food, food-soiled paper, and yard trimmings."
            else -> null
        }
        line1.startsWith("Landfill") -> lens(line1, zh) to when (city) {
            "cupertino", "los_altos", "berkeley" ->
                if (zh) "不能回收、也不能堆肥的。" else "Not recyclable and not compostable."
            else -> null
        }
        // Food placement is not shared. Palo Alto's black cart is not for food.
        // San José's black cart is. Any other city keeps its own reason and no borrowed note.
        line1.startsWith("Garbage") -> lens(line1, zh) to when (city) {
            "palo_alto" -> if (zh) "食物放绿桶，不要放这只。" else "Food goes in the green cart, not in this one."
            "san_jose" -> if (zh) "垃圾。厨余也放这只，不要放绿桶。" else "Garbage. Food goes here too, not in the green cart."
            else -> null
        }
        line1.startsWith("Yard") -> lens(line1, zh) to when (city) {
            "san_jose" -> if (zh) "只放庭院修剪，不放食物。" else "Yard trimmings only. No food."
            else -> null
        }
        line1.startsWith("Special") -> (if (zh) "别放进门口的桶" else "Not in the carts") to
            if (zh) "要另外交出去。" else "Take it somewhere else."
        line1.startsWith("Not sure") -> lens(line1, zh) to null
        line1.startsWith("No connection") -> lens(line1, zh) to null
        else -> line1 to null
    }

    private fun paint(line1: String): Pair<Int, Boolean> = when {
        line1.startsWith("Recycling") -> R.color.bin_blue to false
        line1.startsWith("Compost") -> R.color.bin_green to false
        line1.startsWith("Landfill") -> R.color.bin_gray to false
        line1.startsWith("Garbage") -> R.color.bin_black to false
        line1.startsWith("Yard") -> R.color.bin_green to false
        line1.startsWith("Special") -> R.color.bin_special_solid to false
        line1.startsWith("Not sure") -> R.color.bin_unsure to true
        line1.startsWith("No connection") -> R.color.bin_offline to true
        else -> R.color.card to true
    }
}
