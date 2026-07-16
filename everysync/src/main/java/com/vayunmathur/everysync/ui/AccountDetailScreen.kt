package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel, accountName: String) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val config = accounts.firstOrNull { it.accountName == accountName }
    val provider = config?.let { ProviderRegistry.get(it.providerId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(accountName) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        IconBack()
                    }
                },
            )
        },
    ) { padding ->
        if (config == null || provider == null) {
            Text(stringResource(R.string.no_accounts), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(Modifier.padding(padding)) {
            if (DataType.CONTACTS in provider.capabilities) {
                TypeToggle(R.string.sync_contacts, DataType.CONTACTS in config.enabledTypes) {
                    viewModel.toggleType(accountName, DataType.CONTACTS, it)
                }
            }
            if (DataType.CALENDAR in provider.capabilities) {
                TypeToggle(R.string.sync_calendar, DataType.CALENDAR in config.enabledTypes) {
                    viewModel.toggleType(accountName, DataType.CALENDAR, it)
                }
            }
            if (DataType.HEALTH in provider.capabilities) {
                TypeToggle(R.string.sync_health, DataType.HEALTH in config.enabledTypes) {
                    viewModel.toggleType(accountName, DataType.HEALTH, it)
                }
            }

            Button(
                onClick = { viewModel.syncNow(accountName) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.sync_now)) }

            OutlinedButton(
                onClick = {
                    viewModel.removeAccount(accountName)
                    backStack.pop()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.remove_account)) }
        }
    }
}

@Composable
private fun TypeToggle(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        content = { Text(stringResource(labelRes)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
