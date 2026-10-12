package com.ilhanyurek.privatednstiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ilhanyurek.privatednstiles.ui.DnsScreen
import com.ilhanyurek.privatednstiles.ui.DnsViewModel
import com.ilhanyurek.privatednstiles.ui.theme.PrivateDnsTilesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: DnsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrivateDnsTilesTheme {
                val state by viewModel.uiState.collectAsState()
                val message by viewModel.message.collectAsState()
                DnsScreen(
                    state = state,
                    message = message,
                    onApply = viewModel::apply,
                    onSave = viewModel::save,
                    onDelete = viewModel::delete,
                    onMove = viewModel::move,
                    onAutoFailoverChange = viewModel::setAutoFailover,
                    onConsumeMessage = viewModel::consumeMessage
                )
            }
        }
        // Re-test the servers whenever the network changes, but only while visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchReachability() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted via ADB while we were backgrounded.
        viewModel.refreshPermission()
    }
}
