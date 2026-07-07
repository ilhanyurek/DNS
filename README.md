# Private DNS Tiles

An Android app (phone + tablet) that adds **Private DNS Quick Settings tiles**.
Save as many DNS entries as you like and switch between them straight from the
Quick Settings panel — no digging through Settings each time.

## Features

- 🔢 **Multiple DNS entries**: Off, Automatic, and as many custom DoT
  hostnames as you want (Cloudflare, Google, AdGuard, Quad9, NextDNS, your own…).
- ⚡ **Two Quick Settings tiles**:
  - **DNS Cycle** — each tap switches to the next DNS in your list.
  - **DNS Picker** — tap opens a dialog listing every entry so you can jump
    straight to one.
- 👆 **Long-press a tile** to open the app (instead of the system App-info screen).
- 📱 **Phone + tablet**: responsive Jetpack Compose UI, Material 3, dynamic
  color (Android 12+), light/dark theme.
- ✏️ Add / edit / delete / reorder DNS entries in the app.
- 🔒 Your data stays on device (DataStore). No internet permission.

## How it works

On Android, Private DNS is controlled by the `private_dns_mode` and
`private_dns_specifier` values in `Settings.Global`. Changing them requires the
`WRITE_SECURE_SETTINGS` permission, which can't be granted from the UI — it must
be granted **once via ADB**.

| Mode | `private_dns_mode` | Description |
|------|--------------------|-------------|
| Off | `off` | Private DNS disabled |
| Automatic | `opportunistic` | Opportunistic DoT to the network resolver |
| Hostname | `hostname` | A specific DoT provider (strict) |

## Install

### Download the APK (easiest)

Grab the latest signed APK directly (not zipped):

```
https://github.com/ilhanyurek/DNS/releases/download/latest/PrivateDnsTiles.apk
```

Every push rebuilds and replaces this file, so the link always points at the
newest build. Release page: <https://github.com/ilhanyurek/DNS/releases/tag/latest>

### Grant the permission via ADB (required)

```bash
adb shell pm grant com.ilhanyurek.privatednstiles android.permission.WRITE_SECURE_SETTINGS
```

Without this, the tiles show "Needs ADB permission" and DNS won't change. The
grant is persistent — you only do it once (until a factory reset).

### Add the tiles

Open the Quick Settings panel → edit (pencil) → drag **DNS Cycle** and/or
**DNS Picker** into the active tiles.

## Usage

- Open the app to see the DNS list. Tap **Add DNS** to add a new hostname
  (e.g. `dns.google`, `1dot1dot1dot1.cloudflare-dns.com`, `xxxxxx.dns.nextdns.io`).
- Tap a card in the list → that DNS is applied immediately.
- Use the up/down arrows to reorder — **DNS Cycle** follows this order.
- From Quick Settings: tap **DNS Cycle** to advance to the next entry, or tap
  **DNS Picker** to choose one from a list. Long-press either tile to open the app.

## Build from source

The repo ships a Gradle wrapper, so you can open it in Android Studio and run,
or build from the CLI (Android SDK required):

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### CI & signing

`.github/workflows/build.yml` builds a minified, resource-shrunk **release**
APK on every push and publishes it to the `latest` release.

The signing keystore is **not** stored in the repo. CI reads it from GitHub
Actions secrets:

| Secret | Contents |
|--------|----------|
| `KEYSTORE_BASE64` | base64 of the release keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Locally the release build is unsigned unless a `release.keystore` is present in
`app/` with the matching `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
environment variables.

## Project layout

```
app/src/main/java/com/ilhanyurek/privatednstiles/
├── data/        DnsConfig (model) + DnsRepository (DataStore)
├── dns/         DnsManager — reads/writes Settings.Global
├── tile/        DnsTileService (cycle) + DnsPickerTileService (picker)
├── ui/          Compose screens + DnsViewModel
├── MainActivity.kt
└── PickerActivity.kt
```

## Requirements

- Android 9 (API 28) or newer — Private DNS was introduced in this release.
- `WRITE_SECURE_SETTINGS` granted once via ADB.
