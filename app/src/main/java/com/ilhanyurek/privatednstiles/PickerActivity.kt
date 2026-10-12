package com.ilhanyurek.privatednstiles

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsManager
import com.ilhanyurek.privatednstiles.dns.DnsSwitcher
import com.ilhanyurek.privatednstiles.dns.SwitchResult
import com.ilhanyurek.privatednstiles.dns.message
import com.ilhanyurek.privatednstiles.ui.theme.PrivateDnsTilesTheme

/**
 * Transparent activity launched by the picker Quick Settings tile. Shows a
 * dialog of all saved DNS entries; tapping one switches to it through
 * [DnsSwitcher]. If the server is blocked on this network the dialog stays open
 * with a notice so another entry can be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
class PickerActivity : ComponentActivity() {

    private val repo by lazy { DnsRepository(applicationContext) }
    private var switchingId by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)

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
                                    switching = config.id == switchingId,
                                    onClick = { switchTo(config) }
                                )
                            }
                            notice?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp)
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
     * Switch in the app-wide scope so the check and verification finish even if
     * this short-lived activity goes away.
     */
    private fun switchTo(config: DnsConfig) {
        if (switchingId != null) return
        switchingId = config.id
        notice = null
        DnsSwitcher.launch(this, config) { result ->
            switchingId = null
            when {
                result is SwitchResult.Applied -> finish()
                // Keep the dialog open so another entry can be picked.
                result is SwitchResult.Blocked && !isFinishing -> { notice = result.message }
                else -> {
                    Toast.makeText(applicationContext, result.message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerRow(config: DnsConfig, selected: Boolean, switching: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        headlineContent = { Text(config.name) },
        supportingContent = { Text(config.subtitle) },
        leadingContent = {
            if (switching) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else RadioButton(selected = selected, onClick = onClick)
        }
    )
}
