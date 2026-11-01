package fool.akza.foto.data

import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import fool.akza.foto.R
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Pure formatting helpers for the info sheet. Everything here takes raw EXIF /
 * MediaMetadataRetriever strings and numbers, so it can be unit tested without
 * a device, a ContentResolver, or a Context.
 */

/** Parses an EXIF rational (`"1/250"`, `"0.008"`, `"37/1"`) into a double. */
internal fun parseRational(raw: String?): Double? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val slash = text.indexOf('/')
    if (slash <= 0) return text.toDoubleOrNull()
    val numerator = text.substring(0, slash).trim().toDoubleOrNull() ?: return null
    val denominator = text.substring(slash + 1).trim().toDoubleOrNull() ?: return null
    return if (denominator == 0.0) null else numerator / denominator
}

/** Fixed-point number with trailing zeros removed: `1.80` → `1.8`, `2.0` → `2`. */
internal fun decimal(value: Double, digits: Int): String {
    val text = String.format(Locale.US, "%.${digits}f", value)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

/** `1/250 s` for short exposures, `2.5 s` for long ones. */
internal fun formatExposureTime(raw: String?): String? {
    val seconds = parseRational(raw) ?: return null
    if (seconds <= 0.0) return null
    if (seconds >= 1.0) return "${decimal(seconds, 1)} s"
    val denominator = (1.0 / seconds).roundToLong()
    if (denominator < 2) return "${decimal(seconds, 3)} s"
    // Only snap to 1/N when the reciprocal is (near) exact; otherwise show the
    // real value so dragging-shutter exposures don't get rounded into a lie.
    val error = abs(1.0 / denominator - seconds) / seconds
    return if (error < 0.02) "1/$denominator s" else "${decimal(seconds, 4)} s"
}

/** `f/1.8`, `f/2`. */
internal fun formatAperture(raw: String?): String? {
    val f = parseRational(raw) ?: return null
    if (f <= 0.0) return null
    return "f/${decimal(f, 1)}"
}

/** Millimetres, as stored: `4.3 mm`. */
internal fun formatMillimetres(raw: String?): String? {
    val mm = parseRational(raw) ?: return null
    if (mm <= 0.0) return null
    return "${decimal(mm, 1)} mm"
}

/** `+0.3 EV`, `-1.7 EV`, `0 EV`. */
internal fun formatExposureBias(raw: String?): String? {
    val ev = parseRational(raw) ?: return null
    val sign = when {
        ev > 0.0 -> "+"
        ev < 0.0 -> "-"
        else -> ""
    }
    val magnitude = decimal(abs(ev), 2)
    // Don't print "+0 EV": a bias smaller than the printed precision is zero.
    return if (magnitude == "0") "0 EV" else "$sign$magnitude EV"
}

/** `2×`, `1.5×`. `1×` (the "not zoomed" default) is reported as absent. */
internal fun formatDigitalZoom(raw: String?): String? {
    val zoom = parseRational(raw) ?: return null
    if (zoom <= 1.0) return null
    return "${decimal(zoom, 2)}×"
}

/** `1.2 m`, `12 km`; unknown (0 or the 0xFFFFFFFF sentinel) is absent. */
internal fun formatSubjectDistance(raw: String?): String? {
    val metres = parseRational(raw) ?: return null
    if (metres <= 0.0 || metres > 1_000_000.0) return null
    return if (metres >= 1000.0) "${decimal(metres / 1000.0, 1)} km" else "${decimal(metres, 2)} m"
}

/**
 * Degrees to a geographic coordinate: `37° 46' 29.6" N`. Seconds are rounded
 * to one decimal first, then carried into minutes/degrees so a value like
 * 59.96" never prints as 60".
 */
internal fun formatCoordinate(degrees: Double, isLatitude: Boolean): String {
    val hemisphere = when {
        isLatitude && degrees >= 0 -> "N"
        isLatitude -> "S"
        degrees >= 0 -> "E"
        else -> "W"
    }
    val magnitude = abs(degrees)
    var whole = floor(magnitude).toLong()
    val tenthsOfSeconds = ((magnitude - whole) * 36_000.0).roundToLong()
    var minutes = tenthsOfSeconds / 600
    val seconds = (tenthsOfSeconds % 600) / 10.0
    if (minutes >= 60) {
        whole += 1
        minutes = 0
    }
    return "$whole° $minutes' ${decimal(seconds, 1)}\" $hemisphere"
}

/** `12.5 m` above/below the reference datum. */
internal fun formatAltitude(metres: Double): String = "${decimal(metres, 1)} m"

/** `1:03` under an hour, `1:02:03` above it. */
internal fun formatDurationMs(ms: Long): String? {
    if (ms <= 0L) return null
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** `12.4 Mbps` / `850 kbps` from a bits-per-second count. */
internal fun formatBitrate(bitsPerSecond: Long): String? {
    if (bitsPerSecond <= 0L) return null
    // Round first, then pick the unit, so 999_999 bits is "1 Mbps" and not
    // "1000 kbps".
    return if (bitsPerSecond >= 999_500L) {
        "${decimal(bitsPerSecond / 1_000_000.0, 1)} Mbps"
    } else {
        "${decimal(bitsPerSecond / 1000.0, 0)} kbps"
    }
}

/** `29.97 fps`; zero/absent is reported as absent. */
internal fun formatFrameRate(raw: String?): String? {
    val fps = raw?.trim()?.toDoubleOrNull() ?: return null
    if (fps <= 0.0) return null
    return "${decimal(fps, 2)} fps"
}

/** `48 kHz` (whole kilohertz) else `44100 Hz`. */
internal fun formatSampleRate(hz: Long): String? = when {
    hz <= 0L -> null
    hz % 1000L == 0L -> "${hz / 1000L} kHz"
    else -> "$hz Hz"
}

/**
 * EXIF date/time (`2024:05:01 12:34:56`) or ISO-8601 into `May 1, 2024, 12:34 PM`.
 * Unparseable values fall through unchanged rather than disappearing.
 */
internal fun formatExifDateTime(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val dateTime = listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")
    val dateOnly = listOf("yyyy:MM:dd", "yyyy-MM-dd")
    for (pattern in dateTime) {
        parseDate(text, pattern)?.let {
            return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(it)
        }
    }
    for (pattern in dateOnly) {
        parseDate(text, pattern)?.let {
            return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
        }
    }
    return text
}

private fun parseDate(text: String, pattern: String): java.util.Date? = try {
    val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }
    // Lenient parsing happily turns "00:00:00" into a year-zero date; a real
    // capture date is the only thing worth reformatting.
    format.parse(text)?.takeIf { date ->
        val year = java.util.Calendar.getInstance().apply { time = date }.get(java.util.Calendar.YEAR)
        year in 1900..2200
    }
} catch (_: Exception) {
    null
}

/**
 * Container date tag. QuickTime-based containers report the 1904 epoch
 * placeholder (`19040101T000000.000Z`) when the file has no real date, which
 * would read as a capture date from 1904; drop those instead.
 */
internal fun formatContainerDate(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.startsWith("1904")) return null
    return formatExifDateTime(text)
}

/**
 * Year tag. Some containers put the full timestamp in the year field, so keep
 * the first plausible four-digit year instead of printing the whole stamp.
 */
internal fun formatYear(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return Regex("(19|20|21)\\d{2}").find(text)?.value ?: text
}

/** `ISOSpeedRatings` → `ISO Speed Ratings`, `XPTitle` → `XP Title`. */
internal fun humanizeExifTag(tag: String): String {
    val out = StringBuilder(tag.length + 6)
    tag.forEachIndexed { index, ch ->
        val previous = tag.getOrNull(index - 1)
        val next = tag.getOrNull(index + 1)
        val boundary = index > 0 && ch.isUpperCase() && previous != null &&
            (previous.isLowerCase() || previous.isDigit() || (previous.isUpperCase() && next != null && next.isLowerCase()))
        if (boundary) out.append(' ')
        out.append(ch)
    }
    return out.toString()
}

// ---- Enumerated EXIF values → string resources ---------------------------

/** EXIF orientation (1–8) as the transformation it describes. */
@StringRes
internal fun orientationLabelRes(orientation: Int): Int? = when (orientation) {
    1 -> R.string.info_orientation_normal
    2 -> R.string.info_orientation_mirror_horizontal
    3 -> R.string.info_orientation_rotate_180
    4 -> R.string.info_orientation_mirror_vertical
    5 -> R.string.info_orientation_transpose
    6 -> R.string.info_orientation_rotate_90
    7 -> R.string.info_orientation_transverse
    8 -> R.string.info_orientation_rotate_270
    else -> null
}

/** EXIF ExposureProgram. */
@StringRes
internal fun exposureProgramLabelRes(program: Int): Int? = when (program) {
    0 -> R.string.info_program_undefined
    1 -> R.string.info_program_manual
    2 -> R.string.info_program_normal
    3 -> R.string.info_program_aperture
    4 -> R.string.info_program_shutter
    5 -> R.string.info_program_creative
    6 -> R.string.info_program_action
    7 -> R.string.info_program_portrait
    8 -> R.string.info_program_landscape
    else -> null
}

/** EXIF ExposureMode. */
@StringRes
internal fun exposureModeLabelRes(mode: Int): Int? = when (mode) {
    0 -> R.string.info_exposure_mode_auto
    1 -> R.string.info_exposure_mode_manual
    2 -> R.string.info_exposure_mode_bracket
    else -> null
}

/** EXIF MeteringMode. */
@StringRes
internal fun meteringLabelRes(mode: Int): Int? = when (mode) {
    0 -> R.string.info_metering_unknown
    1 -> R.string.info_metering_average
    2 -> R.string.info_metering_center
    3 -> R.string.info_metering_spot
    4 -> R.string.info_metering_multi_spot
    5 -> R.string.info_metering_multi_segment
    6 -> R.string.info_metering_partial
    255 -> R.string.info_metering_other
    else -> null
}

/** EXIF WhiteBalance. */
@StringRes
internal fun whiteBalanceLabelRes(mode: Int): Int? = when (mode) {
    0 -> R.string.info_white_balance_auto
    1 -> R.string.info_white_balance_manual
    else -> null
}

/** EXIF SceneCaptureType. */
@StringRes
internal fun sceneCaptureLabelRes(type: Int): Int? = when (type) {
    0 -> R.string.info_scene_standard
    1 -> R.string.info_scene_landscape
    2 -> R.string.info_scene_portrait
    3 -> R.string.info_scene_night
    else -> null
}

/**
 * EXIF Flash is a bit field; bit 0 is the fired flag and 0x20 means the device
 * has no flash at all, which is the only part worth spelling out.
 */
@StringRes
internal fun flashLabelRes(flash: Int): Int? = when {
    flash < 0 -> null
    flash and 0x20 != 0 -> R.string.info_flash_unavailable
    flash and 0x1 != 0 -> R.string.info_flash_fired
    else -> R.string.info_flash_not_fired
}

/**
 * Human wording for the enumerated tags that only reach the info sheet through
 * its verbatim tail (they have no dedicated row). Values that aren't listed
 * here stay numeric — better a raw number than a wrong label.
 */
@StringRes
internal fun exifTagValueRes(tag: String, raw: String): Int? {
    val value = raw.trim().toIntOrNull() ?: return null
    return when (tag) {
        ExifInterface.TAG_CONTRAST -> when (value) {
            0 -> R.string.info_normal
            1 -> R.string.info_low
            2 -> R.string.info_high
            else -> null
        }
        ExifInterface.TAG_SATURATION -> when (value) {
            0 -> R.string.info_normal
            1 -> R.string.info_low_saturation
            2 -> R.string.info_high_saturation
            else -> null
        }
        ExifInterface.TAG_SHARPNESS -> when (value) {
            0 -> R.string.info_normal
            1 -> R.string.info_soft
            2 -> R.string.info_hard
            else -> null
        }
        ExifInterface.TAG_CUSTOM_RENDERED -> when (value) {
            0 -> R.string.info_normal_process
            1 -> R.string.info_custom_process
            else -> null
        }
        ExifInterface.TAG_GAIN_CONTROL -> when (value) {
            0 -> R.string.info_none
            1 -> R.string.info_low_gain_up
            2 -> R.string.info_high_gain_up
            3 -> R.string.info_low_gain_down
            4 -> R.string.info_high_gain_down
            else -> null
        }
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE -> when (value) {
            0 -> R.string.info_unknown
            1 -> R.string.info_macro
            2 -> R.string.info_distance_close
            3 -> R.string.info_distant
            else -> null
        }
        ExifInterface.TAG_LIGHT_SOURCE -> when (value) {
            0 -> R.string.info_unknown
            1 -> R.string.info_daylight
            2 -> R.string.info_fluorescent
            3 -> R.string.info_tungsten
            4 -> R.string.info_light_flash
            9 -> R.string.info_fine_weather
            10 -> R.string.info_cloudy
            11 -> R.string.info_shade
            255 -> R.string.info_other
            else -> null
        }
        ExifInterface.TAG_SCENE_TYPE -> if (value == 1) R.string.info_directly_photographed else null
        else -> null
    }
}

/** EXIF ColorSpace: 1 = sRGB, 0xFFFF = uncalibrated. */
@StringRes
internal fun colorSpaceLabelRes(space: Int): Int? = when (space) {
    1 -> R.string.info_color_space_srgb
    0xFFFF -> R.string.info_color_space_uncalibrated
    else -> null
}
