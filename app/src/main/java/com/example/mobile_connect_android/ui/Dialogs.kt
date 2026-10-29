package com.example.mobile_connect_android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The app's dialog, laid out the Material 3 way: 28dp corners, 24dp padding, a headline title,
 * supporting text, optional content, then text buttons. The main action sits at the right with Cancel
 * just before it; an optional third action sits alone at the left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismissText: String? = "Cancel",
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    busy: Boolean = false,
    neutralText: String? = null,
    onNeutral: () -> Unit = {},
    neutralDestructive: Boolean = false,
    content: (@Composable () -> Unit)? = null
) {
    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                if (text != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (content != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (neutralText != null) {
                        DialogButton(neutralText, onNeutral, enabled = !busy, destructive = neutralDestructive)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (dismissText != null) DialogButton(dismissText, onDismiss, enabled = !busy)
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 20.dp).size(20.dp), strokeWidth = 2.dp)
                        } else {
                            DialogButton(confirmText, onConfirm, enabled = confirmEnabled, destructive = destructive)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** A single-choice list for a dialog: the whole row is the touch target, as Android lists expect. */
@Composable
fun <T> DialogRadioList(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
    }
}
