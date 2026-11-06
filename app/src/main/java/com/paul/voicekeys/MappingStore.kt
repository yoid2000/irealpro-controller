package com.paul.voicekeys

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("voicekeys", Context.MODE_PRIVATE)
    var recognitionMode: RecognitionMode
        get() = runCatching { RecognitionMode.valueOf(prefs.getString("recognitionMode", null) ?: "TRAINED_SOUNDS") }
            .getOrDefault(RecognitionMode.TRAINED_SOUNDS)
        set(value) { prefs.edit().putString("recognitionMode", value.name).apply() }
    var commandBias: Boolean
        get() = prefs.getBoolean("commandBias", true)
        set(value) { prefs.edit().putBoolean("commandBias", value).apply() }
    var useBleKeyboard: Boolean
        get() = prefs.getBoolean("bleKeyboard", false)
        set(value) { prefs.edit().putBoolean("bleKeyboard", value).apply() }
    var hostAddress: String
        get() = prefs.getString("host", "") ?: ""
        set(value) { prefs.edit().putString("host", value).apply() }
    var headsetName: String
        get() = prefs.getString("headset", "") ?: ""
        set(value) { prefs.edit().putString("headset", value).apply() }

    fun load(): List<CommandMapping> {
        val json = prefs.getString("mappings", null) ?: return defaultMappings()
        return runCatching {
            val array = JSONArray(json)
            require(array.length() <= 9)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                CommandMapping(item.getString("id"), validateWord(item.getString("word")),
                    OutputKey.valueOf(item.getString("key")),
                    item.optDouble("threshold", 0.2).toFloat().coerceIn(0.15f, 0.9f))
            }.also { items -> require(items.map { it.word }.distinct().size == items.size) }
        }.getOrElse { defaultMappings() }
    }

    fun save(mappings: List<CommandMapping>) {
        require(mappings.size <= 9)
        val array = JSONArray()
        mappings.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("word", item.word)
                .put("key", item.key.name).put("threshold", item.threshold.toDouble()))
        }
        prefs.edit().putString("mappings", array.toString()).apply()
    }
}
