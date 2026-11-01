package fool.akza.foto.data

import androidx.annotation.StringRes
import fool.akza.foto.R

/**
 * Groups the info sheet prints as titled blocks. Declaration order is display
 * order: file facts first, then the media-specific technical blocks, then the
 * capture blocks, and finally the verbatim tag dump.
 */
enum class MetadataGroup(@StringRes val titleRes: Int) {
    FILE(R.string.info_section_file),
    IMAGE(R.string.info_section_image),
    PNG_TEXT(R.string.info_section_png_text),
    VIDEO(R.string.info_section_video),
    AUDIO(R.string.info_section_audio),
    DATES(R.string.info_section_dates),
    CAMERA(R.string.info_section_camera),
    EXPOSURE(R.string.info_section_exposure),
    LOCATION(R.string.info_section_location),
    TAGS(R.string.info_section_tags),
    OTHER(R.string.info_section_other),
}

/**
 * A row's label. Known fields carry a localized string resource; tags we only
 * surface verbatim (the tail of the EXIF dictionary) carry their humanized EXIF
 * tag name so nothing gets lost in translation.
 */
sealed interface MetadataLabel {
    data class Res(@StringRes val id: Int) : MetadataLabel

    /** Humanized EXIF tag name, e.g. `Aperture Value`. */
    data class Tag(val name: String) : MetadataLabel
}

/**
 * A row's value. Enumerated EXIF values (orientation, metering mode, …) stay as
 * string resources so they localize with the rest of the UI instead of leaking
 * raw integers; everything else is already a formatted string.
 */
sealed interface MetadataValue {
    data class Text(val text: String) : MetadataValue

    data class Res(@StringRes val id: Int) : MetadataValue
}

/** One label/value pair in the info sheet. */
data class MetadataField(val label: MetadataLabel, val value: MetadataValue)

/** A titled block of fields. Never emitted empty. */
data class MetadataSection(val group: MetadataGroup, val fields: List<MetadataField>)

/** Builds one section and drops empty values, so callers stay terse. */
internal class SectionDraft(private val group: MetadataGroup) {
    private val fields = mutableListOf<MetadataField>()

    fun text(@StringRes label: Int, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() && it != "-" } ?: return
        fields += MetadataField(MetadataLabel.Res(label), MetadataValue.Text(clean))
    }

    fun res(@StringRes label: Int, @StringRes value: Int?) {
        if (value == null) return
        fields += MetadataField(MetadataLabel.Res(label), MetadataValue.Res(value))
    }

    /** Free-form row: the label is printed exactly as given (PNG keywords). */
    fun entry(label: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        fields += MetadataField(MetadataLabel.Tag(label), MetadataValue.Text(clean))
    }

    /** Verbatim tag row whose value is an enumerated string resource. */
    fun tagRes(tag: String, @StringRes value: Int?) {
        if (value == null) return
        fields += MetadataField(MetadataLabel.Tag(humanizeExifTag(tag)), MetadataValue.Res(value))
    }

    /** Verbatim tag row, shown under its humanized EXIF name. */
    fun tag(tag: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        fields += MetadataField(MetadataLabel.Tag(humanizeExifTag(tag)), MetadataValue.Text(clean))
    }

    fun toSection(): MetadataSection? =
        fields.takeIf { it.isNotEmpty() }?.let { MetadataSection(group, it) }
}
