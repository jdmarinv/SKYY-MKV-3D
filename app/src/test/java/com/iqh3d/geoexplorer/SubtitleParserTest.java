package com.iqh3d.geoexplorer;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class SubtitleParserTest {

    @Test
    public void testSrtParsingAndLookup() {
        String srt = "1\n" +
                "00:00:01,000 --> 00:00:04,000\n" +
                "¡Hola mundo 3D!\n\n" +
                "2\n" +
                "00:00:05,500 --> 00:00:08,200\n" +
                "<i>Película en Half Side-by-Side</i>\n\n" +
                "3\n" +
                "00:00:10,000 --> 00:00:15,000\n" +
                "Fin de la prueba\n";

        byte[] bytes = srt.getBytes(StandardCharsets.UTF_8);
        SubtitleParser.SubtitleTrack track = SubtitleParser.parseBytes(bytes, bytes.length, "test.srt");

        Assert.assertNotNull(track);
        Assert.assertEquals(3, track.cues.size());

        // Antes del primer cue
        Assert.assertNull(track.getCueAt(500, 0));

        // Durante el primer cue
        SubtitleParser.Cue cue1 = track.getCueAt(2000, 0);
        Assert.assertNotNull(cue1);
        Assert.assertTrue(cue1.text.toString().contains("¡Hola mundo 3D!"));

        // Entre el primer y segundo cue
        Assert.assertNull(track.getCueAt(4500, 0));

        // Durante el segundo cue
        SubtitleParser.Cue cue2 = track.getCueAt(6000, 0);
        Assert.assertNotNull(cue2);
        Assert.assertTrue(cue2.text.toString().contains("Película en Half Side-by-Side"));

        // Durante el tercer cue
        SubtitleParser.Cue cue3 = track.getCueAt(12000, 0);
        Assert.assertNotNull(cue3);
        Assert.assertTrue(cue3.text.toString().contains("Fin de la prueba"));

        // Prueba con desfase (sync offset) de +1000ms (el cue aparece 1s más tarde)
        SubtitleParser.Cue cueWithOffset = track.getCueAt(3000, 1000);
        Assert.assertNotNull(cueWithOffset);
        Assert.assertEquals(cue1.startTimeMs, cueWithOffset.startTimeMs);
    }

    @Test
    public void testVttParsing() {
        String vtt = "WEBVTT\n\n" +
                "00:01.000 --> 00:03.500\n" +
                "Subtítulo WebVTT corto\n\n" +
                "01:02:03.100 --> 01:02:06.400\n" +
                "Subtítulo con horas\n";

        byte[] bytes = vtt.getBytes(StandardCharsets.UTF_8);
        SubtitleParser.SubtitleTrack track = SubtitleParser.parseBytes(bytes, bytes.length, "test.vtt");

        Assert.assertNotNull(track);
        Assert.assertEquals(2, track.cues.size());

        SubtitleParser.Cue cue1 = track.getCueAt(2000, 0);
        Assert.assertNotNull(cue1);
        Assert.assertTrue(cue1.text.toString().contains("WebVTT corto"));

        SubtitleParser.Cue cue2 = track.getCueAt(3724000, 0);
        Assert.assertNotNull(cue2);
        Assert.assertTrue(cue2.text.toString().contains("Subtítulo con horas"));
    }

    @Test
    public void testGenerateSbsAssFile() throws Exception {
        String srt = "1\n00:00:01,000 --> 00:00:04,000\nSubtítulo 3D de prueba con una frase un poco más larga para validar el wrapping automático\n";
        byte[] bytes = srt.getBytes(StandardCharsets.UTF_8);
        SubtitleParser.SubtitleTrack track = SubtitleParser.parseBytes(bytes, bytes.length, "test.srt");
        Assert.assertNotNull(track);

        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "sbs_test_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        tempDir.mkdirs();

        java.io.File assFile = SubtitleParser.generateSbsAssFile(tempDir, track, 1920, 1080, 16);
        Assert.assertNotNull(assFile);
        Assert.assertTrue(assFile.exists());
        Assert.assertTrue(assFile.length() > 0);

        String content = new String(java.nio.file.Files.readAllBytes(assFile.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(content.contains("PlayResX: 1920"));
        Assert.assertTrue(content.contains("PlayResY: 1080"));
        Assert.assertTrue(content.contains("ScaleX, ScaleY"));
        // Parallax offset = 16:
        // halfWidth = 960. leftCenterX = 480 + 16 = 496. rightCenterX = 960 + 480 - 16 = 1424.
        Assert.assertTrue(content.contains("{\\pos(496,"));
        Assert.assertTrue(content.contains("{\\pos(1424,"));
        // Wrap automático con \\N
        Assert.assertTrue(content.contains("\\N"));

        // Limpieza
        assFile.delete();
        tempDir.delete();
    }

    @Test
    public void testMatchingSubtitleDoesNotFalsePositiveOnTron() {
        java.util.List<SmbBrowserDialog.SmbEntry> videoEntries = new java.util.ArrayList<>();
        videoEntries.add(new SmbBrowserDialog.SmbEntry("Avatar 3D_HSBS_tablet.mkv", "smb://share/Avatar 3D_HSBS_tablet.mkv", false));
        videoEntries.add(new SmbBrowserDialog.SmbEntry("Gravity 3D_HSBS_tablet.mkv", "smb://share/Gravity 3D_HSBS_tablet.mkv", false));
        videoEntries.add(new SmbBrowserDialog.SmbEntry("TRON ARES IMAX 3D_HSBS_tablet.mkv", "smb://share/TRON ARES IMAX 3D_HSBS_tablet.mkv", false));

        java.util.List<SmbBrowserDialog.SmbEntry> subEntries = new java.util.ArrayList<>();
        subEntries.add(new SmbBrowserDialog.SmbEntry("TRON ARES IMAX 3D_HSBS_tablet.srt", "smb://share/TRON ARES IMAX 3D_HSBS_tablet.srt", false));

        // 1. Para Avatar: NO debe coincidir con Tron Ares
        SmbBrowserDialog.SmbEntry matchAvatar = SmbBrowserDialog.findBestMatchingSubtitle("Avatar 3D_HSBS_tablet.mkv", videoEntries, subEntries);
        Assert.assertNull(matchAvatar);

        // 2. Para Gravity: NO debe coincidir con Tron Ares
        SmbBrowserDialog.SmbEntry matchGravity = SmbBrowserDialog.findBestMatchingSubtitle("Gravity 3D_HSBS_tablet.mkv", videoEntries, subEntries);
        Assert.assertNull(matchGravity);

        // 3. Para Tron Ares: SÍ debe coincidir exactamente
        SmbBrowserDialog.SmbEntry matchTron = SmbBrowserDialog.findBestMatchingSubtitle("TRON ARES IMAX 3D_HSBS_tablet.mkv", videoEntries, subEntries);
        Assert.assertNotNull(matchTron);
        Assert.assertEquals("TRON ARES IMAX 3D_HSBS_tablet.srt", matchTron.name);
    }
}
