package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.util.NavBackStack

@Composable
fun DavLoginScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel, providerId: String) {
    val provider = ProviderRegistry.get(providerId)
    var baseUrl by remember { mutableStateOf(provider?.davPresetUrl ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.dav_login_title, provider?.displayName ?: "")) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.dav_base_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = provider?.davPresetUrl == null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.dav_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dav_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    viewModel.davLogin(providerId, baseUrl.trim(), username.trim(), password) { backStack.pop() }
                },
            ) { Text(stringResource(R.string.login)) }
        },
        dismissButton = {
            TextButton(onClick = { backStack.pop() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
