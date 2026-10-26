package com.baybin.phone

import android.content.Context
import android.os.SystemClock
import com.baybin.protocol.Proto
import java.io.IOException
import java.security.MessageDigest

/**
 * Photo in, two lens lines out. Blocking; runs on a worker thread.
 *
 * The glasses path and the gallery test mode both call [run], so they exercise exactly the
 * same code. The model names the item and its visible evidence; the bin comes from
 * the chosen city's official-source rule, never from the model.
 *
 * "Not sure — check city guide" when:
 *  - the photo is unusably dark/bright or verification sees unrelated objects;
 *  - contents/labels conflict with the item, or required material evidence is missing;
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
    private val decision = Decision(rules)
    private data class Cached(val reply: QwenClient.Reply, val at: Long)
    // Results only, no photos and no files. A new key/model creates a fresh Pipeline.
    private val recent = LinkedHashMap<String, Cached>()

    data class Answer(
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
         * (offline, no key, error). Reused with its evidence when the city changes.
         */
        val reply: QwenClient.Reply? = null,
        /** City whose rule file chose the bin. Null only if no city was asked. */
        val cityId: String? = null,
        /** Official page for this ruling, when the rule cites one. Not shown as a raw URL. */
        val sourceUrl: String? = null,
        val help: Recognition.Help? = null,
    )

    /** Opens the cloud connection ahead of time (async, result ignored). */
    fun warmUp() {
        if (Net.online(context)) qwen?.warmUp()
    }

    fun run(jpeg: ByteArray, rotation: Int, cityId: String, isCurrent: () -> Boolean = { true }): Answer {
        // Missing id is not another city. Cupertino's file must not answer for it.
        val city = rules.cities[cityId]
            ?: return Answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, "No rules for this city", cityId = cityId)
        if (!Net.online(context)) {
            return Answer(Proto.KIND_NO_CONNECTION, Proto.TEXT_NO_CONNECTION, "Phone is offline", cityId = city.id)
        }
        val t0 = SystemClock.elapsedRealtime()
        val upright = Upright.prepare(jpeg, rotation, 1024, quality = 80)
        PhotoQuality.help(upright)?.let { help ->
            return Answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, help.text,
                prepMs = SystemClock.elapsedRealtime() - t0, cityId = city.id)
        }
        val image = Upright.prepare(upright, 0, MODEL_SIDE, quality = 80)
        val prepMs = SystemClock.elapsedRealtime() - t0

        if (qwen == null) {
            return Answer(Proto.KIND_INFO, "No API key", "Type your own key in the phone app.",
                prepMs = prepMs, cityId = city.id)
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(upright).joinToString("") { "%02x".format(it) }
        recent[fingerprint]?.takeIf { SystemClock.elapsedRealtime() - it.at < 180_000L }?.let { cached ->
            EventLog.add("identical photo: reusing checked evidence from memory")
            return decide(city, cached.reply, prepMs, 0).copy(calls = 0)
        }
        val t1 = SystemClock.elapsedRealtime()
        // One response carries the item and its visible evidence. The first token
        // belongs to the bare id, so probability remains meaningful. No second wait.
        val reply = try {
            qwen.ask(image, rules.inspectionPrompt, HEDGE_AT_MS, DEADLINE_MS,
                maxTokens = 160, isCurrent = isCurrent)
        } catch (_: IOException) {
            EventLog.add("Qwen unreachable")
            return Answer(Proto.KIND_NO_CONNECTION, Proto.TEXT_NO_CONNECTION, "Cloud didn't answer. Tap to retry.",
                prepMs = prepMs, modelMs = SystemClock.elapsedRealtime() - t1, calls = HEDGE_AT_MS.size + 1,
                cityId = city.id)
        } catch (e: QwenClient.ApiError) {
            EventLog.add("Qwen error ${e.code}")
            val help = when (e.code) {
                401, 403 -> "Check the API key in phone Settings."
                404 -> "Recognition service unavailable. Check the model configuration."
                429 -> "Recognition service busy. Wait a moment and retry."
                else -> "Recognition service failed. Please retry."
            }
            return Answer(Proto.KIND_ERROR, "Try again", help,
                prepMs = prepMs, modelMs = SystemClock.elapsedRealtime() - t1, cityId = city.id)
        }
        val checked = QwenClient.Reply(parseId(reply.text.lineSequence().firstOrNull().orEmpty(), rules.ids),
            reply.firstToken, reply.firstP, reply.alternatives, reply.calls,
            Recognition.parseInspection(reply.text, rules.ids))
        val answer = decide(city, checked, prepMs, SystemClock.elapsedRealtime() - t1)
        if (answer.kind == Proto.KIND_OK && isCurrent()) {
            recent[fingerprint] = Cached(checked, SystemClock.elapsedRealtime())
            while (recent.size > 16) recent.remove(recent.keys.first())
        }
        return answer
    }

    /**
     * The same recognized item under [cityId]'s rule only. No model call.
     * Photo/key/connection guidance follows the selected city without a new call.
     * Null only when this city has no rule file.
     * Does not read any other city's rules.
     */
    fun forCity(previous: Answer, cityId: String): Answer? {
        val city = rules.cities[cityId] ?: return null
        val reply = previous.reply ?: return previous.copy(cityId = city.id)
        return decide(city, reply, previous.prepMs, previous.modelMs)
    }

    fun lookup(itemId: String, cityId: String): Answer = decision.lookup(itemId, cityId)

    fun supplement(previous: Answer, value: String): Answer? = decision.supplement(previous, value)

    private fun decide(city: Rules.City, reply: QwenClient.Reply, prepMs: Long, modelMs: Long): Answer {
        return decision.decide(city, reply, prepMs, modelMs)
    }

    /**
     * Whether [token] is a different bin from the id already chosen.
     * The endpoint's runner-up is only a first-token prefix. Skip the chosen id's own
     * prefix group ("food" starts food_in_container and also food_can). Any other prefix
     * changes the bin only when every id it could start is a different bin: "food" also
     * starts food_in_container, same bin as a greasy pizza box, so food_can alone is not
     * a bin change. Empty prefix (a code fence, punctuation) is not an item.
     */
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

        /** A single id only. Prose, multiple ids, and mixed unknown/id answers fail closed. */
        fun parseId(text: String, ids: Set<String>): String = Recognition.parseId(text, ids)
    }
}
