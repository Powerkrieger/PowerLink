package de.powerizzle.powerlink

/** Debug log under the tag "PowerLink" (`adb logcat -s PowerLink`); silent in JVM tests. */
internal fun log(message: String) {
    runCatching { android.util.Log.d("PowerLink", message) }
}
