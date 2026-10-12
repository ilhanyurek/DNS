package com.ilhanyurek.privatednstiles.tile

import android.os.Build
import android.service.quicksettings.Tile

/**
 * Tile subtitles exist only from Android 10. On Android 9 nothing is set; the
 * label and the icon still show the current DNS.
 */
internal fun Tile.setSubtitleIfSupported(text: CharSequence?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = text
}
