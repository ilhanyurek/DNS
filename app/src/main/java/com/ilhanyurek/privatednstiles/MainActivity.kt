package com.ilhanyurek.privatednstiles

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

    // The result needs no handling: without the permission, notices fall back to toasts.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
        lifecycleScope.launch {
            viewModel.notificationPermissionRequests.collect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted via ADB while we were backgrounded, and
        // the network may have changed: re-check both.
        viewModel.onOpened()
    }
}
