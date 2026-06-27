# Private DNS Tiles

Android uygulaması — telefon ve tablet için **Private DNS Quick Settings tiles**.
Birden fazla DNS kaydı ekleyip Quick Settings (hızlı ayarlar) panelinden tek
dokunuşla aralarında geçiş yapabilirsiniz.

> An Android app that adds Quick Settings tiles to switch Private DNS. Save as
> many DNS providers as you like and flip between them straight from the QS
> panel — no need to dig through Settings each time.

## Özellikler / Features

- 🔢 **Birden fazla DNS** kaydı: Off, Automatic ve istediğiniz kadar özel
  hostname (Cloudflare, Google, AdGuard, Quad9, NextDNS… kendi sunucunuz).
- ⚡ **İki Quick Settings tile**:
  - **DNS Cycle** — her dokunuşta listedeki bir sonraki DNS'e geçer.
  - **DNS Picker** — dokununca tüm kayıtların listesini açar, birini seçersiniz.
- 📱 **Telefon + tablet**: responsive Jetpack Compose arayüz, Material 3,
  dinamik renkler (Android 12+), açık/koyu tema.
- ✏️ Uygulama içinden ekle / düzenle / sil / sırala.
- 🔒 Verileriniz cihazda kalır (DataStore). İnternet izni yok.

## Nasıl çalışır / How it works

Android'de Private DNS, `Settings.Global` içindeki `private_dns_mode` ve
`private_dns_specifier` değerleriyle kontrol edilir. Bunları değiştirmek
`WRITE_SECURE_SETTINGS` iznini gerektirir; bu izin normal bir uygulamaya UI'dan
verilemez, **bir kez ADB ile** verilmelidir.

| Mod | `private_dns_mode` | Açıklama |
|-----|--------------------|----------|
| Off | `off` | Private DNS kapalı |
| Automatic | `opportunistic` | Ağdaki çözücüye fırsatçı DoT |
| Hostname | `hostname` | Belirli bir DoT sağlayıcısı (strict) |

## Kurulum / Setup

### 1. Derle (Android Studio veya CLI)

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Bu repo `gradlew` wrapper'ı içerir. Android Studio'da açıp doğrudan
> çalıştırabilirsiniz (Android SDK gerekli).

### 2. İzni ADB ile ver (zorunlu)

```bash
adb shell pm grant com.ilhanyurek.privatednstiles android.permission.WRITE_SECURE_SETTINGS
```

Bu adımı yapmadan tile'lar "Needs ADB permission" gösterir ve DNS değişmez.
İzin kalıcıdır; cihazı sıfırlamadıkça tekrar gerekmez.

### 3. Tile'ları ekle

Quick Settings panelini aç → kalem/düzenle → **DNS Cycle** ve/veya
**DNS Picker** tile'larını aktif kutucuklara sürükle.

## Kullanım / Usage

- Uygulamayı aç, hazır gelen DNS listesini gör. **Add DNS** ile yeni hostname
  ekle (ör. `dns.google`, `1dot1dot1dot1.cloudflare-dns.com`,
  `xxxxxx.dns.nextdns.io`).
- Listede bir karta dokun → o DNS hemen uygulanır.
- Yukarı/aşağı oklarla sırayı değiştir — **DNS Cycle** tile bu sıraya göre döner.
- QS panelinden **DNS Cycle**'a dokun: sıradaki DNS'e geç. **DNS Picker**'a
  dokun: listeden seç.

## Proje yapısı / Project layout

```
app/src/main/java/com/ilhanyurek/privatednstiles/
├── data/        DnsConfig (model) + DnsRepository (DataStore)
├── dns/         DnsManager — Settings.Global okuma/yazma
├── tile/        DnsTileService (cycle) + DnsPickerTileService (picker)
├── ui/          Compose ekranları + DnsViewModel
├── MainActivity.kt
└── PickerActivity.kt
```

## Gereksinimler / Requirements

- Android 9 (API 28) ve üzeri — Private DNS bu sürümle geldi.
- `WRITE_SECURE_SETTINGS` izni (ADB ile bir kez).
