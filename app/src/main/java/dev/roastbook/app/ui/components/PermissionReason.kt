package dev.roastbook.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.roastbook.app.R

/**
 * Why Android is about to ask for a permission, said once, before its dialog: a Material dialog with
 * a title, the reason, and a text Not now beside a filled Continue at the bottom end. Continue goes on to Android's
 * question; Not now (also Back, Escape or a tap outside) closes it and asks nothing.
 */
@Composable
fun PermissionReasonDialog(title: String, reason: String, onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(title) },
        text = { Text(reason) },
        confirmButton = { Button(onClick = onContinue) { Text(stringResource(R.string.permission_continue)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.permission_not_now)) } },
    )
}
