package com.paul.voicekeys

data class PracticeEntry(val at: String, val session: Int, val decoder: String,
    val audioMs: Long, val type: String, val text: String, val outcome: String,
    val score: Float? = null, val threshold: Float? = null, val lookbackMs: Long? = null,
    val distance: Float? = null, val distanceLimit: Float? = null,
    val competingDistance: Float? = null, val backgroundDistance: Float? = null)

fun practiceCsv(entries: List<PracticeEntry>): String = buildString {
    append("time,session,decoder,audio_ms,type,text,outcome,acoustic_score,selectivity,decoder_lookback_ms,sound_distance,distance_limit,competing_distance,background_distance\n")
    entries.forEach { entry ->
        val cells = listOf(entry.at, entry.session.toString(), entry.decoder, entry.audioMs.toString(),
            entry.type, entry.text, entry.outcome, entry.score?.toString().orEmpty(),
            entry.threshold?.toString().orEmpty(), entry.lookbackMs?.toString().orEmpty(),
            entry.distance?.toString().orEmpty(), entry.distanceLimit?.toString().orEmpty(),
            entry.competingDistance?.toString().orEmpty(), entry.backgroundDistance?.toString().orEmpty())
        append(cells.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append('\n')
    }
}
