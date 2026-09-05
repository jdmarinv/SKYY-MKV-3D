package com.iqh3d.geoexplorer;

import android.text.Html;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de alto rendimiento para subtítulos SubRip (.srt) y WebVTT (.vtt).
 * Detecta codificaciones comunes (UTF-8, UTF-16, Windows-1252 / ISO-8859-1)
 * para evitar mojibake en caracteres en español y soporta etiquetas HTML de estilo.
 */
public final class SubtitleParser {
    private static final String TAG = "SkyySubParser";

    public static final class Cue {
        public final long startTimeMs;
        public final long endTimeMs;
        public final CharSequence text;

        public Cue(long startTimeMs, long endTimeMs, CharSequence text) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.text = text;
        }

        @Override
        public String toString() {
            return "[" + startTimeMs + "ms -> " + endTimeMs + "ms]: " + text;
        }
    }

    public static final class SubtitleTrack {
        public final String name;
        public final List<Cue> cues;

        public SubtitleTrack(String name, List<Cue> cues) {
            this.name = name;
            this.cues = cues != null ? cues : Collections.emptyList();
        }

        public Cue getCueAt(long timeMs, long syncOffsetMs) {
            if (cues.isEmpty()) return null;
            long adjustedTime = timeMs - syncOffsetMs;
            if (adjustedTime < 0) return null;

            int low = 0;
            int high = cues.size() - 1;

            while (low <= high) {
                int mid = (low + high) >>> 1;
                Cue cue = cues.get(mid);
                if (adjustedTime < cue.startTimeMs) {
                    high = mid - 1;
                } else if (adjustedTime > cue.endTimeMs) {
                    low = mid + 1;
                } else {
                    return cue;
                }
            }

            // Si hay solapamientos pequeños, buscar en el entorno inmediato
            if (low < cues.size()) {
                Cue next = cues.get(low);
                if (adjustedTime >= next.startTimeMs && adjustedTime <= next.endTimeMs) {
                    return next;
                }
            }
            if (high >= 0 && high < cues.size()) {
                Cue prev = cues.get(high);
                if (adjustedTime >= prev.startTimeMs && adjustedTime <= prev.endTimeMs) {
                    return prev;
                }
            }

            return null;
        }
    }

    // Patrón para timestamps SRT: 00:01:23,456 --> 00:01:26,789
    // Patrón para timestamps VTT: 00:01:23.456 --> 00:01:26.789 o 01:23.456 --> 01:26.789
    private static final Pattern TIMING_PATTERN = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[\\.,](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[\\.,](\\d{3})");

    private SubtitleParser() {}

    public static File generateSbsAssFile(File cacheDir, SubtitleTrack track, int videoWidth, int videoHeight, int parallaxOffsetPx) {
        return generateSbsAssFile(cacheDir, track, videoWidth, videoHeight, parallaxOffsetPx, 20f);
    }

    /**
     * Genera un archivo de subtítulos en formato ASS (.ass) con duplicación estereoscópica Side-by-Side
     * para que LibVLC lo renderice directamente sobre el buffer del SurfaceView de video.
     * De esta forma, el procesador lenticular de hardware 3DFV lo entrelaza en 3D real.
     */
    public static File generateSbsAssFile(File cacheDir, SubtitleTrack track, int videoWidth, int videoHeight, int parallaxOffsetPx, float textSizeSp) {
        if (track == null || track.cues.isEmpty() || cacheDir == null) return null;
        int w = videoWidth > 0 ? videoWidth : 1920;
        int h = videoHeight > 0 ? videoHeight : 1080;

        // Limpiar archivos anteriores generados
        try {
            File[] oldAss = cacheDir.listFiles((dir, name) -> name.startsWith("stereo_sbs_") && name.endsWith(".ass"));
            if (oldAss != null) {
                for (File f : oldAss) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {}

        File assFile = new File(cacheDir, "stereo_sbs_" + System.currentTimeMillis() + ".ass");
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(assFile), StandardCharsets.UTF_8))) {
            writer.write("[Script Info]\r\n");
            writer.write("ScriptType: v4.00+\r\n");
            writer.write("PlayResX: " + w + "\r\n");
            writer.write("PlayResY: " + h + "\r\n");
            writer.write("WrapStyle: 0\r\n\r\n");

            writer.write("[V4+ Styles]\r\n");
            writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\r\n");
            float sp = (textSizeSp > 0) ? textSizeSp : 20f;
            int fontSize = Math.max(26, (int) (h * (sp / 480f)));
            // En Half-SBS (ancho <= 2048), la imagen contiene 2 ojos lado a lado comprimidos anamórficamente al 50%.
            // El procesador 3DFV y la barrera lenticular de la tableta expanden cada ojo 2x horizontalmente.
            // Con ScaleX: 50, se compensa exactamente dicha expansión 2x, logrando que las letras se vean
            // completamente proporcionadas (1:1) y sin ningún achatamiento horizontal.
            int scaleX = (w <= 2048) ? 50 : 100;
            writer.write("Style: Default,Arial," + fontSize + ",&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0," + scaleX + ",100,0,0,1,2,1,2,10,10,35,1\r\n\r\n");

            writer.write("[Events]\r\n");
            writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n");

            int halfWidth = w / 2;
            int y = (int) (h * 0.90f);
            int leftX = (halfWidth / 2) + parallaxOffsetPx;
            int rightX = (halfWidth + halfWidth / 2) - parallaxOffsetPx;

            for (Cue cue : track.cues) {
                String start = formatAssTime(cue.startTimeMs);
                String end = formatAssTime(cue.endTimeMs);
                String text = wrapAssText(cue.text.toString().replace("\r", ""), 42);

                // Evento Ojo Izquierdo
                writer.write("Dialogue: 0," + start + "," + end + ",Default,,0,0,0,,{\\pos(" + leftX + "," + y + ")}" + text + "\r\n");
                // Evento Ojo Derecho
                writer.write("Dialogue: 0," + start + "," + end + ",Default,,0,0,0,,{\\pos(" + rightX + "," + y + ")}" + text + "\r\n");
            }
            writer.flush();
            return assFile;
        } catch (Exception e) {
            logError("Error generando archivo ASS SBS estereoscópico", e);
            return null;
        }
    }

    private static String wrapAssText(String text, int maxCharsPerLine) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.length() <= maxCharsPerLine) {
                if (sb.length() > 0) sb.append("\\N");
                sb.append(line);
            } else {
                String[] words = line.split("\\s+");
                StringBuilder cur = new StringBuilder();
                for (String w : words) {
                    if (cur.length() + w.length() + 1 > maxCharsPerLine && cur.length() > 0) {
                        if (sb.length() > 0) sb.append("\\N");
                        sb.append(cur);
                        cur.setLength(0);
                    }
                    if (cur.length() > 0) cur.append(" ");
                    cur.append(w);
                }
                if (cur.length() > 0) {
                    if (sb.length() > 0 && !sb.toString().endsWith("\\N")) sb.append("\\N");
                    sb.append(cur);
                }
            }
        }
        return sb.toString();
    }

    private static String formatAssTime(long ms) {
        long h = ms / 3600000L;
        long m = (ms % 3600000L) / 60000L;
        long s = (ms % 60000L) / 1000L;
        long cs = (ms % 1000L) / 10L;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
    }

    public static SubtitleTrack parseFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 10 * 1024 * 1024)]; // máx 10 MB
            int read = fis.read(data);
            if (read <= 0) return null;
            return parseBytes(data, read, file.getName());
        } catch (Exception e) {
            logError("Error leyendo archivo de subtítulo: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static SubtitleTrack parseStream(InputStream is, String trackName) {
        try {
            byte[] buffer = new byte[64 * 1024];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = is.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
            }
            byte[] data = baos.toByteArray();
            return parseBytes(data, data.length, trackName);
        } catch (Exception e) {
            logError("Error parseando stream de subtítulo " + trackName, e);
            return null;
        }
    }

    public static SubtitleTrack parseBytes(byte[] data, int length, String trackName) {
        if (data == null || length <= 0) return null;

        Charset charset = detectCharset(data, length);
        int offset = 0;

        // Saltar UTF-8 BOM si existe (EF BB BF)
        if (length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            offset = 3;
        }

        List<Cue> cues = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data, offset, length - offset), charset))) {

            String line;
            long currentStart = -1;
            long currentEnd = -1;
            StringBuilder textBuilder = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Ignorar encabezado WebVTT
                if (line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                    continue;
                }

                Matcher matcher = TIMING_PATTERN.matcher(line);
                if (matcher.find()) {
                    // Si ya teníamos un cue acumulado, guardarlo
                    if (currentStart >= 0 && currentEnd >= currentStart && textBuilder.length() > 0) {
                        cues.add(new Cue(currentStart, currentEnd, formatSubtitleText(textBuilder.toString())));
                    }
                    textBuilder.setLength(0);

                    currentStart = parseTime(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
                    currentEnd = parseTime(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
                    continue;
                }

                // Línea vacía indica fin del cue actual
                if (line.isEmpty()) {
                    if (currentStart >= 0 && currentEnd >= currentStart && textBuilder.length() > 0) {
                        cues.add(new Cue(currentStart, currentEnd, formatSubtitleText(textBuilder.toString())));
                        currentStart = -1;
                        currentEnd = -1;
                        textBuilder.setLength(0);
                    }
                    continue;
                }

                // Ignorar números de secuencia simples en SRT si no tenemos un timestamp activo
                if (currentStart == -1 && line.matches("^\\d+$")) {
                    continue;
                }

                // Acumular texto del subtítulo
                if (currentStart >= 0) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append("\n");
                    }
                    textBuilder.append(line);
                }
            }

            // Último cue
            if (currentStart >= 0 && currentEnd >= currentStart && textBuilder.length() > 0) {
                cues.add(new Cue(currentStart, currentEnd, formatSubtitleText(textBuilder.toString())));
            }

        } catch (Exception e) {
            logError("Error procesando líneas de subtítulo", e);
        }

        // Ordenar cues por tiempo de inicio
        Collections.sort(cues, Comparator.comparingLong(c -> c.startTimeMs));

        logInfo("Subtítulo cargado: " + trackName + " con " + cues.size() + " entradas (Codificación: " + charset.name() + ")");
        return new SubtitleTrack(trackName, cues);
    }

    private static CharSequence formatSubtitleText(String rawText) {
        if (rawText == null || rawText.isEmpty()) return "";
        // Convertir etiquetas de estilo HTML
        String clean = rawText
                .replaceAll("\\{\\\\an\\d\\}", "") // Quitar directivas de posicionamiento ASS en SRT
                .replaceAll("\\{\\\\[^}]+\\}", "");
        try {
            CharSequence spanned = Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY);
            if (spanned != null && spanned.length() > 0) {
                return spanned;
            }
        } catch (Throwable fallback) {
            // Ignorar excepciones en entornos sin Android framework nativo (tests JVM)
        }
        return clean.replaceAll("<[^>]*>", "");
    }

    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable t) {
            System.out.println(TAG + ": " + message);
        }
    }

    private static void logError(String message, Throwable err) {
        try {
            Log.e(TAG, message, err);
        } catch (Throwable t) {
            System.err.println(TAG + ": " + message);
            if (err != null) err.printStackTrace();
        }
    }

    private static long parseTime(String hStr, String mStr, String sStr, String msStr) {
        long hours = hStr != null ? Long.parseLong(hStr) : 0L;
        long minutes = Long.parseLong(mStr);
        long seconds = Long.parseLong(sStr);
        long millis = Long.parseLong(msStr);
        return (hours * 3600L + minutes * 60L + seconds) * 1000L + millis;
    }

    private static Charset detectCharset(byte[] data, int length) {
        // Verificar BOM UTF-8
        if (length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        // Verificar BOM UTF-16LE
        if (length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        // Verificar BOM UTF-16BE
        if (length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        // Probar si es UTF-8 válido
        if (isValidUtf8(data, length)) {
            return StandardCharsets.UTF_8;
        }

        // Si contiene secuencias inválidas para UTF-8 (muy común en subtítulos en español de España/LatAm en ISO-8859-1 o Windows-1252):
        try {
            return Charset.forName("windows-1252");
        } catch (Exception ignored) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static boolean isValidUtf8(byte[] data, int length) {
        int i = 0;
        while (i < length) {
            int b = data[i++] & 0xFF;
            if (b <= 0x7F) continue;
            int nBytes;
            if ((b & 0xE0) == 0xC0) {
                if (b < 0xC2) return false;
                nBytes = 1;
            } else if ((b & 0xF0) == 0xE0) {
                nBytes = 2;
            } else if ((b & 0xF8) == 0xF0) {
                if (b > 0xF4) return false;
                nBytes = 3;
            } else {
                return false;
            }
            if (i + nBytes > length) return false;
            for (int k = 0; k < nBytes; k++) {
                int cb = data[i++] & 0xFF;
                if ((cb & 0xC0) != 0x80) return false;
            }
        }
        return true;
    }
}
