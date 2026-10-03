package com.baybin.phone

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * Qwen vision model through Model Studio's OpenAI-compatible chat/completions endpoint.
 * Blocking; call it off the main thread.
 *
 * Errors are split the way the glasses need them: [IOException] means we could not get an
 * answer in time (lens shows "No connection"); [ApiError] means the service refused.
 */
class QwenClient(
    private val baseUrl: String,
    private val apiKey: String,
    val model: String,
) {
    class ApiError(val code: Int, message: String) : Exception(message)

    /**
     * [text] is the model's reply. The endpoint returns a log-probability for the first output
     * token only: [firstToken] with probability [firstP], plus the runner-up first tokens in
     * [alternatives]. [calls] is how many requests went out (2 when the hedge fired).
     */
    class Reply(
        val text: String,
        val firstToken: String?,
        val firstP: Double?,
        val alternatives: List<Pair<String, Double>>,
        val calls: Int = 1,
    )

    // HTTP/1.1 on purpose: over one shared HTTP/2 connection a stalled connection took the
    // hedged second request down with it (and every call after it). With 1.1 each request in
    // flight has its own connection, and an idle one is still reused for the next scan.
    private val http = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(3, 5, TimeUnit.MINUTES))
        .build()

    /**
     * Opens two HTTPS connections to the endpoint (one for the request, one for a hedge) so the
     * next [ask] skips the TLS handshakes. Async, answers ignored; listing models costs nothing.
     */
    fun warmUp() {
        repeat(2) {
            http.newCall(Request.Builder()
                .url(baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }
    }

    /**
     * One image + one prompt. For every time in [hedgeAtMs] that passes without an answer, an
     * identical request goes out on another connection (about 1 call in 50 stalls for ~20 s at
     * random); the first answer wins and the others are cancelled. When every request in flight
     * has failed, the next one goes out right away. Throws [IOException] if nothing usable
     * arrives within [deadlineMs].
     */
    @Throws(IOException::class, ApiError::class)
    fun ask(jpeg: ByteArray, prompt: String, hedgeAtMs: LongArray, deadlineMs: Long, maxTokens: Int = 16): Reply {
        val body = requestBody(jpeg, prompt, maxTokens)
        val answers = LinkedBlockingQueue<Result<Reply>>()
        val calls = ArrayList<Call>(hedgeAtMs.size + 1)
        fun launch() {
            val call = http.newCall(
                Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
            )
            synchronized(calls) { calls += call }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    answers.add(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    answers.add(runCatching { response.use { parse(it) } })
                }
            })
        }

        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        launch()
        var launched = 1
        var failed = 0
        try {
            while (true) {
                val left = deadlineMs - elapsed()
                if (left <= 0) {
                    // Whatever connection(s) these calls sat on may be dead; don't hand them to the next scan.
                    http.connectionPool.evictAll()
                    throw IOException("no answer within $deadlineMs ms")
                }
                val nextHedge = hedgeAtMs.getOrNull(launched - 1)
                val wait = if (nextHedge != null) minOf(left, maxOf(0L, nextHedge - elapsed())) else left
                val answer = answers.poll(wait, TimeUnit.MILLISECONDS)
                if (answer == null) {
                    if (nextHedge != null && elapsed() >= nextHedge) {
                        launch()
                        launched++
                    }
                    continue
                }
                val reply = answer.getOrNull()
                if (reply != null) {
                    return Reply(reply.text, reply.firstToken, reply.firstP, reply.alternatives, launched)
                }
                failed++
                val e = answer.exceptionOrNull()!!
                // A refused request (4xx other than 429) won't go better the second time.
                if (e is ApiError && e.code in 400..499 && e.code != 429) throw e
                if (failed >= launched) {
                    if (launched <= hedgeAtMs.size) {
                        launch()
                        launched++
                        continue
                    }
                    throw e
                }
            }
        } finally {
            synchronized(calls) { calls.forEach { it.cancel() } }
        }
    }

    private fun requestBody(jpeg: ByteArray, prompt: String, maxTokens: Int): RequestBody {
        val image = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
            .put(JSONObject().put("type", "text").put("text", prompt))
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0)
            // Only the first output token's log-probability comes back, so the reply is the bare id.
            .put("logprobs", true)
            .put("top_logprobs", 5)
            .toString()
            .toRequestBody(JSON)
    }

    private fun parse(resp: Response): Reply {
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiError(resp.code, text.take(300))
        try {
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            val content = choice.getJSONObject("message").getString("content").trim()
            val first = choice.optJSONObject("logprobs")?.optJSONArray("content")?.optJSONObject(0)
            val token = first?.optString("token")
            val p = first?.let { exp(it.getDouble("logprob")) }
            val alternatives = ArrayList<Pair<String, Double>>()
            first?.optJSONArray("top_logprobs")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val t = o.getString("token")
                    if (t != token) alternatives += t to exp(o.getDouble("logprob"))
                }
            }
            return Reply(content, token, p, alternatives)
        } catch (e: JSONException) {
            throw ApiError(resp.code, "unexpected response: ${text.take(300)}")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
