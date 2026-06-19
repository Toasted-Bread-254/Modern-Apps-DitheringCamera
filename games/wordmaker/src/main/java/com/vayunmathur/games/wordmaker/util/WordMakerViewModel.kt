package com.vayunmathur.games.wordmaker.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WordMakerViewModel(application: Application) : AndroidViewModel(application) {

    val levelDataStore: LevelDataStore = LevelDataStore(application)

    private val dictionary = Dictionary()

    val currentLevel: StateFlow<Int> = levelDataStore.currentLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    val foundWords: StateFlow<Set<String>> = levelDataStore.foundWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val bonusWords: StateFlow<Set<String>> = levelDataStore.bonusWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val tapToSpell: StateFlow<Boolean> = levelDataStore.tapToSpell
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val revealedHints: StateFlow<Set<Pair<Int, Int>>> = levelDataStore.revealedHints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _hintCooldownEnd = MutableStateFlow(System.currentTimeMillis() + 30_000L)
    val hintCooldownEnd: StateFlow<Long> = _hintCooldownEnd.asStateFlow()

    private val _crosswordData = MutableStateFlow<CrosswordData?>(null)
    val crosswordData: StateFlow<CrosswordData?> = _crosswordData.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dictionary.init(getApplication())
        }
        viewModelScope.launch {
            currentLevel.collectLatest { level -> loadLevel(level) }
        }
    }

    private suspend fun loadLevel(level: Int) {
        val ctx = getApplication<Application>()
        val data = withContext(Dispatchers.IO) {
            CrosswordData.fromAsset(ctx, "levels/$level.txt")
        }
        _crosswordData.value = data
        _error.value = if (data == null) ctx.getString(R.string.error_parse_level) else null
    }

    fun isInDictionary(word: String): Boolean = word.lowercase() in dictionary

    fun getDefinition(word: String): List<String> = dictionary.getDefinition(word)

    fun saveLevel(level: Int) {
        viewModelScope.launch { levelDataStore.saveLevel(level) }
    }

    fun addFoundWord(word: String) {
        viewModelScope.launch { levelDataStore.addFoundWord(word) }
    }

    suspend fun addBonusWord(word: String): Int = levelDataStore.addBonusWord(word)

    fun setTapToSpell(enabled: Boolean) {
        viewModelScope.launch { levelDataStore.setTapToSpell(enabled) }
    }

    fun revealHint(crosswordData: CrosswordData, foundWords: Set<String>, revealedHints: Set<Pair<Int, Int>>) {
        val revealed = foundWords.flatMapTo(mutableSetOf()) { word ->
            crosswordData.letterPositions[word]?.flatten().orEmpty()
        } + revealedHints

        val allCells = crosswordData.letterPositions.values.flatMapTo(mutableSetOf()) { it.flatten() }
        val unrevealed = allCells - revealed
        if (unrevealed.isEmpty()) return

        val target = unrevealed.random()
        _hintCooldownEnd.value = System.currentTimeMillis() + 30_000L
        viewModelScope.launch {
            levelDataStore.addRevealedHint(target.first, target.second)
            val nowRevealed = revealed + target
            crosswordData.letterPositions.forEach { (word, occurrences) ->
                if (word !in foundWords && occurrences.any { nowRevealed.containsAll(it) }) {
                    levelDataStore.addFoundWord(word)
                }
            }
        }
    }
}
