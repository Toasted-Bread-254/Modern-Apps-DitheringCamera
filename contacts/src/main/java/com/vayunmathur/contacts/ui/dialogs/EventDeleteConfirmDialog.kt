package com.vayunmathur.contacts.ui.dialogs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun EventDeleteConfirmDialog(
    contactId: Long,
    contactName: String? = null,
    viewModel: ContactViewModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.delete_contact_title)) },
        text = {
            Text(
                text = if (contactName.isNullOrBlank()) {
                    stringResource(R.string.delete_contact_confirm)
                } else {
                    stringResource(R.string.delete_contact_name_confirm, contactName)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    viewModel.getContact(contactId)?.let { contact ->
                        viewModel.deleteContact(contact)
                    }
                }
                onConfirm()
            }) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
