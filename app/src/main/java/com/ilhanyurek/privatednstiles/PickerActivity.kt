package com.ilhanyurek.privatednstiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsManager
import com.ilhanyurek.privatednstiles.ui.theme.PrivateDnsTilesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent activity launched by the picker Quick Settings tile. Shows a
 * dialog of all saved DNS entries; tapping one applies it and finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
class PickerActivity : ComponentActivity() {

    private val repo by lazy { DnsRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrivateDnsTilesTheme {
                val configs by repo.configs.collectAsState(initial = emptyList())
                val activeId by repo.activeId.collectAsState(initial = null)
                val granted by produceState(initialValue = true) {
                    value = DnsManager.hasPermission(applicationContext)
                }

                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Choose Private DNS") },
                    text = {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            configs.forEach { config ->
                                PickerRow(
                                    config = config,
                                    selected = config.id == activeId,
                                    onClick = { applyAndFinish(config) }
                                )
                            }
                            if (!granted) {
                                Text(
                                    "Grant WRITE_SECURE_SETTINGS via ADB to switch DNS.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Close") } }
                )
            }
        }
    }

    /**
     * Apply on a background thread and only finish once the write completes, so
     * the work isn't cancelled when this short-lived activity goes away.
     */
    private fun applyAndFinish(config: DnsConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = DnsManager.apply(applicationContext, config)
            if (ok) repo.setActiveId(config.id)
            withContext(Dispatchers.Main) { finish() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerRow(config: DnsConfig, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        headlineContent = { Text(config.name) },
        supportingContent = { Text(config.subtitle) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) }
    )
}
