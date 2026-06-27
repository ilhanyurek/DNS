package com.ilhanyurek.privatednstiles.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilhanyurek.privatednstiles.data.DnsConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(
    state: UiState,
    message: String?,
    onApply: (DnsConfig) -> Unit,
    onSave: (DnsConfig) -> Unit,
    onDelete: (DnsConfig) -> Unit,
    onMove: (Int, Int) -> Unit,
    onConsumeMessage: () -> Unit
) {
    var editing by remember { mutableStateOf<DnsConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onConsumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Private DNS Tiles") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add DNS") }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!state.hasPermission) {
                    item { PermissionCard() }
                }
                itemsIndexed(state.configs, key = { _, c -> c.id }) { index, config ->
                    DnsCard(
                        config = config,
                        isActive = config.id == state.activeId,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.configs.lastIndex,
                        onApply = { onApply(config) },
                        onEdit = { editing = config; showEditor = true },
                        onDelete = { onDelete(config) },
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) }
                    )
                }
            }
        }
    }

    if (showEditor) {
        DnsEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onConfirm = { cfg -> onSave(cfg); showEditor = false }
        )
    }
}

@Composable
private fun DnsCard(
    config: DnsConfig,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        onClick = onApply,
        colors = if (isActive)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        config.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = if (isActive) 6.dp else 0.dp)
                    )
                }
                Text(
                    config.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun PermissionCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Permission needed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "To change Private DNS this app needs WRITE_SECURE_SETTINGS. " +
                    "Connect the device to a computer and run:\n\n" +
                    "adb shell pm grant com.ilhanyurek.privatednstiles " +
                    "android.permission.WRITE_SECURE_SETTINGS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
