package com.baybin.phone

import android.content.Context
import android.os.SystemClock
import com.baybin.protocol.Proto
import java.io.IOException

/**
 * Photo in, two lens lines out. Blocking; runs on a worker thread.
 *
 * The glasses path and the gallery test mode both call [run], so they exercise exactly the
 * same code. The model only names the item (an id from rules/items.json); the bin comes from
 * the chosen city's official-source rule, never from the model.
 *
 * "Not sure — check city guide" when:
 *  - the photo clearly shows two different things (a local check, before the model);
 *  - the model answers "unknown" or something outside the catalog;
 *  - the city's sources don't settle the item (rule "unknown");
 *  - the first-token probability says the model wasn't sure ([P_MIN]), or a runner-up
 *    token would put the item in a different bin ([P_ALT]). A runner-up counts only when
 *    every catalog id it could start is a different bin. The chosen id's own prefix is
 *    skipped: the token "food" also starts food_can (recycling), so it is not a second
 *    answer for food_in_container, and it is not a bin change for a greasy pizza box
 *    (same bin as food_in_container). The model's self-reported confidence turned out
 *    useless (always ~0.95), hence the logprobs.
 */
class Pipeline(private val context: Context, private val rules: Rules, private val qwen: QwenClient?) {

    class Answer(
        val kind: Int,
        val line1: String,
        val line2: String,
        /** Catalog id the model chose, or null if no model answer. */
        val itemId: String? = null,
        /** Rule bin key ("recycling", ..., "unknown"), or null. */
        val bin: String? = null,
        /** Probability of the model's first answer token. */
        val pFirst: Double? = null,
        /** Rotation + downscale on the phone. */
        val prepMs: Long = 0,
        /** Cloud time including a hedged second request; -1 if no call was made. */
        val modelMs: Long = -1,
        val calls: Int = 0,
        /**
         * Model reply this answer came from. Null when the model was never asked
         * (offline, no key, error). Not used to score any other city.
         */
        val reply: QwenClient.Reply? = null,
        /** City whose rule file chose the bin. Null only if no city was asked. */
        val cityId: String? = null,
        /** Official page for this ruling, when the rule cites one. Not shown as a raw URL. */
        val sourceUrl: String? = null,
    )

    /** Opens the cloud connection ahead of time (async, result ignored). */
    fun warmUp() {
        if (Net.online(context)) qwen?.warmUp()
    }

    fun run(jpeg: ByteArray, rotation: Int, cityId: String): Answer {
        // Missing id is not another city. Cupertino's file must not answer for it.
        val city = rules.cities[cityId]
            ?: return Answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, "No rules for this city", cityId = cityId)
        if (!Net.online(context)) {
            return Answer(Proto.KIND_NO_CONNECTION, Proto.TEXT_NO_CONNECTION, "Phone is offline", cityId = city.id)
        }
        val t0 = SystemClock.elapsedRealtime()
        val image = Upright.prepare(jpeg, rotation, MODEL_SIDE, quality = 80)
        val prepMs = SystemClock.elapsedRealtime() - t0

        if (qwen == null) {
            return Answer(Proto.KIND_INFO, "No API key", "Type your own key in the phone app.",
                prepMs = prepMs, cityId = city.id)
        }
        if (TwoThings.seesTwo(image)) {
            return Answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, "Two things in the photo. Hold up one.",
                prepMs = prepMs, cityId = city.id)
        }
        val t1 = SystemClock.elapsedRealtime()
        val reply = try {
            qwen.ask(image, rules.prompt, HEDGE_AT_MS, DEADLINE_MS)
        } catch (_: IOException) {
            EventLog.add("Qwen unreachable")
            return Answer(Proto.KIND_NO_CONNECTION, Proto.TEXT_NO_CONNECTION, "Cloud didn't answer. Tap to retry.",
                prepMs = prepMs, modelMs = SystemClock.elapsedRealtime() - t1, calls = HEDGE_AT_MS.size + 1,
                cityId = city.id)
        } catch (e: QwenClient.ApiError) {
            EventLog.add("Qwen error ${e.code}")
            return Answer(Proto.KIND_ERROR, "Try again", "Model error ${e.code}",
                prepMs = prepMs, modelMs = SystemClock.elapsedRealtime() - t1, cityId = city.id)
        }
        return decide(city, reply, prepMs, SystemClock.elapsedRealtime() - t1)
    }

    /**
     * The same recognized item under [cityId]'s rule only. No model call.
     * Null when there is no model reply, or this city has no rule file.
     * Does not read any other city's rules.
     */
    fun forCity(previous: Answer, cityId: String): Answer? {
        val reply = previous.reply ?: return null
        val city = rules.cities[cityId] ?: return null
        if (previous.cityId == cityId) return previous
        return decide(city, reply, previous.prepMs, previous.modelMs)
    }

    private fun decide(city: Rules.City, reply: QwenClient.Reply, prepMs: Long, modelMs: Long): Answer {
        val id = parseId(reply.text, rules.ids)
        fun answer(kind: Int, line1: String, line2: String, bin: String?, page: String? = null) =
            Answer(kind, line1, line2, id, bin, reply.firstP, prepMs, modelMs, reply.calls, reply, city.id, page)

        val item = rules.item(id)
            ?: return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, "Couldn't tell what it is. Try closer.", null)
        val rule = city.rules[id]
        if (rule == null || rule.bin == "unknown") {
            return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, "${item.name}: no clear ${city.name} rule", rule?.bin, rule?.sourceUrl)
        }
        val p = reply.firstP
        val rival = reply.alternatives.firstOrNull { (token, pAlt) ->
            pAlt >= P_ALT && changesBin(city, token, id, rule.bin)
        }
        if ((p != null && p < P_MIN) || rival != null) {
            return answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, "Might be ${item.name}. Look closer, tap again.", rule.bin, rule.sourceUrl)
        }
        val label = city.binLabels[rule.bin] ?: rule.bin
        return answer(Proto.KIND_OK, label, rule.reason, rule.bin, rule.sourceUrl)
    }

    /**
     * Whether [token] is a different bin from the id already chosen.
     * The endpoint's runner-up is only a first-token prefix. Skip the chosen id's own
     * prefix group ("food" starts food_in_container and also food_can). Any other prefix
     * changes the bin only when every id it could start is a different bin: "food" also
     * starts food_in_container, same bin as a greasy pizza box, so food_can alone is not
     * a bin change. Empty prefix (a code fence, punctuation) is not an item.
     */
    private fun changesBin(city: Rules.City, token: String, selectedId: String, selectedBin: String): Boolean {
        val prefix = token.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        if (prefix.isEmpty() || selectedId.startsWith(prefix)) return false
        val bins = HashSet<String>()
        if ("unknown".startsWith(prefix)) bins += "unknown"
        for (cid in rules.ids) if (cid.startsWith(prefix)) bins += city.rules[cid]?.bin ?: "unknown"
        return bins.isNotEmpty() && bins.all { it != selectedBin }
    }

    companion object {
        /** Long side of the image sent to the model: ~400 fewer image tokens than 1024px, faster, same answers. */
        const val MODEL_SIDE = 768
        /**
         * Extra identical requests if nothing has answered by these times (typical answer 1.5–3 s).
         * The second request alone left ~1 in 250 calls unanswered at 7 s; a third, on a fresh
         * connection, catches most of those.
         */
        val HEDGE_AT_MS = longArrayOf(2000L, 4300L)
        /**
         * Give up and show "No connection". The glasses give up 8 s after sending, so this leaves
         * room for the trip back; a right answer at 6 s beats "No connection" at 5.5 s.
         */
        const val DEADLINE_MS = 7000L
        const val P_MIN = 0.5
        const val P_ALT = 0.25

        /** First catalog id in the reply; tolerates quotes, backticks or a trailing period. Same as tools/eval_probe.py. */
        fun parseId(text: String, ids: Set<String>): String {
            for (m in Regex("[a-z][a-z0-9_]*").findAll(text.lowercase())) if (m.value in ids) return m.value
            return "unknown"
        }
    }
}
