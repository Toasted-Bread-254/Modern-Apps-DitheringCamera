package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
                singleLine = true,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.wifi_only)) },
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
        headlineContent = { Text(stringResource(labelRes)) },
        leadingContent = { RadioButton(selected = selected == value, onClick = { onSelect(value) }) },
    )
}
