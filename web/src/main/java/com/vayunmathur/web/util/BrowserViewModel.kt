package com.vayunmathur.web.util

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.web.data.BrowserDatabase
import com.vayunmathur.web.data.HistoryDao
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.SearchResult
import com.vayunmathur.web.data.Tab
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import java.util.UUID

sealed interface NavEntry {
    data object NewTab : NavEntry
    data class Search(
        val query: String,
        val results: List<SearchResult> = emptyList(),
        val error: String? = null,
        val loading: Boolean = false
    ) : NavEntry
    data class WebPage(val url: String) : NavEntry
    data class Blocked(val url: String) : NavEntry
}

class BrowserViewModel(
    application: Application,
    private val historyDao: HistoryDao,
    val isIncognito: Boolean = false
) : AndroidViewModel(application) {

    val runtime: GeckoRuntime get() = GeckoRuntimeHolder.get(getApplication())

    private val blockedDomains: Set<String> = loadBlockedDomains(application)

    private val _tabs = mutableStateListOf<Tab>()
    val tabs: List<Tab> = _tabs

    var activeTabIndex by mutableIntStateOf(0)
        private set

    val activeTab: Tab? get() = _tabs.getOrNull(activeTabIndex)

    private val sessions = mutableMapOf<String, GeckoSession>()

    private val tabHistory = mutableMapOf<String, MutableList<NavEntry>>()
    private val tabCursor = mutableMapOf<String, Int>()

    var currentEntry by mutableStateOf<NavEntry>(NavEntry.NewTab)
        private set

    var currentUrl by mutableStateOf("")
        private set

    var currentTitle by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    private var searchJob: Job? = null

    init {
        createTab(url = HOME_URL)
    }

    private fun currentTabId(): String? = activeTab?.id

    private fun isActiveTab(tabId: String) = activeTab?.id == tabId

    private fun getHistory(tabId: String): MutableList<NavEntry> =
        tabHistory.getOrPut(tabId) { mutableListOf() }

    private fun getCursor(tabId: String): Int = tabCursor[tabId] ?: -1

    private fun setCursor(tabId: String, value: Int) {
        tabCursor[tabId] = value
    }

    private fun navigate(entry: NavEntry) {
        val tabId = currentTabId() ?: return
        val history = getHistory(tabId)
        val cursor = getCursor(tabId)

        if (cursor < history.lastIndex) {
            history.subList(cursor + 1, history.size).clear()
        }

        history.add(entry)
        setCursor(tabId, history.lastIndex)
        applyEntry(tabId, entry)
        updateNavState(tabId)
    }

    private fun applyEntry(tabId: String, entry: NavEntry, restore: Boolean = false) {
        currentEntry = entry
        when (entry) {
            is NavEntry.NewTab -> {
                currentUrl = ""
                currentTitle = "New Tab"
            }
            is NavEntry.Search -> {
                currentUrl = ""
                currentTitle = entry.query
                if (!restore || (entry.results.isEmpty() && entry.error == null && !entry.loading)) {
                    performSearch(tabId, entry)
                }
            }
            is NavEntry.WebPage -> {
                currentUrl = entry.url
                sessions[tabId]?.close()
                createSession(tabId).loadUri(entry.url)
            }
            is NavEntry.Blocked -> {
                currentUrl = entry.url
                currentTitle = "Blocked"
            }
        }
    }

    private fun replaceCurrentEntry(tabId: String, entry: NavEntry) {
        val history = getHistory(tabId)
        val cursor = getCursor(tabId)
        if (cursor in history.indices) {
            history[cursor] = entry
        }
        if (currentTabId() == tabId) {
            currentEntry = entry
        }
    }

    private fun performSearch(tabId: String, entry: NavEntry.Search) {
        searchJob?.cancel()
        replaceCurrentEntry(tabId, entry.copy(loading = true, error = null, results = emptyList()))
        searchJob = viewModelScope.launch {
            val result = try {
                entry.copy(results = SearchEngine.fetchSearchResults(entry.query), loading = false)
            } catch (e: Exception) {
                entry.copy(error = e.message ?: "Failed to fetch results", loading = false)
            }
            replaceCurrentEntry(tabId, result)
        }
    }

    private fun updateNavState(tabId: String) {
        val cursor = getCursor(tabId)
        canGoBack = cursor > 0
        canGoForward = cursor < getHistory(tabId).lastIndex
    }

    private fun activateTab(tab: Tab) {
        currentUrl = tab.url
        currentTitle = tab.title
        currentEntry = getHistory(tab.id).getOrNull(getCursor(tab.id)) ?: NavEntry.NewTab
        updateNavState(tab.id)
    }

    fun createTab(url: String = HOME_URL) {
        val tab = Tab(id = UUID.randomUUID().toString())
        _tabs.add(tab)
        activeTabIndex = _tabs.lastIndex
        createSession(tab.id)

        if (url == HOME_URL) {
            getHistory(tab.id).add(NavEntry.NewTab)
            setCursor(tab.id, 0)
            currentEntry = NavEntry.NewTab
            currentUrl = ""
            currentTitle = "New Tab"
            canGoBack = false
            canGoForward = false
        } else {
            navigate(NavEntry.WebPage(url))
        }
    }

    private fun createSession(tabId: String): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isIncognito)
            .build()

        return GeckoSession(settings).also { session ->
            session.open(runtime)

            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    url?.let {
                        if (it != "about:blank" && isActiveTab(tabId)) currentUrl = it
                        updateTab(tabId) { copy(url = it) }
                    }
                }

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    Log.d("ContentBlocker", "onLoadRequest: ${request.uri}")
                    val query = SearchEngine.extractSearchQuery(request.uri)
                    if (query != null) {
                        search(query)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    if (SearchEngine.isSearchEngineUrl(request.uri)) {
                        session.loadUri(HOME_URL)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    if (isDomainBlocked(request.uri)) {
                        Log.d("ContentBlocker", "Blocked: ${request.uri}")
                        val tabIdNow = currentTabId()
                        if (tabIdNow != null) {
                            val history = getHistory(tabIdNow)
                            val cursor = getCursor(tabIdNow)
                            if (cursor in history.indices && history[cursor] is NavEntry.WebPage) {
                                val blocked = NavEntry.Blocked(request.uri)
                                history[cursor] = blocked
                                currentEntry = blocked
                                currentUrl = request.uri
                                currentTitle = "Blocked"
                            } else {
                                navigate(NavEntry.Blocked(request.uri))
                            }
                        }
                        session.loadUri(HOME_URL)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {}
                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {}
            }

            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (isActiveTab(tabId)) {
                        isLoading = true
                        progress = 0f
                    }
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (isActiveTab(tabId)) {
                        isLoading = false
                        progress = 1f
                    }
                    if (success && !isIncognito) {
                        val tab = _tabs.firstOrNull { it.id == tabId } ?: return
                        if (tab.url.isNotBlank() && tab.url != "about:blank") {
                            viewModelScope.launch {
                                historyDao.insert(
                                    HistoryEntry(
                                        url = tab.url,
                                        title = tab.title.ifBlank { tab.url },
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }

                override fun onProgressChange(session: GeckoSession, progressValue: Int) {
                    if (isActiveTab(tabId)) {
                        progress = progressValue / 100f
                    }
                }
            }

            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    title?.let {
                        if (isActiveTab(tabId)) currentTitle = it
                        updateTab(tabId) { copy(title = it) }
                    }
                }
            }

            sessions[tabId] = session
        }
    }

    fun getActiveSession(): GeckoSession? = activeTab?.let { sessions[it.id] }

    fun switchTab(index: Int) {
        if (index in _tabs.indices) {
            activeTabIndex = index
            activateTab(_tabs[index])
        }
    }

    fun closeTab(tabId: String, finishActivity: () -> Unit) {
        val index = _tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return

        sessions.remove(tabId)?.close()
        tabHistory.remove(tabId)
        tabCursor.remove(tabId)
        _tabs.removeAt(index)

        if (_tabs.isEmpty()) {
            finishActivity()
        } else {
            activeTabIndex = index.coerceAtMost(_tabs.lastIndex)
            activateTab(_tabs[activeTabIndex])
        }
    }

    fun loadUrl(url: String) {
        navigate(NavEntry.WebPage(url))
    }

    fun search(query: String) {
        navigate(NavEntry.Search(query))
    }

    fun goBack() {
        val tabId = currentTabId() ?: return
        val cursor = getCursor(tabId)
        if (cursor <= 0) return
        setCursor(tabId, cursor - 1)
        applyEntry(tabId, getHistory(tabId)[cursor - 1], restore = true)
        updateNavState(tabId)
    }

    fun goForward() {
        val tabId = currentTabId() ?: return
        val history = getHistory(tabId)
        val cursor = getCursor(tabId)
        if (cursor >= history.lastIndex) return
        setCursor(tabId, cursor + 1)
        applyEntry(tabId, history[cursor + 1], restore = true)
        updateNavState(tabId)
    }

    fun clearBrowsingData() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        viewModelScope.launch { historyDao.deleteAll() }
    }

    val history = historyDao.getAll()

    private fun updateTab(tabId: String, update: Tab.() -> Tab) {
        val index = _tabs.indexOfFirst { it.id == tabId }
        if (index >= 0) {
            _tabs[index] = _tabs[index].update()
        }
    }

    override fun onCleared() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun isDomainBlocked(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        Log.d("ContentBlocker", "Checking host: $host, blocklist size: ${blockedDomains.size}")
        var domain = host
        while (domain.contains('.')) {
            if (domain in blockedDomains) return true
            domain = domain.substringAfter('.')
        }
        return false
    }

    companion object {
        const val HOME_URL = "about:blank"

        private fun loadBlockedDomains(application: Application): Set<String> = try {
            application.assets.open("extensions/content_blocker/domains.js").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    line.trim()
                        .takeIf { it.startsWith("\"") && it.contains("\",") }
                        ?.removePrefix("\"")?.substringBefore("\"")
                        ?.lowercase()?.takeIf { it.isNotEmpty() }
                }.toSet()
            }.also { Log.d("ContentBlocker", "Loaded ${it.size} blocked domains") }
        } catch (e: Exception) {
            Log.e("ContentBlocker", "Failed to load blocked domains", e)
            emptySet()
        }
    }
}

class BrowserViewModelFactory(
    private val application: Application,
    private val db: BrowserDatabase,
    private val isIncognito: Boolean = false
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BrowserViewModel(application, db.historyDao(), isIncognito) as T
    }
}

object GeckoRuntimeHolder {
    private const val TAG = "ContentBlocker"
    private var instance: GeckoRuntime? = null

    @Synchronized
    fun get(context: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath("resource://android/assets/geckoview-config.yaml")
            .build()
        return instance ?: GeckoRuntime.create(context.applicationContext, settings).also { runtime ->
            instance = runtime
            runtime.webExtensionController.installBuiltIn(
                "resource://android/assets/extensions/content_blocker/"
            ).then({ ext ->
                Log.i(TAG, "Extension installed: ${ext?.id}")
                GeckoResult.fromValue(ext)
            }, { e ->
                Log.e(TAG, "Extension install failed", e)
                GeckoResult.fromValue(null)
            })
        }
    }
}
