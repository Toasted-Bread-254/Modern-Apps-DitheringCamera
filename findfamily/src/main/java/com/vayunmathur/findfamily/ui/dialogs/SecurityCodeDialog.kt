package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.util.FindFamilyViewModel

/**
 * Shows the connection's verification **security code**. Both people open this for each other and
 * compare the numbers; if they match, the end-to-end-encrypted link is verified and no one (not
 * even the server) has substituted a key to intercept it.
 */
@Composable
fun SecurityCodeDialog(user: User, ffViewModel: FindFamilyViewModel, onDismiss: () -> Unit) {
    var code by remember(user.id) { mutableStateOf<String?>(null) }
    var loading by remember(user.id) { mutableStateOf(true) }
    LaunchedEffect(user.id) {
        code = ffViewModel.securityCodeFor(user)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Security code") },
        text = {
            Column {
                Text("Compare this code with ${user.name} on their device. If the numbers match on both phones, your connection is verified end-to-end — no one is intercepting it.")
                Spacer(Modifier.height(16.dp))
                when {
                    loading -> Text("Computing…")
                    code == null -> Text("Couldn't compute yet — ${user.name}'s key isn't available. Make sure you're both connected, then try again.")
                    else -> Text(
                        code!!,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
