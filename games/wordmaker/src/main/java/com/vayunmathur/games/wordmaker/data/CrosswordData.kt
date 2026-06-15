package com.vayunmathur.games.wordmaker.data
import android.content.Context

data class CrosswordData(
    val solutionWords: Set<String>,
    val lettersInChooser: List<Char>,
    val gridStructure: List<String>,
    val letterPositions: Map<String, List<List<Pair<Int, Int>>>>) {

    fun getWordAt(row: Int, col: Int, foundWords: Set<String>): String? {
        val cell = Pair(row, col)
        // Prioritize vertical words
        for ((word, occurrences) in letterPositions) {
            if (word !in foundWords) continue
            for (positions in occurrences) {
                if (cell in positions) {
                    val isVertical = positions.first().second == positions.last().second
                    if (isVertical) return word
                }
            }
        }
        // If no vertical word, check for horizontal
        for ((word, occurrences) in letterPositions) {
            if (word !in foundWords) continue
            for (positions in occurrences) {
                if (cell in positions) return word
            }
        }
        return null
    }

    fun winsWith(foundWords: Set<String>): Boolean {
        val unfoundWords = solutionWords - foundWords

        val foundLetterPositions = foundWords
            .mapNotNull { letterPositions[it] }
            .flatten()
            .flatten()
            .toSet()

        val unfoundLetterPositions = unfoundWords
            .mapNotNull { letterPositions[it] }
            .flatten()
            .flatten()

        return foundLetterPositions.containsAll(unfoundLetterPositions)
    }

    companion object {
        fun fromAsset(context: Context, fileName: String): CrosswordData? {
            return try {
                val content = context.assets.open(fileName).bufferedReader().use { it.readText() }
                fromString(content)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun fromString(content: String): CrosswordData? {
            return try {
                val lines = content.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                if (lines.isEmpty()) return null
                val maxLength = lines.maxOf { it.length }
                val grid = lines.map {
                    val padded = it.padEnd(maxLength)
                    if (padded.isBlank()) ".".repeat(maxLength) else padded.replace(' ', '.')
                }
                val (words, positions) = extractWordsAndPositions(grid)
                val chooserLetters = words
                    .map { word ->
                        word.groupingBy { it }.eachCount() // Count chars in each word
                    }
                    .fold(mutableMapOf<Char, Int>()) { acc, wordMap ->
                        wordMap.forEach { (char, count) ->
                            acc[char] = maxOf(acc.getOrDefault(char, 0), count)
                        }
                        acc
                    }
                    .flatMap { (char, count) ->
                        List(count) { char } // Create 'count' copies of 'char'
                    }
                    .sorted()

                CrosswordData(
                    solutionWords = words.toSet(),
                    lettersInChooser = chooserLetters,
                    gridStructure = grid,
                    letterPositions = positions
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        private fun extractWordsAndPositions(grid: List<String>): Pair<List<String>, Map<String, List<List<Pair<Int, Int>>>>> {
            val words = mutableListOf<String>()
            val positions = mutableMapOf<String, MutableList<List<Pair<Int, Int>>>>()
            val numRows = grid.size
            if (numRows == 0) return Pair(emptyList(), emptyMap())
            val numCols = grid[0].length

            // Horizontal words
            for (r in 0 until numRows) {
                var currentWord = ""
                var startCol = -1
                for (c in 0 until numCols) {
                    val char = grid[r][c]
                    if (char != '.') {
                        if (currentWord.isEmpty()) startCol = c
                        currentWord += char
                    } else {
                        if (currentWord.length > 1) {
                            words.add(currentWord)
                            positions.getOrPut(currentWord) { mutableListOf() }.add(List(currentWord.length) { i -> Pair(r, startCol + i) })
                        }
                        currentWord = ""
                    }
                }
                if (currentWord.length > 1) {
                    words.add(currentWord)
                    positions.getOrPut(currentWord) { mutableListOf() }.add(List(currentWord.length) { i -> Pair(r, startCol + i) })
                }
            }

            // Vertical words
            for (c in 0 until numCols) {
                var currentWord = ""
                var startRow = -1
                for (r in 0 until numRows) {
                    val char = grid[r][c]
                    if (char != '.') {
                        if (currentWord.isEmpty()) startRow = r
                        currentWord += char
                    } else {
                        if (currentWord.length > 1) {
                            words.add(currentWord)
                            positions.getOrPut(currentWord) { mutableListOf() }.add(List(currentWord.length) { i -> Pair(startRow + i, c) })
                        }
                        currentWord = ""
                    }
                }
                if (currentWord.length > 1) {
                    words.add(currentWord)
                    positions.getOrPut(currentWord) { mutableListOf() }.add(List(currentWord.length) { i -> Pair(startRow + i, c) })
                }
            }

            return Pair(words, positions)
        }
    }
}