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
    private val inspectionTemplate: String,
) {
    class Item(val id: String, val name: String, val hint: String)

    /** [bin] is a key of [City.binLabels], or "unknown" when the official sources don't settle it. */
    class Rule(val bin: String, val reason: String, val sourceUrl: String?, val checks: Set<String> = emptySet(),
               val materialRules: Map<String, Rule> = emptyMap()) {
        fun forMaterial(material: String?): Rule = material?.let { materialRules[it] } ?: this
    }

    class City(val id: String, val name: String, val binLabels: Map<String, String>, val rules: Map<String, Rule>,
               val guideUrl: String)

    val ids: Set<String> = items.mapTo(HashSet()) { it.id }

    fun item(id: String): Item? = items.firstOrNull { it.id == id }

    /** rules/prompt.txt with the catalog filled in; tools/eval_probe.py builds the same text. */
    val prompt: String = promptTemplate.trim().replace(
        "{catalog}", items.joinToString("\n") { "- ${it.id}: ${it.name} (${it.hint})" })

    /** One structured call: bare id first keeps its probability usable; six evidence lines follow. */
    val inspectionPrompt: String = inspectionTemplate.trim().replace(
        "{catalog}", items.joinToString("\n") { "- ${it.id}: ${it.hint}" })
        .replace("item=<catalog id or unknown>", "<catalog id or unknown>")
        .replace("Return exactly these seven lines", "The first line must be the bare item id, without item=. Return exactly these seven lines")

    companion object {
        val CITY_IDS = listOf("cupertino", "san_jose", "palo_alto", "los_altos", "berkeley")

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
                    fun parseRule(rule: JSONObject, conditional: Boolean = false): Rule {
                        val checks = rule.optJSONArray("checks")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }.toSet()
                        } ?: emptySet()
                        require(checks.all { it in Recognition.CHECKS }) { "Unsupported rule check: $id" }
                        val byMaterial = rule.optJSONObject("by_material")
                        require(!conditional || byMaterial == null) { "Nested conditional rule: $id" }
                        val variants = byMaterial?.keys()?.asSequence()?.associateWith { code ->
                            require(code in setOf("1", "2", "3", "4", "5", "6", "7"))
                            parseRule(byMaterial.getJSONObject(code), true)
                        } ?: emptyMap()
                        return Rule(rule.getString("bin"), rule.optString("reason"), page(rule.optString("source")), checks, variants)
                    }
                    r.keys().asSequence().associateWith { k -> parseRule(r.getJSONObject(k)) }
                }
                City(id, o.getString("name"), bins, rules, o.getString("guide_url"))
            }
            return Rules(items, cities, read("prompt.txt"), read("inspection.txt"))
        }
    }
}
