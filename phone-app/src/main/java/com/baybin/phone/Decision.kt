package com.baybin.phone

import com.baybin.protocol.Proto

/** Shared, network-free decision path for photos, explicit catalog choices and follow-up answers. */
internal class Decision(private val rules: Rules) {
    fun decide(city: Rules.City, reply: QwenClient.Reply, prepMs: Long = 0, modelMs: Long = 0): Pipeline.Answer {
        val rawId = Recognition.parseId(reply.text, rules.ids)
        val id = Recognition.canonicalId(rawId, reply.observation)
        fun answer(kind: Int, line1: String, line2: String, bin: String?, page: String? = null,
                   help: Recognition.Help? = null) = Pipeline.Answer(kind, line1, line2, id, bin, reply.firstP,
            prepMs, modelMs, reply.calls, reply, city.id, page, help)
        if (rules.item(id) == null) return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE,
            "Couldn't tell what it is. Try closer.", null)
        val rule = city.rules[id]?.forEvidence(reply.observation?.material, reply.observation?.paper)
        if (rule == null || rule.bin == "unknown") return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE,
            Recognition.Help.CITY.text, rule?.bin, rule?.sourceUrl)
        if (!reply.userIdentified) {
            val rival = reply.alternatives.any { (token, p) -> p >= Pipeline.P_ALT && changesBin(city, token, rawId, rule.bin) }
            if (!Recognition.reliableToken(reply.firstP, reply.firstToken, rawId) || rival) {
                return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, Recognition.Help.CLOSER.text, rule.bin, rule.sourceUrl)
            }
        }
        Recognition.help(id, reply.observation, rule.checks)?.let {
            return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, it.text, rule.bin, rule.sourceUrl, it)
        }
        return answer(Proto.KIND_OK, city.binLabels[rule.bin] ?: rule.bin, rule.reason, rule.bin, rule.sourceUrl)
    }

    fun lookup(itemId: String, cityId: String): Pipeline.Answer {
        val city = rules.cities[cityId] ?: return Pipeline.Answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE,
            "No rules for this city", cityId = cityId)
        val seen = Recognition.Observation(itemId, "unknown", "unknown", "unknown", "unknown", "unknown", "no")
        return decide(city, QwenClient.Reply(itemId, null, null, emptyList(), 0, seen, userIdentified = true))
    }

    fun supplement(previous: Pipeline.Answer, value: String): Pipeline.Answer? {
        val question = Clarification.next(previous) ?: return null
        if (question.choices.none { it.value == value }) return null
        val reply = previous.reply ?: return null
        val seen = reply.observation ?: return null
        val fields = linkedMapOf("item" to seen.item, "food" to seen.food, "material" to seen.material,
            "empty" to seen.empty, "paper" to seen.paper, "label" to seen.label, "multiple" to seen.multiple)
        fields[question.field] = value
        val updated = Recognition.parseObservation(fields.entries.joinToString("\n") { "${it.key}=${it.value}" }, rules.ids)
            ?: return null
        val city = rules.cities[previous.cityId] ?: return null
        return decide(city, reply.copy(observation = updated, calls = 0, userFields = reply.userFields + question.field))
    }

    private fun changesBin(city: Rules.City, token: String, selectedId: String, selectedBin: String): Boolean {
        val prefix = token.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        if (prefix.isEmpty() || selectedId.startsWith(prefix)) return false
        val bins = HashSet<String>()
        if ("unknown".startsWith(prefix)) bins += "unknown"
        for (id in rules.ids) if (id.startsWith(prefix)) bins += city.rules[id]?.bin ?: "unknown"
        return bins.isNotEmpty() && bins.all { it != selectedBin }
    }
}
