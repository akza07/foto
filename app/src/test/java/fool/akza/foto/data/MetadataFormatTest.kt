package fool.akza.foto.data

import androidx.exifinterface.media.ExifInterface
import fool.akza.foto.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The info sheet's formatting layer is pure on purpose: every value the sheet
 * prints is derived here from raw EXIF strings, so it can be checked without a
 * device or a ContentResolver.
 */
class MetadataFormatTest {

    private lateinit var defaultLocale: Locale

    @Before
    fun pinLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    // ---- parseRational ----------------------------------------------------

    @Test
    fun `rationals parse as fractions and decimals`() {
        assertEquals(0.004, parseRational("1/250")!!, 1e-9)
        assertEquals(0.008, parseRational("0.008")!!, 1e-9)
    }

    @Test
    fun `malformed rationals are rejected`() {
        assertNull(parseRational(null))
        assertNull(parseRational(""))
        assertNull(parseRational("abc"))
        assertNull(parseRational("1/0"))
        assertNull(parseRational("/4"))
    }

    // ---- exposure ---------------------------------------------------------

    @Test
    fun `fast exposures stay as one over n`() {
        assertEquals("1/250 s", formatExposureTime("1/250"))
        assertEquals("1/125 s", formatExposureTime("0.008"))
        assertEquals("1/3 s", formatExposureTime("1/3"))
    }

    @Test
    fun `long exposures use seconds`() {
        assertEquals("2 s", formatExposureTime("2/1"))
        assertEquals("2.5 s", formatExposureTime("5/2"))
        assertEquals("0.3 s", formatExposureTime("3/10"))
    }

    @Test
    fun `missing or zero exposure is absent`() {
        assertNull(formatExposureTime(null))
        assertNull(formatExposureTime("0/1"))
    }

    @Test
    fun `aperture keeps one decimal`() {
        assertEquals("f/1.8", formatAperture("18/10"))
        assertEquals("f/2", formatAperture("2/1"))
        assertNull(formatAperture(null))
        assertNull(formatAperture("0/1"))
    }

    @Test
    fun `focal length is millimetres`() {
        assertEquals("4.3 mm", formatMillimetres("43/10"))
        assertEquals("26 mm", formatMillimetres("26"))
        assertNull(formatMillimetres("0/1"))
    }

    @Test
    fun `exposure bias carries its sign`() {
        assertEquals("+0.3 EV", formatExposureBias("3/10"))
        assertEquals("-0.3 EV", formatExposureBias("-3/10"))
        assertEquals("0 EV", formatExposureBias("0/1"))
        // Below the printed precision the sign and decimals are noise.
        assertEquals("0 EV", formatExposureBias("1/10000"))
        assertNull(formatExposureBias(null))
    }

    @Test
    fun `digital zoom hides the no-zoom default`() {
        assertNull(formatDigitalZoom("1/1"))
        assertNull(formatDigitalZoom("0/1"))
        assertEquals("2.5×", formatDigitalZoom("5/2"))
    }

    @Test
    fun `subject distance handles the unknown sentinel`() {
        assertNull(formatSubjectDistance("0/1"))
        assertNull(formatSubjectDistance("4294967295/1"))
        assertEquals("1.5 m", formatSubjectDistance("150/100"))
        assertEquals("1.5 km", formatSubjectDistance("1500"))
    }

    // ---- location ---------------------------------------------------------

    @Test
    fun `coordinates print as degrees minutes seconds`() {
        assertEquals("37° 46' 29.6\" N", formatCoordinate(37.7749, isLatitude = true))
        assertEquals("122° 25' 9.8\" W", formatCoordinate(-122.4194, isLatitude = false))
        assertEquals("33° 51' 35.9\" S", formatCoordinate(-33.859972, isLatitude = true))
    }

    @Test
    fun `rounded seconds carry into minutes`() {
        assertEquals("46° 0' 0\" N", formatCoordinate(45.9999999999, isLatitude = true))
    }

    @Test
    fun `altitude keeps one decimal`() {
        assertEquals("12.5 m", formatAltitude(12.5))
    }

    // ---- video ------------------------------------------------------------

    @Test
    fun `durations show hours only when needed`() {
        assertEquals("1:03", formatDurationMs(63_000))
        assertEquals("1:02:03", formatDurationMs(3_723_000))
        assertNull(formatDurationMs(0))
    }

    @Test
    fun `bitrates switch unit at one megabit`() {
        assertEquals("12.4 Mbps", formatBitrate(12_400_000))
        assertEquals("850 kbps", formatBitrate(850_000))
        // Rounds up into the next unit instead of printing "1000 kbps".
        assertEquals("1 Mbps", formatBitrate(999_999))
        assertNull(formatBitrate(0))
    }

    @Test
    fun `frame rate keeps two decimals`() {
        assertEquals("29.97 fps", formatFrameRate("29.97"))
        assertEquals("60 fps", formatFrameRate("60"))
        assertNull(formatFrameRate("0"))
        assertNull(formatFrameRate(null))
    }

    @Test
    fun `sample rate uses kHz for whole kilohertz`() {
        assertEquals("48 kHz", formatSampleRate(48_000))
        assertEquals("44100 Hz", formatSampleRate(44_100))
        assertNull(formatSampleRate(0))
    }

    // ---- dates and tags ---------------------------------------------------

    @Test
    fun `exif datetimes become readable dates`() {
        assertEquals("May 1, 2024, 12:34 PM", formatExifDateTime("2024:05:01 12:34:56"))
        assertEquals("May 1, 2024", formatExifDateTime("2024:05:01"))
        assertEquals("May 1, 2024, 12:34 PM", formatExifDateTime("2024-05-01T12:34:56"))
        // Zone offsets and subseconds are dropped, not turned into a wrong hour:
        // EXIF local time is what the info sheet reports.
        assertEquals("May 1, 2024, 12:34 PM", formatExifDateTime("2024-05-01T12:34:56+02:00"))
    }

    @Test
    fun `unparseable dates are shown verbatim`() {
        assertEquals("yesterday", formatExifDateTime("yesterday"))
        assertEquals("00:00:00", formatExifDateTime("00:00:00"))
        assertNull(formatExifDateTime(null))
        assertNull(formatExifDateTime("  "))
    }

    @Test
    fun `container dates drop the quicktime placeholder`() {
        assertEquals("Feb 14, 2026, 6:31 PM", formatContainerDate("2026:02:14 18:31:07"))
        assertNull(formatContainerDate("19040101T000000.000Z"))
        assertNull(formatContainerDate(null))
    }

    @Test
    fun `year keeps only the year when a full stamp is given`() {
        assertEquals("2026", formatYear("2026:02:14 18:31:07"))
        assertEquals("1999", formatYear("1999"))
        assertNull(formatYear("  "))
    }

    @Test
    fun `tag names are humanized for display`() {
        assertEquals("ISO Speed Ratings", humanizeExifTag("ISOSpeedRatings"))
        assertEquals("XP Title", humanizeExifTag("XPTitle"))
        assertEquals("F Number", humanizeExifTag("FNumber"))
        assertEquals("GPS Latitude", humanizeExifTag("GPSLatitude"))
        assertEquals("Date Time Original", humanizeExifTag("DateTimeOriginal"))
    }

    // ---- enumerated values ------------------------------------------------

    @Test
    fun `orientation maps every defined value`() {
        assertEquals(R.string.info_orientation_normal, orientationLabelRes(1))
        assertEquals(R.string.info_orientation_rotate_90, orientationLabelRes(6))
        assertEquals(R.string.info_orientation_rotate_270, orientationLabelRes(8))
        assertNull(orientationLabelRes(0))
        assertNull(orientationLabelRes(9))
    }

    @Test
    fun `exposure program and mode map known values`() {
        assertEquals(R.string.info_program_normal, exposureProgramLabelRes(2))
        assertEquals(R.string.info_program_aperture, exposureProgramLabelRes(3))
        assertNull(exposureProgramLabelRes(42))
        assertEquals(R.string.info_exposure_mode_auto, exposureModeLabelRes(0))
        assertEquals(R.string.info_exposure_mode_bracket, exposureModeLabelRes(2))
        assertNull(exposureModeLabelRes(-1))
    }

    @Test
    fun `metering and white balance map known values`() {
        assertEquals(R.string.info_metering_spot, meteringLabelRes(3))
        assertEquals(R.string.info_metering_other, meteringLabelRes(255))
        assertNull(meteringLabelRes(9))
        assertEquals(R.string.info_white_balance_auto, whiteBalanceLabelRes(0))
        assertEquals(R.string.info_white_balance_manual, whiteBalanceLabelRes(1))
    }

    @Test
    fun `flash bit field reports fired and unavailable`() {
        assertEquals(R.string.info_flash_not_fired, flashLabelRes(0))
        assertEquals(R.string.info_flash_fired, flashLabelRes(0x19))
        assertEquals(R.string.info_flash_unavailable, flashLabelRes(0x20))
        assertNull(flashLabelRes(-1))
    }

    @Test
    fun `tail tags get wording when they are enumerations`() {
        assertEquals(R.string.info_normal, exifTagValueRes(ExifInterface.TAG_CONTRAST, "0"))
        assertEquals(R.string.info_high_saturation, exifTagValueRes(ExifInterface.TAG_SATURATION, "2"))
        assertEquals(R.string.info_soft, exifTagValueRes(ExifInterface.TAG_SHARPNESS, "1"))
        assertEquals(R.string.info_daylight, exifTagValueRes(ExifInterface.TAG_LIGHT_SOURCE, "1"))
        assertEquals(R.string.info_unknown, exifTagValueRes(ExifInterface.TAG_LIGHT_SOURCE, "0"))
        assertNull(exifTagValueRes(ExifInterface.TAG_LIGHT_SOURCE, "42"))
        assertNull(exifTagValueRes(ExifInterface.TAG_USER_COMMENT, "hello"))
        assertNull(exifTagValueRes(ExifInterface.TAG_APERTURE_VALUE, "37/10"))
    }

    @Test
    fun `scene capture and color space map known values`() {
        assertEquals(R.string.info_scene_night, sceneCaptureLabelRes(3))
        assertNull(sceneCaptureLabelRes(7))
        assertEquals(R.string.info_color_space_srgb, colorSpaceLabelRes(1))
        assertEquals(R.string.info_color_space_uncalibrated, colorSpaceLabelRes(0xFFFF))
        assertNull(colorSpaceLabelRes(2))
    }
}
