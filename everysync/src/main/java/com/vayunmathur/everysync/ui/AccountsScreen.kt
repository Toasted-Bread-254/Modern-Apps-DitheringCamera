package com.vayunmathur.everysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.NavBackStack
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    val permissions = arrayOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )
    PermissionsChecker(permissions, stringResource(R.string.need_permissions)) {
        val accounts by viewModel.accounts.collectAsStateWithLifecycle()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.accounts_title)) },
                    actions = {
                        IconButton(onClick = { backStack.add(Route.Settings) }) {
                            Icon(Icons.Default.Settings, stringResource(R.string.settings))
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { backStack.add(Route.AddAccount) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_account))
                }
            },
        ) { padding ->
            if (accounts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.no_accounts),
                        Modifier.padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    items(accounts, key = { it.accountName }) { account ->
                        val provider = ProviderRegistry.get(account.providerId)
                        ListItem(
                            modifier = Modifier.clickable { backStack.add(Route.AccountDetail(account.accountName)) },
                            headlineContent = { Text(account.accountName) },
                            supportingContent = {
                                Column {
                                    Text(provider?.displayName ?: account.providerId)
                                    Text(
                                        account.lastSyncError
                                            ?: if (account.lastSyncEpochMs > 0)
                                                stringResource(R.string.last_synced, formatTime(account.lastSyncEpochMs))
                                            else stringResource(R.string.never_synced),
                                    )
                                }
                            },
                            leadingContent = {
                                Icon(androidx.compose.ui.res.painterResource(provider?.iconRes ?: R.drawable.ic_provider), null)
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.syncNow(account.accountName) }) {
                                    Icon(Icons.Default.Refresh, stringResource(R.string.sync_now))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
