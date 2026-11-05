package com.ikko.aura.core.audio.dsp

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    // ---------------------------------------------------------------------------------------------------------------
    // AutoEq

    private val sennheiser = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 2512 Hz Gain -2.1 dB Q 1.41
        Filter 3: ON PK Fc 5100 Hz Gain 3.9 dB Q 3.62
        Filter 4: OFF PK Fc 8000 Hz Gain 1.0 dB Q 1.00
        Filter 5: ON HSC Fc 10000 Hz Gain -3.4 dB Q 0.70
    """.trimIndent()

    @Test
    fun `an AutoEq ParametricEQ file reads into Fc, Q and gain`() {
        val result = AutoEqParser.parse(sennheiser)!!
        assertEquals(-6.4, result.preampDb, 1e-9)
        assertEquals(4, result.filters.size)
        assertEquals(1, result.skipped) // the OFF line
        assertEquals(FilterSpec(FilterType.LOW_SHELF, 105.0, 0.70, 6.4), result.filters[0])
        assertEquals(FilterSpec(FilterType.PEAKING, 2512.0, 1.41, -2.1), result.filters[1])
        assertEquals(FilterSpec(FilterType.HIGH_SHELF, 10_000.0, 0.70, -3.4), result.filters[3])
    }

    @Test
    fun `what AutoEq writes is read back the same`() {
        val first = AutoEqParser.parse(sennheiser)!!
        val again = AutoEqParser.parse(AutoEqParser.format(first.preampDb, first.filters))!!
        assertEquals(first.preampDb, again.preampDb, 0.05)
        assertEquals(first.filters.size, again.filters.size)
        for (i in first.filters.indices) {
            assertEquals(first.filters[i].type, again.filters[i].type)
            assertEquals(first.filters[i].frequencyHz, again.filters[i].frequencyHz, 0.5)
            assertEquals(first.filters[i].gainDb, again.filters[i].gainDb, 0.05)
            assertEquals(first.filters[i].q, again.filters[i].q, 0.005)
        }
    }

    @Test
    fun `text that is not an AutoEq profile is refused`() {
        assertNull(AutoEqParser.parse(""))
        assertNull(AutoEqParser.parse("Preamp: -3 dB\nhello"))
        // An unknown filter type is skipped, not misread.
        val r = AutoEqParser.parse("Filter 1: ON LP Fc 100 Hz\nFilter 2: ON PK Fc 1000 Hz Gain 2 dB Q 1")!!
        assertEquals(1, r.filters.size)
        assertEquals(1, r.skipped)
    }

    @Test
    fun `an AutoEq curve runs through the engine and stays stable`() {
        val result = AutoEqParser.parse(sennheiser)!!
        val engine = DspEngine(DspParameters(DspConfig(bypass = false, preampDb = result.preampDb, filters = result.filters)))
        engine.configure(44_100, 2)
        val noise = DspTestSignals.whiteNoise(1f, 44_100 * 2, 2)
        DspTestSignals.processInChunks(engine, noise, 2)
        assertTrue(DspTestSignals.allFinite(noise))
        assertTrue(DspTestSignals.peak(noise) <= 1f)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Presets and the automatic preamp

    @Test
    fun `the six built-in presets are there, ten bands each, inside the sliders' range`() {
        val names = BuiltInProfiles.all.map { it.name }
        assertEquals(listOf("Neutre", "Basses renforcées", "Voix", "Aigus renforcés", "Électronique", "Rock"), names)
        for (p in BuiltInProfiles.all) {
            assertEquals(IsoBands.COUNT, p.bandGainsDb.size)
            assertTrue(p.bandGainsDb.all { it in -IsoBands.MAX_GAIN_DB..IsoBands.MAX_GAIN_DB })
            assertTrue(p.builtIn)
        }
        assertTrue(BuiltInProfiles.byId(BuiltInProfiles.FLAT_ID)!!.bandGainsDb.all { it == 0f })
    }

    @Test
    fun `the automatic preamp takes away the curve's highest boost`() {
        assertEquals(0.0, DspConfig(filters = DspTestSignals.allBands(0f)).headroomDb, 0.0)
        assertEquals(0.0, DspConfig(filters = DspTestSignals.allBands(-6f)).headroomDb, 0.0)
        val bass = DspConfig(filters = BuiltInProfiles.all[1].bandGainsDb.indices.map { IsoBands.spec(it, BuiltInProfiles.all[1].bandGainsDb[it]) })
        // Overlapping bands add up: the peak is above the highest single band, and it is all taken back.
        assertTrue("headroom ${bass.headroomDb}", bass.headroomDb < -6.0 && bass.headroomDb > -15.0)
        assertEquals(0.0, bass.copy(autoPreamp = false).headroomDb, 0.0)
        assertEquals(bass.headroomDb - 2.0, bass.copy(preampDb = -2.0).totalPreampDb, 1e-9)
    }

    @Test
    fun `the response the screen draws matches each band's own filter`() {
        val response = EqResponse(points = 64)
        val out = FloatArray(64)
        response.computeGraphic(FloatArray(IsoBands.COUNT), out)
        assertTrue(out.all { it == 0f })
        val gains = FloatArray(IsoBands.COUNT).also { it[5] = 6f }
        response.computeGraphic(gains, out)
        val c = DoubleArray(Biquad.STRIDE)
        Biquad.design(IsoBands.spec(5, 6f), EqResponse.REFERENCE_RATE, c, 0)
        for (i in 0 until 64) {
            assertEquals(Biquad.magnitudeDb(c, 0, response.frequencies[i], EqResponse.REFERENCE_RATE), out[i].toDouble(), 1e-3)
        }
    }

    @Test
    fun `settings turn into the engine's config`() {
        val s = DspSettings(enabled = true, bandGainsDb = List(10) { it.toFloat() }, normalizationEnabled = true, targetLufs = -16f)
        val c = s.toConfig()
        assertTrue(!c.bypass)
        assertEquals(10, c.filters.size)
        assertEquals(9.0, c.filters[9].gainDb, 0.0)
        assertEquals(16_000.0, c.filters[9].frequencyHz, 0.0)
        assertEquals(-16.0, c.targetLufs, 0.0)
        val parametric = s.copy(parametric = listOf(FilterSpec(FilterType.PEAKING, 1000.0, 1.0, 3.0))).toConfig()
        assertEquals(1, parametric.filters.size)
        assertTrue(DspSettings().toConfig().bypass)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ReplayGain tags

    @Test
    fun `ReplayGain and R128 tags are read from every container's metadata`() {
        val flac = Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-7.89 dB"), VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-9.00 dB"))
        assertEquals(TrackGain(-7.89, -18.0), ReplayGainReader.read(flac))

        val mp3 = Metadata(TextInformationFrame("TXXX", "replaygain_track_gain", listOf("+2.10 dB")))
        assertEquals(TrackGain(2.10, -18.0), ReplayGainReader.read(mp3))

        val m4a = Metadata(InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-3.5 dB"))
        assertEquals(TrackGain(-3.5, -18.0), ReplayGainReader.read(m4a))

        val opus = Metadata(VorbisComment("R128_TRACK_GAIN", "-1280"))
        assertEquals(TrackGain(-5.0, -23.0), ReplayGainReader.read(opus))

        assertNull(ReplayGainReader.read(Metadata(VorbisComment("ARTIST", "x"))))
        assertNull(ReplayGainReader.read(null))
    }

    @Test
    fun `broken tag values are ignored`() {
        assertNull(ReplayGainReader.parseDb("loud"))
        assertNull(ReplayGainReader.parseDb("-120 dB"))
        assertEquals(-6.54, ReplayGainReader.parseDb(" -6,54 dB ")!!, 1e-9)
        assertNull(ReplayGainReader.parseQ78("99999"))
        assertEquals(1.0, ReplayGainReader.parseQ78("256")!!, 0.0)
    }
}
