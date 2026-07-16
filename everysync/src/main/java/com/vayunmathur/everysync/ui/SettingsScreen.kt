package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.Settings
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    val context = LocalContext.current
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    var intervalText by remember(interval) { mutableStateOf(interval.toString()) }
    var conflict by remember { mutableStateOf(Settings.conflictPolicy(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        IconBack()
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = intervalText,
                onValueChange = {
                    intervalText = it.filter { c -> c.isDigit() }
                    intervalText.toLongOrNull()?.let { m -> viewModel.setInterval(m) }
                },
                label = { Text(stringResource(R.string.global_interval)) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            ListItem(
                content = { Text(stringResource(R.string.wifi_only)) },
                trailingContent = { Switch(checked = wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) }) },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.conflict_policy),
                Modifier.padding(16.dp),
            )
            ConflictOption(R.string.conflict_lww, Settings.CONFLICT_LWW, conflict) { conflict = it; viewModel.setConflictPolicy(it) }
            ConflictOption(R.string.conflict_remote, Settings.CONFLICT_REMOTE, conflict) { conflict = it; viewModel.setConflictPolicy(it) }
            ConflictOption(R.string.conflict_local, Settings.CONFLICT_LOCAL, conflict) { conflict = it; viewModel.setConflictPolicy(it) }
        }
    }
}

@Composable
private fun ConflictOption(labelRes: Int, value: String, selected: String, onSelect: (String) -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected == value, onClick = { onSelect(value) }),
        content = { Text(stringResource(labelRes)) },
        leadingContent = { RadioButton(selected = selected == value, onClick = { onSelect(value) }) },
    )
}
