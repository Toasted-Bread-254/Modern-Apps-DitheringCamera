package com.vayunmathur.games.wordmaker.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WordMakerViewModel(application: Application) : AndroidViewModel(application) {

    val levelDataStore: LevelDataStore = LevelDataStore(application)

    private val dictionary = Dictionary()

    val currentLevel: StateFlow<Int> = levelDataStore.currentLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    private val casualFoundWords: StateFlow<Set<String>> = levelDataStore.foundWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val bonusWords: StateFlow<Set<String>> = levelDataStore.bonusWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val tapToSpell: StateFlow<Boolean> = levelDataStore.tapToSpell
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val revealedHints: StateFlow<Set<Pair<Int, Int>>> = levelDataStore.revealedHints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _hintCooldownEnd = MutableStateFlow(System.currentTimeMillis() + 30_000L)
    val hintCooldownEnd: StateFlow<Long> = _hintCooldownEnd.asStateFlow()

    // ---- Competitive mode state ----

    val gameMode: StateFlow<GameMode> = levelDataStore.gameMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameMode.CASUAL)

    val difficulty: StateFlow<Difficulty> = levelDataStore.difficulty
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Difficulty.MEDIUM)

    val competitiveScore: StateFlow<Int> = levelDataStore.competitiveScore
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @Volatile
    private var levelGenerator: CompetitiveLevelGenerator? = null

    private val _competitiveLevelNumber = MutableStateFlow(0)
    val competitiveLevelNumber: StateFlow<Int> = _competitiveLevelNumber.asStateFlow()

    private val _competitiveCrossword = MutableStateFlow<CrosswordData?>(null)
    private val _competitiveFoundWords = MutableStateFlow<Set<String>>(emptySet())

    /** Epoch millis at which the current competitive level's timer expires (0 when inactive). */
    private val _competitiveDeadline = MutableStateFlow(0L)
    val competitiveDeadline: StateFlow<Long> = _competitiveDeadline.asStateFlow()

    private val _casualCrossword = MutableStateFlow<CrosswordData?>(null)

    val crosswordData: StateFlow<CrosswordData?> =
        combine(gameMode, _casualCrossword, _competitiveCrossword) { mode, casual, competitive ->
            if (mode == GameMode.COMPETITIVE) competitive else casual
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val foundWords: StateFlow<Set<String>> =
        combine(gameMode, casualFoundWords, _competitiveFoundWords) { mode, casual, competitive ->
            if (mode == GameMode.COMPETITIVE) competitive else casual
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dictionary.init(getApplication())
        }
        viewModelScope.launch(Dispatchers.Default) {
            levelGenerator = CompetitiveLevelGenerator.fromAssets(getApplication())
        }
        viewModelScope.launch {
            currentLevel.collectLatest { level -> loadCasualLevel(level) }
        }
        // Restore a competitive board on launch if the player left the app in competitive mode.
        viewModelScope.launch {
            if (levelDataStore.gameMode.first() == GameMode.COMPETITIVE) {
                _competitiveLevelNumber.value = 0
                loadNextCompetitiveLevel()
            }
        }
    }

    private suspend fun loadCasualLevel(level: Int) {
        val ctx = getApplication<Application>()
        val data = withContext(Dispatchers.IO) {
            CrosswordData.fromAsset(ctx, "levels/$level.txt")
        }
        _casualCrossword.value = data
        if (gameMode.value != GameMode.COMPETITIVE) {
            _error.value = if (data == null) ctx.getString(R.string.error_parse_level) else null
        }
    }

    /** Generates a fresh competitive layout on the fly, resets its found words and restarts the timer. */
    fun loadNextCompetitiveLevel() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // Show the loading spinner while the next board is generated.
            _competitiveCrossword.value = null
            _competitiveFoundWords.value = emptySet()
            val data = withContext(Dispatchers.Default) {
                val generator = levelGenerator ?: CompetitiveLevelGenerator.fromAssets(ctx)
                    .also { levelGenerator = it }
                generator.generate()
            }
            _competitiveCrossword.value = data
            if (gameMode.value == GameMode.COMPETITIVE) {
                _error.value = if (data == null) ctx.getString(R.string.error_parse_level) else null
            }
            _competitiveLevelNumber.value = _competitiveLevelNumber.value + 1
            _competitiveDeadline.value =
                System.currentTimeMillis() + difficulty.value.timeLimitSeconds * 1000L
        }
    }

    fun setGameMode(mode: GameMode) {
        viewModelScope.launch {
            levelDataStore.setGameMode(mode)
            if (mode == GameMode.COMPETITIVE) {
                _competitiveLevelNumber.value = 0
                loadNextCompetitiveLevel()
            } else {
                _competitiveDeadline.value = 0L
            }
        }
    }

    fun setDifficulty(difficulty: Difficulty) {
        viewModelScope.launch {
            levelDataStore.setDifficulty(difficulty)
            // Restart with a fresh level so the new time limit applies cleanly.
            if (gameMode.value == GameMode.COMPETITIVE) {
                _competitiveLevelNumber.value = 0
                loadNextCompetitiveLevel()
            }
        }
    }

    /** Player solved the competitive level in time: award points and stop the timer. */
    fun onCompetitiveWin() {
        viewModelScope.launch {
            levelDataStore.addToCompetitiveScore(difficulty.value.winDelta)
            _competitiveDeadline.value = 0L
        }
    }

    /** Timer ran out: deduct points and stop the timer. */
    fun onCompetitiveTimeout() {
        viewModelScope.launch {
            levelDataStore.addToCompetitiveScore(-difficulty.value.lossDelta)
            _competitiveDeadline.value = 0L
        }
    }

    fun isInDictionary(word: String): Boolean = word.lowercase() in dictionary

    fun getDefinition(word: String): List<String> = dictionary.getDefinition(word)

    fun saveLevel(level: Int) {
        viewModelScope.launch { levelDataStore.saveLevel(level) }
    }

    fun addFoundWord(word: String) {
        if (gameMode.value == GameMode.COMPETITIVE) {
            _competitiveFoundWords.value = _competitiveFoundWords.value + word
        } else {
            viewModelScope.launch { levelDataStore.addFoundWord(word) }
        }
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
