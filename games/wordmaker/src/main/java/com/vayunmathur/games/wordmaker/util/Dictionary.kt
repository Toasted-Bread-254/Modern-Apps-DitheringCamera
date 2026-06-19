package com.vayunmathur.games.wordmaker.util
import android.content.Context

class Dictionary {
    @Volatile private var definitions: Map<String, List<String>> = emptyMap()
    @Volatile private var wordSet: Set<String> = emptySet()

    fun init(context: Context) {
        val newDefs = mutableMapOf<String, MutableList<String>>()
        context.assets.open("dictionary.csv").bufferedReader().lines().forEach { line ->
            val parts = line.split(",", limit = 4)
            if (parts.size < 4) return@forEach
            val w = parts[0].lowercase()
            newDefs.getOrPut(w) { mutableListOf() }.add(parts[3].trim('"'))
        }
        definitions = newDefs.mapValues { it.value.toList() }
        wordSet = newDefs.keys.toHashSet()
    }

    operator fun contains(word: String): Boolean = word in wordSet

    fun getDefinition(word: String): List<String> = definitions[word.lowercase()] ?: emptyList()
}
