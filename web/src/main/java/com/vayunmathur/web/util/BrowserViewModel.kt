package com.vayunmathur.web.util

import android.app.Application
import android.content.Context
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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import java.util.UUID

class BrowserViewModel(
    application: Application,
    private val historyDao: HistoryDao,
    val isIncognito: Boolean = false
) : AndroidViewModel(application) {

    val runtime: GeckoRuntime get() = GeckoRuntimeHolder.get(getApplication())

    private val _tabs = mutableStateListOf<Tab>()
    val tabs: List<Tab> = _tabs

    var activeTabIndex by mutableIntStateOf(0)
        private set

    val activeTab: Tab? get() = _tabs.getOrNull(activeTabIndex)

    private val sessions = mutableMapOf<String, GeckoSession>()

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

    var activeSearchQuery by mutableStateOf<String?>(null)
        private set

    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
        private set

    var isSearchLoading by mutableStateOf(false)
        private set

    var searchError by mutableStateOf<String?>(null)
        private set

    private val searchStack = mutableListOf<String>()
    private var searchJob: Job? = null

    val hasSearchHistory: Boolean get() = searchStack.isNotEmpty()

    var canGoForwardFromSearch by mutableStateOf(false)
        private set

    init {
        createTab(url = HOME_URL)
    }

    fun createTab(url: String = HOME_URL) {
        val tab = Tab(id = UUID.randomUUID().toString())
        _tabs.add(tab)
        activeTabIndex = _tabs.lastIndex

        val session = createSession(tab.id)
        session.loadUri(url)
        currentUrl = url
        currentTitle = "New Tab"
        canGoBack = false
        canGoForward = false
    }

    private fun createSession(tabId: String): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isIncognito)
            .build()

        val session = GeckoSession(settings)
        session.open(runtime)

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let {
                    if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                        currentUrl = it
                    }
                    updateTab(tabId) { copy(url = it) }
                }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val query = SearchEngine.extractSearchQuery(request.uri)
                if (query != null) {
                    search(query)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                    this@BrowserViewModel.canGoBack = canGoBack
                }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                    this@BrowserViewModel.canGoForward = canGoForward
                }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                    isLoading = true
                    progress = 0f
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                    isLoading = false
                    progress = 1f
                }
                val tab = _tabs.firstOrNull { it.id == tabId }
                if (success && tab != null && !isIncognito) {
                    val tabUrl = tab.url
                    val tabTitle = tab.title
                    if (tabUrl.isNotBlank() && tabUrl != "about:blank") {
                        viewModelScope.launch {
                            historyDao.insert(
                                HistoryEntry(
                                    url = tabUrl,
                                    title = tabTitle.ifBlank { tabUrl },
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }

            override fun onProgressChange(session: GeckoSession, progressValue: Int) {
                if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                    progress = progressValue / 100f
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let {
                    if (_tabs.getOrNull(activeTabIndex)?.id == tabId) {
                        currentTitle = it
                    }
                    updateTab(tabId) { copy(title = it) }
                }
            }
        }

        sessions[tabId] = session
        return session
    }

    fun getActiveSession(): GeckoSession? = activeTab?.let { sessions[it.id] }

    fun switchTab(index: Int) {
        if (index in _tabs.indices) {
            activeTabIndex = index
            val tab = _tabs[index]
            currentUrl = tab.url
            currentTitle = tab.title
            canGoBack = false
            canGoForward = false
        }
    }

    fun closeTab(tabId: String, finishActivity: () -> Unit) {
        val index = _tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return

        sessions[tabId]?.close()
        sessions.remove(tabId)
        _tabs.removeAt(index)

        if (_tabs.isEmpty()) {
            finishActivity()
        } else {
            activeTabIndex = index.coerceAtMost(_tabs.lastIndex)
            val tab = _tabs[activeTabIndex]
            currentUrl = tab.url
            currentTitle = tab.title
        }
    }

    fun loadUrl(url: String) {
        getActiveSession()?.loadUri(url)
    }

    fun search(query: String) {
        searchStack.add(query)
        activeSearchQuery = query
        searchResults = emptyList()
        searchError = null
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            isSearchLoading = true
            try {
                searchResults = SearchEngine.fetchSearchResults(query)
            } catch (e: Exception) {
                searchError = e.message ?: "Failed to fetch results"
            }
            isSearchLoading = false
        }
    }

    fun loadSearchResult(url: String) {
        activeSearchQuery = null
        canGoForwardFromSearch = false
        currentUrl = url
        val tabId = activeTab?.id ?: return
        sessions[tabId]?.close()
        val session = createSession(tabId)
        canGoBack = false
        canGoForward = false
        session.loadUri(url)
    }

    fun goBackFromSearch(): Boolean {
        searchStack.removeLastOrNull()
        val prev = searchStack.lastOrNull()
        if (prev != null) {
            search(prev)
            searchStack.removeLastOrNull() // search() re-adds it
            return true
        }
        activeSearchQuery = null
        searchResults = emptyList()
        return false
    }

    fun goBackToSearch(): Boolean {
        val prev = searchStack.lastOrNull() ?: return false
        search(prev)
        searchStack.removeLastOrNull() // search() re-adds it
        canGoForwardFromSearch = true
        return true
    }

    fun goForwardFromSearch() {
        activeSearchQuery = null
        canGoForwardFromSearch = false
    }

    fun goBack() { getActiveSession()?.goBack() }
    fun goForward() { getActiveSession()?.goForward() }
    fun reload() { getActiveSession()?.reload() }
    fun stop() { getActiveSession()?.stop() }

    fun clearHistory() {
        viewModelScope.launch { historyDao.deleteAll() }
    }

    fun clearBrowsingData() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        clearHistory()
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

    companion object {
        const val HOME_URL = "about:blank"
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
    private var instance: GeckoRuntime? = null

    @Synchronized
    fun get(context: Context): GeckoRuntime {
        return instance ?: GeckoRuntime.create(context.applicationContext).also {
            instance = it
        }
    }
}
