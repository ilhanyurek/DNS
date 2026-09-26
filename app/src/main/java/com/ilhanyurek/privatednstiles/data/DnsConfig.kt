package com.ilhanyurek.privatednstiles.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The three Private DNS modes Android supports, mapped to the values stored in
 * Settings.Global "private_dns_mode".
 */
enum class DnsMode(val settingValue: String) {
    /** Private DNS disabled. */
    OFF("off"),

    /** "Automatic" – opportunistic DoT to the network-provided resolver. */
    AUTOMATIC("opportunistic"),

    /** Strict mode pointing at a specific provider hostname. */
    HOSTNAME("hostname");

    companion object {
        fun fromSettingValue(value: String?): DnsMode = when (value) {
            "off" -> OFF
            "hostname" -> HOSTNAME
            else -> AUTOMATIC // "opportunistic" or null/unknown -> Automatic
        }
    }
}

/**
 * A single saved Private DNS entry the user can switch to.
 *
 * For [DnsMode.HOSTNAME] the [hostname] is the DoT provider hostname
 * (e.g. "dns.google", "1dot1dot1dot1.cloudflare-dns.com").
 */
@Serializable
data class DnsConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mode: DnsMode,
    val hostname: String = ""
) {
    /** Short text shown on the tile / list subtitle. */
    val subtitle: String
        get() = when (mode) {
            DnsMode.OFF -> "Off"
            DnsMode.AUTOMATIC -> "Automatic"
            DnsMode.HOSTNAME -> hostname
        }

    companion object {
        /** Built-in entries always available, even before the user adds any. */
        fun defaults(): List<DnsConfig> = listOf(
            DnsConfig(id = "builtin-off", name = "Off", mode = DnsMode.OFF),
            DnsConfig(id = "builtin-auto", name = "Automatic", mode = DnsMode.AUTOMATIC),
            DnsConfig(
                id = "builtin-cloudflare",
                name = "Cloudflare",
                mode = DnsMode.HOSTNAME,
                hostname = "1dot1dot1dot1.cloudflare-dns.com"
            ),
            DnsConfig(
                id = "builtin-google",
                name = "Google",
                mode = DnsMode.HOSTNAME,
                hostname = "dns.google"
            ),
            DnsConfig(
                id = "builtin-adguard",
                name = "AdGuard",
                mode = DnsMode.HOSTNAME,
                hostname = "dns.adguard-dns.com"
            ),
            DnsConfig(
                id = "builtin-quad9",
                name = "Quad9",
                mode = DnsMode.HOSTNAME,
                hostname = "dns.quad9.net"
            )
        )
    }
}
