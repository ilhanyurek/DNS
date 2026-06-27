package com.ilhanyurek.privatednstiles.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode

/**
 * Add / edit dialog for a single DNS entry. Lets the user pick a mode and, for
 * strict mode, type the provider hostname.
 */
@Composable
fun DnsEditorDialog(
    initial: DnsConfig?,
    onDismiss: () -> Unit,
    onConfirm: (DnsConfig) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var mode by remember { mutableStateOf(initial?.mode ?: DnsMode.HOSTNAME) }
    var hostname by remember { mutableStateOf(initial?.hostname ?: "") }

    val hostInvalid = mode == DnsMode.HOSTNAME && hostname.isNotBlank() && !isValidHostname(hostname)
    val canSave = name.isNotBlank() &&
        (mode != DnsMode.HOSTNAME || (hostname.isNotBlank() && !hostInvalid))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add DNS" else "Edit DNS") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Mode",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                ModeRow("Off", mode == DnsMode.OFF) { mode = DnsMode.OFF }
                ModeRow("Automatic", mode == DnsMode.AUTOMATIC) { mode = DnsMode.AUTOMATIC }
                ModeRow("Hostname (strict)", mode == DnsMode.HOSTNAME) { mode = DnsMode.HOSTNAME }

                if (mode == DnsMode.HOSTNAME) {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it.trim() },
                        label = { Text("Provider hostname") },
                        placeholder = { Text("dns.google") },
                        singleLine = true,
                        isError = hostInvalid,
                        supportingText = {
                            if (hostInvalid) Text("Enter a valid hostname")
                            else Text("e.g. dns.google, dns.adguard-dns.com")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onConfirm(
                        (initial ?: DnsConfig(name = name, mode = mode)).copy(
                            name = name.trim(),
                            mode = mode,
                            hostname = if (mode == DnsMode.HOSTNAME) hostname.trim() else ""
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

/** Loose DoT-hostname sanity check (labels, letters/digits/hyphens, a dot). */
private fun isValidHostname(host: String): Boolean {
    val h = host.trim()
    if (h.length !in 1..253 || !h.contains('.')) return false
    return h.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first() != '-' && label.last() != '-' &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}
