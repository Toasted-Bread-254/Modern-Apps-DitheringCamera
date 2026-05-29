package com.vayunmathur.games.alchemist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.ui.HomeScreen
import com.vayunmathur.games.alchemist.ui.ItemDetailsScreen
import com.vayunmathur.games.alchemist.ui.UnlockNotification
import com.vayunmathur.games.alchemist.util.AlchemistViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: AlchemistViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ds = DataStoreUtils.getInstance(this)
        setContent {
            DynamicTheme {
                Navigation(ds, viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data class ItemDetails(val item: Int): Route
    @Serializable
    data object GameCenter: Route
}

@Composable
fun Navigation(ds: DataStoreUtils, viewModel: AlchemistViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val achievementsManager = rememberAchievementsManager()
    val newAchievement = achievementsManager?.newAchievement?.collectAsState()?.value

    var showingUnlock by remember { mutableStateOf(false) }
    var currentUnlocks by remember { mutableStateOf(emptyList<AlchemyItem>()) }

    LaunchedEffect(achievementsManager) {
        if (achievementsManager != null) {
            launch { achievementsManager.checkExistingAchievements() }
            viewModel.bindAchievements(achievementsManager)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.newUnlocksEvent.collectLatest { items ->
            currentUnlocks = items
            showingUnlock = true
            delay(3000)
            showingUnlock = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> {
                HomeScreen(backStack, viewModel, onOpenGameCenter = { backStack.add(Route.GameCenter) })
            }
            entry<Route.ItemDetails> {
                ItemDetailsScreen(backStack, ds, viewModel, it.item)
            }
            entry<Route.GameCenter> {
                achievementsManager?.let {
                    com.vayunmathur.library.ui.GameCenterScreen(
                        backupAgent = com.vayunmathur.games.alchemist.util.AppBackupAgent(),
                        manager = it,
                        onBack = { backStack.pop() }
                    )
                }
            }
        }

        newAchievement?.let { ach ->
            com.vayunmathur.library.ui.AchievementNotification(ach) {
                achievementsManager?.dismissNotification()
            }
        }

        UnlockNotification(
            unlock = currentUnlocks,
            showing = showingUnlock
        )
    }
}

@Composable
fun rememberAchievementsManager(): AchievementsManager? {
    val context = LocalContext.current
    // Load + parse the JSON catalog off the main thread; first composition
    // sees null and the manager appears once IO completes.
    val state = produceState<AchievementsManager?>(initialValue = null, context) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
            com.vayunmathur.games.alchemist.util.AlchemistAchievementsManager(context, json)
        }
    }
    return state.value
}
