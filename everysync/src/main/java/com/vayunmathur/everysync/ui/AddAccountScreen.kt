package com.vayunmathur.everysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_account_title)) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(ProviderRegistry.all, key = { it.id }) { provider ->
                ListItem(
                    modifier = Modifier.clickable {
                        when (provider.authType) {
                            AuthType.OAUTH -> viewModel.startOAuth(provider.id)
                            AuthType.DAV -> backStack.add(Route.DavLogin(provider.id))
                            AuthType.HEALTH_CONNECT -> viewModel.addHealthConnectAccount(provider.id) { backStack.pop() }
                        }
                    },
                    leadingContent = { Icon(painterResource(provider.iconRes), null) },
                    headlineContent = { Text(provider.displayName) },
                    supportingContent = if (provider.viaHealthConnect) {
                        { Text(stringResource(R.string.provider_via_health_connect)) }
                    } else null,
                )
            }
        }
    }
}
