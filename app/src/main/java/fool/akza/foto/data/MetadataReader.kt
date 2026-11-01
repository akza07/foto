package fool.akza.foto.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import fool.akza.foto.AppLog
import fool.akza.foto.R

/**
 * Reads everything a file says about itself and shapes it into the titled
 * sections the info sheet prints.
 *
 * Images go through [ExifInterface] (JPEG, PNG, WebP, HEIC/HEIF, DNG), so the
 * camera, exposure, GPS and descriptive blocks come from the embedded EXIF
 * dictionary; tags that have no first-class row in the sheet are still listed
 * verbatim at the end rather than dropped. Videos have no EXIF dictionary we
 * can rely on, so [MediaMetadataRetriever] supplies container metadata
 * (duration, bitrate, codecs, rotation, tags).
 *
 * Every read is best effort: a missing or unreadable file yields no sections,
 * never an error row and never a crash.
 */
internal object MetadataReader {

    fun read(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        mimeType: String?,
    ): List<MetadataSection> = try {
        if (isVideo) videoSections(context, uri) else imageSections(context, uri, mimeType)
    } catch (e: Exception) {
        AppLog.w("metadata read failed for $uri", e)
        emptyList()
    }

    // ---- Images ----------------------------------------------------------

    private fun imageSections(
        context: Context,
        uri: Uri,
        mimeType: String?,
    ): List<MetadataSection> {
        val exif = openImage(context, uri)?.use { ExifInterface(it) }
        val sections = exif?.let(::exifSections).orEmpty().toMutableList()
        // PNG keeps its descriptive metadata in textual chunks rather than in
        // the EXIF dictionary, so it gets its own block.
        if (mimeType?.endsWith("png", ignoreCase = true) == true) {
            val pngText = SectionDraft(MetadataGroup.PNG_TEXT)
            openImage(context, uri)?.use { stream -> PngText.addSection(stream, pngText) }
            pngText.toSection()?.let(sections::add)
        }
        // Display order is the group order, whatever order the readers ran in.
        return sections.sortedBy { it.group.ordinal }
    }

    /**
     * Opens the file's bytes, asking for the unredacted original when we may.
     *
     * MediaProvider rewrites the EXIF block of a MediaStore stream to zero out
     * GPS latitude/longitude/altitude/timestamps unless the caller asks for the
     * original bytes and holds ACCESS_MEDIA_LOCATION. SAF document URIs are not
     * redacted and reject that request, and some providers reject it outright,
     * so the plain URI remains the fallback rather than a dead end.
     */
    private fun openImage(context: Context, uri: Uri): java.io.InputStream? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val original = try {
                MediaStore.setRequireOriginal(uri)
            } catch (_: Exception) {
                null
            }
            if (original != null) {
                val stream = context.contentResolver.openInputStream(original)
                if (stream != null) return stream
            }
        }
        return context.contentResolver.openInputStream(uri)
    }

    private fun exifSections(exif: ExifInterface): List<MetadataSection> {
        val used = mutableSetOf<String>()
        val sections = mutableListOf<MetadataSection>()

        fun attribute(tag: String): String? {
            used += tag
            return exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun attributeInt(tag: String): Int {
            used += tag
            return exif.getAttributeInt(tag, -1)
        }

        // --- Image technical block ---
        val image = SectionDraft(MetadataGroup.IMAGE)
        image.res(R.string.info_orientation, orientationLabelRes(attributeInt(ExifInterface.TAG_ORIENTATION)))
        image.res(R.string.info_color_space, colorSpaceLabelRes(attributeInt(ExifInterface.TAG_COLOR_SPACE)))
        attribute(ExifInterface.TAG_BITS_PER_SAMPLE)?.let {
            image.text(R.string.info_bits_per_sample, it.replace(",", ", "))
        }
        image.text(R.string.info_resolution, resolution({ attribute(it) }, { attributeInt(it) }))
        image.text(R.string.info_description, attribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        image.toSection()?.let(sections::add)

        // --- Capture dates ---
        val dates = SectionDraft(MetadataGroup.DATES)
        dates.text(
            R.string.info_date_taken,
            formatExifDateTime(attribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
        )
        dates.text(
            R.string.info_date_digitized,
            formatExifDateTime(attribute(ExifInterface.TAG_DATETIME_DIGITIZED)),
        )
        dates.text(
            R.string.info_date_in_file,
            formatExifDateTime(attribute(ExifInterface.TAG_DATETIME)),
        )
        dates.toSection()?.let(sections::add)

        // --- Capture device ---
        val camera = SectionDraft(MetadataGroup.CAMERA)
        camera.text(R.string.info_make, attribute(ExifInterface.TAG_MAKE))
        camera.text(R.string.info_model, attribute(ExifInterface.TAG_MODEL))
        val lens = listOfNotNull(
            attribute(ExifInterface.TAG_LENS_MAKE),
            attribute(ExifInterface.TAG_LENS_MODEL),
        ).distinct().joinToString(" ").takeIf { it.isNotBlank() }
        camera.text(R.string.info_lens, lens)
        attribute(ExifInterface.TAG_LENS_SERIAL_NUMBER)?.let {
            camera.text(R.string.info_lens_serial, it)
        }
        attribute(ExifInterface.TAG_BODY_SERIAL_NUMBER)?.let {
            camera.text(R.string.info_serial, it)
        }
        camera.text(R.string.info_software, attribute(ExifInterface.TAG_SOFTWARE))
        camera.text(R.string.info_artist, attribute(ExifInterface.TAG_ARTIST))
        camera.text(R.string.info_copyright, attribute(ExifInterface.TAG_COPYRIGHT))
        camera.toSection()?.let(sections::add)

        // --- Exposure ---
        val exposure = SectionDraft(MetadataGroup.EXPOSURE)
        exposure.text(
            R.string.info_exposure_time,
            formatExposureTime(attribute(ExifInterface.TAG_EXPOSURE_TIME)),
        )
        exposure.text(
            R.string.info_aperture,
            formatAperture(attribute(ExifInterface.TAG_F_NUMBER)),
        )
        // Cameras write ISO under PhotographicSensitivity since EXIF 2.3; the
        // older ISOSpeedRatings tag is the fallback for legacy files. Only the
        // tag we actually show is consumed, so the other stays in the tail.
        val modernIso = attribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        val iso = modernIso ?: attribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
        if (modernIso != null) used += ExifInterface.TAG_ISO_SPEED_RATINGS
        exposure.text(R.string.info_iso, iso)
        exposure.text(
            R.string.info_focal_length,
            formatMillimetres(attribute(ExifInterface.TAG_FOCAL_LENGTH)),
        )
        exposure.text(
            R.string.info_focal_length_35mm,
            formatMillimetres(attribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)),
        )
        exposure.text(
            R.string.info_exposure_bias,
            formatExposureBias(attribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)),
        )
        exposure.res(
            R.string.info_exposure_program,
            exposureProgramLabelRes(attributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM)),
        )
        exposure.res(
            R.string.info_exposure_mode,
            exposureModeLabelRes(attributeInt(ExifInterface.TAG_EXPOSURE_MODE)),
        )
        exposure.res(
            R.string.info_metering,
            meteringLabelRes(attributeInt(ExifInterface.TAG_METERING_MODE)),
        )
        exposure.res(R.string.info_flash, flashLabelRes(attributeInt(ExifInterface.TAG_FLASH)))
        exposure.res(
            R.string.info_white_balance,
            whiteBalanceLabelRes(attributeInt(ExifInterface.TAG_WHITE_BALANCE)),
        )
        exposure.res(
            R.string.info_scene_capture,
            sceneCaptureLabelRes(attributeInt(ExifInterface.TAG_SCENE_CAPTURE_TYPE)),
        )
        exposure.text(
            R.string.info_digital_zoom,
            formatDigitalZoom(attribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)),
        )
        exposure.text(
            R.string.info_subject_distance,
            formatSubjectDistance(attribute(ExifInterface.TAG_SUBJECT_DISTANCE)),
        )
        exposure.toSection()?.let(sections::add)

        // --- Location ---
        val location = SectionDraft(MetadataGroup.LOCATION)
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            used += ExifInterface.TAG_GPS_LATITUDE
            used += ExifInterface.TAG_GPS_LATITUDE_REF
            used += ExifInterface.TAG_GPS_LONGITUDE
            used += ExifInterface.TAG_GPS_LONGITUDE_REF
            location.text(R.string.info_latitude, formatCoordinate(latLong[0].toDouble(), true))
            location.text(R.string.info_longitude, formatCoordinate(latLong[1].toDouble(), false))
        }
        // Altitude is a rational with a separate above/below-datum flag; reading
        // it from the tags keeps us off the deprecated getAltitude() accessor.
        val altitude = parseRational(attribute(ExifInterface.TAG_GPS_ALTITUDE))
        if (altitude != null) {
            val belowDatum = attribute(ExifInterface.TAG_GPS_ALTITUDE_REF) == "1"
            location.text(
                R.string.info_altitude,
                formatAltitude(if (belowDatum) -altitude else altitude),
            )
        }
        // GPS stores the date and the time as two separate tags; join them only
        // when the date is present, so a lone timestamp can't parse as a
        // year-zero date.
        val gpsDate = attribute(ExifInterface.TAG_GPS_DATESTAMP)
        val gpsTime = attribute(ExifInterface.TAG_GPS_TIMESTAMP)
        location.text(
            R.string.info_gps_time,
            formatExifDateTime(gpsDate?.let { date -> gpsTime?.let { "$date $it" } ?: date }),
        )
        location.text(R.string.info_gps_datum, attribute(ExifInterface.TAG_GPS_MAP_DATUM))
        location.toSection()?.let(sections::add)

        // --- Remaining tags, verbatim ---
        val other = SectionDraft(MetadataGroup.OTHER)
        ExifDetailTags.forEach { tag ->
            if (tag in used) return@forEach
            val value = exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            // Skip blobs (XMP, maker notes, thumbnails) that would flood the sheet.
            if (value.length > MaxVerbatimValueLength) return@forEach
            val enumLabel = exifTagValueRes(tag, value)
            if (enumLabel != null) other.tagRes(tag, enumLabel) else other.tag(tag, value)
        }
        other.toSection()?.let(sections::add)

        return sections
    }

    /** `72 × 72 dpi` / `300 dpi` from X/Y resolution plus the unit tag. */
    private fun resolution(attribute: (String) -> String?, attributeInt: (String) -> Int): String? {
        val x = parseRational(attribute(ExifInterface.TAG_X_RESOLUTION)) ?: return null
        val y = parseRational(attribute(ExifInterface.TAG_Y_RESOLUTION)) ?: x
        val unit = when (attributeInt(ExifInterface.TAG_RESOLUTION_UNIT)) {
            3 -> "dpcm"
            else -> "dpi"
        }
        val xText = decimal(x, 1)
        val yText = decimal(y, 1)
        return if (xText == yText) "$xText $unit" else "$xText × $yText $unit"
    }

    // ---- Video -----------------------------------------------------------

    private fun videoSections(context: Context, uri: Uri): List<MetadataSection> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val meta = { key: Int -> metadata(retriever, key) }
            val sections = mutableListOf<MetadataSection>()

            val video = SectionDraft(MetadataGroup.VIDEO)
            video.text(
                R.string.info_duration,
                formatDurationMs(meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L),
            )
            video.text(
                R.string.info_bitrate,
                formatBitrate(meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L),
            )
            video.text(
                R.string.info_frame_rate,
                formatFrameRate(meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)),
            )
            meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                ?.takeIf { it != 0 }
                ?.let { video.text(R.string.info_rotation, "$it°") }
            video.text(
                R.string.info_frames,
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT),
            )
            video.res(
                R.string.info_has_video,
                yesNo(meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)),
            )
            video.res(
                R.string.info_has_audio,
                yesNo(meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                video.text(
                    R.string.info_color_standard,
                    meta(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD),
                )
                video.text(
                    R.string.info_color_transfer,
                    meta(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER),
                )
                video.text(
                    R.string.info_color_range,
                    meta(MediaMetadataRetriever.METADATA_KEY_COLOR_RANGE),
                )
            }
            video.toSection()?.let(sections::add)

            val audio = SectionDraft(MetadataGroup.AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.text(
                    R.string.info_sample_rate,
                    formatSampleRate(meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toLongOrNull() ?: 0L),
                )
                meta(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { audio.text(R.string.info_audio_bit_depth, "$it bits") }
            }
            audio.toSection()?.let(sections::add)

            val tags = SectionDraft(MetadataGroup.TAGS)
            tags.text(R.string.info_media_title, meta(MediaMetadataRetriever.METADATA_KEY_TITLE))
            tags.text(R.string.info_artist, meta(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            tags.text(R.string.info_album, meta(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            tags.text(
                R.string.info_album_artist,
                meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            )
            tags.text(R.string.info_author, meta(MediaMetadataRetriever.METADATA_KEY_AUTHOR))
            tags.text(R.string.info_composer, meta(MediaMetadataRetriever.METADATA_KEY_COMPOSER))
            tags.text(R.string.info_writer, meta(MediaMetadataRetriever.METADATA_KEY_WRITER))
            tags.text(R.string.info_date, formatContainerDate(meta(MediaMetadataRetriever.METADATA_KEY_DATE)))
            tags.text(R.string.info_year, formatYear(meta(MediaMetadataRetriever.METADATA_KEY_YEAR)))
            tags.text(R.string.info_genre, meta(MediaMetadataRetriever.METADATA_KEY_GENRE))
            tags.text(
                R.string.info_track,
                meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
            )
            tags.text(R.string.info_disc, meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            tags.text(
                R.string.info_total_tracks,
                meta(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS),
            )
            tags.res(
                R.string.info_compilation,
                yesNo(meta(MediaMetadataRetriever.METADATA_KEY_COMPILATION)),
            )
            tags.text(
                R.string.info_location,
                meta(MediaMetadataRetriever.METADATA_KEY_LOCATION),
            )
            tags.toSection()?.let(sections::add)

            sections.sortedBy { it.group.ordinal }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Metadata extract can throw for keys the device's extractor doesn't know. */
    private fun metadata(retriever: MediaMetadataRetriever, key: Int): String? = try {
        retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Retriever booleans arrive as the strings `yes`/`no`. */
    private fun yesNo(raw: String?): Int? = when (raw?.lowercase()) {
        "yes", "true", "1" -> R.string.info_yes
        "no", "false", "0" -> R.string.info_no
        else -> null
    }

    /** Above this, a tag is a blob (XMP, maker note), not a value worth a row. */
    private const val MaxVerbatimValueLength = 160

    /**
     * Tags the sheet has no dedicated row for. Curated on purpose: offsets,
     * strip/thumbnail pointers and binary blobs say nothing useful to a person
     * looking at their photo, while these still fill in the rare details
     * (sensing method, gain control, sharpness, …).
     */
    private val ExifDetailTags = listOf(
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_EXPOSURE_INDEX,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_CFA_PATTERN,
        ExifInterface.TAG_COMPONENTS_CONFIGURATION,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION,
        ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
        ExifInterface.TAG_REFERENCE_BLACK_WHITE,
        ExifInterface.TAG_WHITE_POINT,
        ExifInterface.TAG_PRIMARY_CHROMATICITIES,
        ExifInterface.TAG_TRANSFER_FUNCTION,
        ExifInterface.TAG_GAMMA,
        ExifInterface.TAG_FLASHPIX_VERSION,
        ExifInterface.TAG_EXIF_VERSION,
        ExifInterface.TAG_INTEROPERABILITY_INDEX,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SATELLITES,
    )
}
