package com.vayunmathur.messages.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Poll state stored inside a poll message's
 * [com.vayunmathur.messages.data.Message.serviceData] JSON (key "poll"), so no Room schema
 * migration is needed. Shape:
 * ```
 * { "poll": { "question": "...", "options": ["A","B"], "selectable": 1,
 *             "votes": { "<voterId>": ["A"], ... } } }
 * ```
 * Counts for the UI are derived from the per-voter `votes` map so a voter changing their choice
 * replaces (not accumulates) their vote.
 */
private const val POLL_KEY = "poll"

/** A parsed poll for rendering: options with vote counts, plus the local user's current picks. */
data class PollView(
    val question: String,
    val options: List<String>,
    val selectable: Int,
    val counts: Map<String, Int>,
    val myVotes: Set<String>,
    val totalVoters: Int,
)

/** Build the initial poll serviceData JSON (no votes yet), preserving any unrelated keys. */
fun buildPollServiceData(serviceData: String?, question: String, options: List<String>, selectable: Int): String {
    val obj = runCatching {
        if (serviceData.isNullOrBlank()) JSONObject() else JSONObject(serviceData)
    }.getOrDefault(JSONObject())
    val poll = obj.optJSONObject(POLL_KEY) ?: JSONObject()
    poll.put("question", question)
    poll.put("options", JSONArray(options))
    poll.put("selectable", selectable)
    if (!poll.has("votes")) poll.put("votes", JSONObject())
    obj.put(POLL_KEY, poll)
    return obj.toString()
}

/** Record [voterId]'s current selection ([optionNames]; empty clears it). Returns updated JSON. */
fun applyPollVote(serviceData: String?, voterId: String, optionNames: List<String>): String {
    val obj = runCatching {
        if (serviceData.isNullOrBlank()) JSONObject() else JSONObject(serviceData)
    }.getOrDefault(JSONObject())
    val poll = obj.optJSONObject(POLL_KEY) ?: JSONObject().also { obj.put(POLL_KEY, it) }
    val votes = poll.optJSONObject("votes") ?: JSONObject().also { poll.put("votes", it) }
    if (optionNames.isEmpty()) votes.remove(voterId) else votes.put(voterId, JSONArray(optionNames))
    return obj.toString()
}

/** Parse poll state for rendering, or null if [serviceData] has no poll. */
fun pollFromServiceData(serviceData: String?): PollView? {
    if (serviceData.isNullOrBlank()) return null
    val poll = runCatching { JSONObject(serviceData).optJSONObject(POLL_KEY) }.getOrNull() ?: return null
    val question = poll.optString("question")
    val optionsArr = poll.optJSONArray("options") ?: return null
    val options = (0 until optionsArr.length()).map { optionsArr.optString(it) }
    val selectable = poll.optInt("selectable", 1)
    val votes = poll.optJSONObject("votes") ?: JSONObject()
    val counts = LinkedHashMap<String, Int>()
    options.forEach { counts[it] = 0 }
    val myVotes = mutableSetOf<String>()
    for (voter in votes.keys()) {
        val picks = votes.optJSONArray(voter) ?: continue
        for (i in 0 until picks.length()) {
            val opt = picks.optString(i)
            counts[opt] = (counts[opt] ?: 0) + 1
            if (voter == "self") myVotes.add(opt)
        }
    }
    return PollView(question, options, selectable, counts, myVotes, votes.length())
}
