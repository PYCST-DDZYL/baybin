package com.baybin.phone

import android.content.Context
import org.json.JSONObject

/**
 * The item catalog, each city's official-source rules and the model prompt, read from the
 * app's assets. They are copied there at build time from the repo's `rules/` folder (the
 * files tools/verify_rules.py checks against the official pages), so app and checks
 * always use the same data.
 */
class Rules private constructor(
    val items: List<Item>,
    val cities: Map<String, City>,
    private val promptTemplate: String,
) {
    class Item(val id: String, val name: String, val hint: String)

    /** [bin] is a key of [City.binLabels], or "unknown" when the official sources don't settle it. */
    class Rule(val bin: String, val reason: String, val sourceUrl: String?)

    class City(val id: String, val name: String, val binLabels: Map<String, String>, val rules: Map<String, Rule>)

    val ids: Set<String> = items.mapTo(HashSet()) { it.id }

    fun item(id: String): Item? = items.firstOrNull { it.id == id }

    /** rules/prompt.txt with the catalog filled in; tools/eval_probe.py builds the same text. */
    val prompt: String = promptTemplate.trim().replace(
        "{catalog}", items.joinToString("\n") { "- ${it.id}: ${it.name} (${it.hint})" })

    companion object {
        val CITY_IDS = listOf("cupertino", "san_jose", "palo_alto", "los_altos")

        fun load(context: Context): Rules {
            fun read(name: String) = context.assets.open("rules/$name").bufferedReader().use { it.readText() }

            val itemArray = JSONObject(read("items.json")).getJSONArray("items")
            val items = List(itemArray.length()) { i ->
                val o = itemArray.getJSONObject(i)
                Item(o.getString("id"), o.getString("name"), o.optString("hint"))
            }
            val cities = CITY_IDS.associateWith { id ->
                val o = JSONObject(read("$id.json"))
                val bins = o.getJSONObject("bins").let { b ->
                    b.keys().asSequence().associateWith { k -> b.getJSONObject(k).getString("line1") }
                }
                val sources = o.optJSONObject("sources")
                fun page(key: String): String? {
                    if (key.isEmpty() || sources == null || !sources.has(key)) return null
                    val s = sources.optJSONObject(key) ?: return null
                    val live = s.optString("live_url").trim()
                    if (live.startsWith("http")) return live
                    val url = s.optString("url").trim()
                    return url.takeIf { it.startsWith("http") }
                }
                val rules = o.getJSONObject("rules").let { r ->
                    r.keys().asSequence().associateWith { k ->
                        val rule = r.getJSONObject(k)
                        Rule(rule.getString("bin"), rule.optString("reason"), page(rule.optString("source")))
                    }
                }
                City(id, o.getString("name"), bins, rules)
            }
            return Rules(items, cities, read("prompt.txt"))
        }
    }
}
