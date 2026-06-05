package com.vayunmathur.web

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.web.data.BrowserDatabase
import com.vayunmathur.web.ui.BrowserPage
import com.vayunmathur.web.ui.HistoryPage
import com.vayunmathur.web.ui.TabsPage
import com.vayunmathur.web.util.BrowserViewModel
import com.vayunmathur.web.util.BrowserViewModelFactory
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val db by lazy { buildDatabase<BrowserDatabase>() }

    private val isIncognito: Boolean
        get() = intent?.getBooleanExtra(EXTRA_INCOGNITO, false) == true

    private val viewModel: BrowserViewModel by viewModels {
        BrowserViewModelFactory(application, db, isIncognito)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.data?.toString()
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            viewModel.createTab(url = url)
        }
    }

    companion object {
        const val EXTRA_INCOGNITO = "incognito"

        fun launchNewWindow(context: Context, incognito: Boolean = false) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                putExtra(EXTRA_INCOGNITO, incognito)
            }
            context.startActivity(intent)
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Browser : Route

    @Serializable
    data object Tabs : Route

    @Serializable
    data object History : Route
}

@Composable
fun Navigation(viewModel: BrowserViewModel) {
    val backStack = rememberNavBackStack<Route>(listOf(Route.Browser))
    MainNavigation(backStack) {
        entry<Route.Browser> {
            BrowserPage(viewModel, backStack)
        }
        entry<Route.Tabs> {
            TabsPage(viewModel, backStack)
        }
        entry<Route.History> {
            HistoryPage(viewModel, backStack)
        }
    }
}
