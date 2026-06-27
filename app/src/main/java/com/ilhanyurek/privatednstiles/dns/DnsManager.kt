package com.ilhanyurek.privatednstiles.dns

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode

/**
 * Reads and writes the global Private DNS state via Settings.Global.
 *
 * Writing requires android.permission.WRITE_SECURE_SETTINGS, which can only be
 * granted over ADB:
 *
 *   adb shell pm grant com.ilhanyurek.privatednstiles android.permission.WRITE_SECURE_SETTINGS
 */
object DnsManager {

    private const val MODE = "private_dns_mode"
    private const val SPECIFIER = "private_dns_specifier"

    /** True if we currently hold WRITE_SECURE_SETTINGS (i.e. we can write). */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** The currently-applied mode read from the system. */
    fun currentMode(context: Context): DnsMode =
        DnsMode.fromSettingValue(Settings.Global.getString(context.contentResolver, MODE))

    /** The currently-applied strict-mode hostname, or empty string. */
    fun currentHostname(context: Context): String =
        Settings.Global.getString(context.contentResolver, SPECIFIER).orEmpty()

    /**
     * Apply [config] to the system. Returns true on success, false if the write
     * was rejected (typically missing WRITE_SECURE_SETTINGS).
     */
    fun apply(context: Context, config: DnsConfig): Boolean = runCatching {
        val resolver = context.contentResolver
        when (config.mode) {
            DnsMode.OFF, DnsMode.AUTOMATIC -> {
                Settings.Global.putString(resolver, MODE, config.mode.settingValue)
            }
            DnsMode.HOSTNAME -> {
                require(config.hostname.isNotBlank()) { "Hostname required for strict mode" }
                // Order matters: set the specifier before switching the mode so
                // the system never sees "hostname" mode with a stale specifier.
                Settings.Global.putString(resolver, SPECIFIER, config.hostname.trim())
                Settings.Global.putString(resolver, MODE, DnsMode.HOSTNAME.settingValue)
            }
        }
        true
    }.getOrDefault(false)

    /**
     * Find which saved config matches the live system state, so the tile can
     * highlight / cycle from the right place. Returns null if nothing matches.
     */
    fun matchActive(context: Context, configs: List<DnsConfig>): DnsConfig? {
        val mode = currentMode(context)
        val host = currentHostname(context)
        return configs.firstOrNull { c ->
            c.mode == mode && (mode != DnsMode.HOSTNAME || c.hostname.equals(host, ignoreCase = true))
        }
    }
}
