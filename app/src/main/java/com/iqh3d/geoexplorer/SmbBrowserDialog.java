package com.iqh3d.geoexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SmbBrowserDialog {
    private static final String TAG = "SkyySmbBrowser";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PASSWORD_KEY_ALIAS = "skyy_smb_password_key";
    private static final String PASSWORD_CIPHER = "AES/GCM/NoPadding";
    private static final String PREF_PASSWORD_DATA = "password_data";
    private static final String PREF_PASSWORD_IV = "password_iv";
    private static final String PREF_VIEW_MODE = "smb_view_mode"; // "mosaic" o "list"

    interface Listener {
        void onFileSelected(SmbFile file);
    }

    static final class SmbFile {
        final String host;
        final String share;
        final String path;
        final String username;
        final String domain;
        private final char[] password;
        File cachedSubtitleFile;

        SmbFile(ConnectionInfo connection, String path) {
            this.host = connection.host;
            this.share = connection.share;
            this.path = path;
            this.username = connection.anonymous ? "" : connection.username;
            this.domain = connection.anonymous ? "" : connection.domain;
            this.password = connection.anonymous
                    ? new char[0]
                    : Arrays.copyOf(connection.password, connection.password.length);
        }

        String displayName() {
            int separator = path.lastIndexOf('\\');
            return separator >= 0 ? path.substring(separator + 1) : path;
        }

        Uri uri() {
            StringBuilder value = new StringBuilder("smb://").append(host)
                    .append('/').append(Uri.encode(share));
            if (!path.isEmpty()) {
                for (String segment : path.split("\\\\")) {
                    if (!segment.isEmpty()) {
                        value.append('/').append(Uri.encode(segment));
                    }
                }
            }
            return Uri.parse(value.toString());
        }

        String passwordOption() {
            return new String(password);
        }

        void clearPassword() {
            Arrays.fill(password, '\0');
        }
    }

    private static final String PREFS = "smb_connection";
    private final Activity activity;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    SmbBrowserDialog(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    private void restoreImmersiveMode() {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).enterImmersiveModePublic();
        }
    }

    private void configureDialogImmersive(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.setOnDismissListener(ignored -> restoreImmersiveMode());
        dialog.setOnCancelListener(ignored -> restoreImmersiveMode());
    }

    private void showImmersiveDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().setSystemUiVisibility(
                    activity.getWindow().getDecorView().getSystemUiVisibility());
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    void show() {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);
        int padding = dp(20);
        form.setPadding(padding, dp(8), padding, dp(8));

        EditText server = field(form, "Server or IP (optional :port)",
                preferences.getString("host", ""), false);
        EditText share = field(form, "Shared folder", preferences.getString("share", ""), false);
        EditText path = field(form, "Starting path (optional)", preferences.getString("path", ""), false);
        EditText username = field(form, "Username", preferences.getString("username", ""), false);
        char[] savedPassword = loadPassword(preferences);
        EditText password = field(form, "Password", new String(savedPassword), true);
        Arrays.fill(savedPassword, '\0');
        EditText domain = field(form, "Domain / workgroup", preferences.getString("domain", "WORKGROUP"), false);
        CheckBox anonymous = new CheckBox(activity);
        anonymous.setText("Anonymous access");
        anonymous.setChecked(preferences.getBoolean("anonymous", false));
        form.addView(anonymous);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Connect to SMB")
                .setView(scrollView)
                .setNegativeButton("CANCEL", (ignored, which) -> {
                    executor.shutdownNow();
                    restoreImmersiveMode();
                })
                .setPositiveButton("CONNECT", null)
                .create();

        configureDialogImmersive(dialog);

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String hostValue = normalizeHost(server.getText().toString());
                    String shareValue = trimSlashes(share.getText().toString());
                    if (hostValue.isEmpty() || shareValue.isEmpty()) {
                        Toast.makeText(activity, "Server and shared folder are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    HostAndPort endpoint;
                    try {
                        endpoint = parseEndpoint(hostValue);
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(activity, error.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ConnectionInfo connection = new ConnectionInfo(
                            endpoint,
                            shareValue,
                            normalizePath(path.getText().toString()),
                            username.getText().toString().trim(),
                            password.getText().toString().toCharArray(),
                            domain.getText().toString().trim(),
                            anonymous.isChecked());
                    password.getText().clear();
                    preferences.edit()
                            .putString("host", connection.host)
                            .putString("share", connection.share)
                            .putString("path", connection.path)
                            .putString("username", connection.username)
                            .putString("domain", connection.domain)
                            .putBoolean("anonymous", connection.anonymous)
                            .apply();
                    if (connection.anonymous) {
                        clearSavedPassword(preferences);
                    } else {
                        savePassword(preferences, connection.password);
                    }
                    dialog.dismiss();
                    browse(connection, connection.path);
                }));

        dialog.setOnCancelListener(ignored -> {
            executor.shutdownNow();
            restoreImmersiveMode();
        });

        showImmersiveDialog(dialog);
        form.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    private EditText field(LinearLayout parent, String label, String value, boolean secret) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(0xFF263442);
        title.setPadding(0, dp(8), 0, 0);
        parent.addView(title);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextColor(0xFF101820);
        input.setHintTextColor(0xFF687785);
        if (secret) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_URI
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        parent.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private void savePassword(SharedPreferences preferences, char[] password) {
        if (password.length == 0) {
            clearSavedPassword(preferences);
            return;
        }
        byte[] plaintext = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(PASSWORD_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreatePasswordKey());
            byte[] encrypted = cipher.doFinal(plaintext);
            preferences.edit()
                    .putString(PREF_PASSWORD_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREF_PASSWORD_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException | IOException error) {
            Log.e(TAG, "Could not encrypt the saved SMB password", error);
            clearSavedPassword(preferences);
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "Connected, but the password could not be saved securely", Toast.LENGTH_LONG).show());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private char[] loadPassword(SharedPreferences preferences) {
        String encodedData = preferences.getString(PREF_PASSWORD_DATA, null);
        String encodedIv = preferences.getString(PREF_PASSWORD_IV, null);
        if (encodedData == null || encodedIv == null) {
            return new char[0];
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance(PASSWORD_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreatePasswordKey(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
            plaintext = cipher.doFinal(Base64.decode(encodedData, Base64.NO_WRAP));
            return new String(plaintext, StandardCharsets.UTF_8).toCharArray();
        } catch (GeneralSecurityException | IOException | IllegalArgumentException error) {
            Log.w(TAG, "Saved SMB password could not be decrypted; clearing it", error);
            clearSavedPassword(preferences);
            return new char[0];
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private SecretKey getOrCreatePasswordKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(PASSWORD_KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                PASSWORD_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGenerator.generateKey();
    }

    private void clearSavedPassword(SharedPreferences preferences) {
        preferences.edit()
                .remove(PREF_PASSWORD_DATA)
                .remove(PREF_PASSWORD_IV)
                .apply();
    }

    private void browse(ConnectionInfo connection, String path) {
        ProgressDialog progress = ProgressDialog.show(
                activity, "SMB", "Loading " + locationLabel(connection, path), true, false);
        if (progress.getWindow() != null) {
            progress.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        progress.setOnDismissListener(ignored -> restoreImmersiveMode());

        executor.execute(() -> {
            try {
                BrowseResult result = list(connection, path);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        connection.clearPassword();
                        executor.shutdownNow();
                        return;
                    }
                    progress.dismiss();
                    showMosaicDialog(connection, path, result);
                });
            } catch (Exception error) {
                Log.e(TAG, "SMB browse failed for " + locationLabel(connection, path), error);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        connection.clearPassword();
                        executor.shutdownNow();
                        return;
                    }
                    progress.dismiss();
                    showError(connection, path, error);
                });
            }
        });
    }

    private static final class BrowseResult {
        final List<SmbEntry> entries;
        final List<SmbEntry> subtitleEntries;

        BrowseResult(List<SmbEntry> entries, List<SmbEntry> subtitleEntries) {
            this.entries = entries;
            this.subtitleEntries = subtitleEntries;
        }
    }

    private BrowseResult list(ConnectionInfo info, String path) throws IOException {
        List<SmbEntry> entries = new ArrayList<>();
        List<SmbEntry> subtitles = new ArrayList<>();

        SmbConfig config = info.anonymous
                ? SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1).build()
                : SmbConfig.createDefaultConfig();
        try (SMBClient client = new SMBClient(config);
             Connection connection = client.connect(info.server, info.port)) {
            AuthenticationContext authentication = info.anonymous
                    ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(info.username, info.password, info.domain);
            Session session = connection.authenticate(authentication);
            try (DiskShare share = (DiskShare) session.connectShare(info.share)) {
                for (FileIdBothDirectoryInformation item : share.list(path)) {
                    String name = item.getFileName();
                    if (".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    boolean directory = (item.getFileAttributes()
                            & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                    if (directory || isVideo(name)) {
                        entries.add(new SmbEntry(name, join(path, name), directory));
                    } else if (isSubtitle(name)) {
                        subtitles.add(new SmbEntry(name, join(path, name), false));
                    }
                }
            }
        }
        Collections.sort(entries, Comparator
                .comparing((SmbEntry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name.toLowerCase(Locale.US)));
        return new BrowseResult(entries, subtitles);
    }

    /**
     * Muestra los archivos en modo Mosaico (Grid) o Lista según la preferencia del usuario.
     */
    private void showMosaicDialog(ConnectionInfo connection, String path, BrowseResult result) {
        List<SmbEntry> displayItems = new ArrayList<>();
        boolean hasParent = !path.isEmpty();
        if (hasParent) {
            displayItems.add(new SmbEntry(".. (Carpeta superior)", parent(path), true, true));
        }
        displayItems.addAll(result.entries);

        // Identificar qué videos tienen subtítulos complementarios disponibles mediante matching inteligente
        Set<String> videosWithSubtitles = new HashSet<>();
        for (SmbEntry item : result.entries) {
            if (!item.directory) {
                SmbEntry match = findBestMatchingSubtitle(item.name, result.entries, result.subtitleEntries);
                if (match != null) {
                    videosWithSubtitles.add(item.name);
                }
            }
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean isMosaic = !"list".equals(prefs.getString(PREF_VIEW_MODE, "mosaic"));

        // Vista principal del diálogo
        LinearLayout dialogRoot = new LinearLayout(activity);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackgroundColor(0xFF0C131A);

        // Barra superior con título y controles
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setBackgroundColor(0xFF131F2A);

        TextView title = new TextView(activity);
        title.setText(locationLabel(connection, path));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.START);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView modeToggle = new TextView(activity);
        modeToggle.setText(isMosaic ? "MOSAICO" : "LISTA");
        modeToggle.setTextColor(0xFF54E0C7);
        modeToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        modeToggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        modeToggle.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setColor(0x3354E0C7);
        toggleBg.setCornerRadius(dp(14));
        modeToggle.setBackground(toggleBg);
        header.addView(modeToggle);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFFB0BEC5);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        closeBtn.setPadding(dp(16), dp(4), dp(4), dp(4));
        header.addView(closeBtn);
        dialogRoot.addView(header);

        FrameLayout contentHost = new FrameLayout(activity);
        dialogRoot.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogRoot)
                .create();

        configureDialogImmersive(dialog);

        closeBtn.setOnClickListener(v -> {
            connection.clearPassword();
            executor.shutdownNow();
            dialog.dismiss();
        });

        SmbItemAdapter adapter = new SmbItemAdapter(activity, displayItems, videosWithSubtitles, isMosaic);

        if (isMosaic) {
            GridView gridView = new GridView(activity);
            gridView.setNumColumns(GridView.AUTO_FIT);
            gridView.setColumnWidth(dp(200));
            gridView.setHorizontalSpacing(dp(12));
            gridView.setVerticalSpacing(dp(12));
            gridView.setPadding(dp(16), dp(14), dp(16), dp(16));
            gridView.setClipToPadding(false);
            gridView.setAdapter(adapter);
            gridView.setOnItemClickListener((parent, view, position, id) ->
                    handleItemClick(connection, path, displayItems.get(position), result.entries, result.subtitleEntries, dialog));
            contentHost.addView(gridView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ListView listView = new ListView(activity);
            listView.setPadding(dp(12), dp(8), dp(12), dp(8));
            listView.setDivider(null);
            listView.setDividerHeight(dp(6));
            listView.setAdapter(adapter);
            listView.setOnItemClickListener((parent, view, position, id) ->
                    handleItemClick(connection, path, displayItems.get(position), result.entries, result.subtitleEntries, dialog));
            contentHost.addView(listView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        modeToggle.setOnClickListener(v -> {
            boolean nextMosaic = !adapter.isMosaic;
            prefs.edit().putString(PREF_VIEW_MODE, nextMosaic ? "mosaic" : "list").apply();
            dialog.dismiss();
            showMosaicDialog(connection, path, result);
        });

        dialog.setOnCancelListener(ignored -> {
            connection.clearPassword();
            executor.shutdownNow();
            restoreImmersiveMode();
        });

        showImmersiveDialog(dialog);
    }

    private void handleItemClick(ConnectionInfo connection, String currentPath, SmbEntry item,
                                 List<SmbEntry> videoEntries, List<SmbEntry> subtitleEntries, AlertDialog dialog) {
        if (item.isParent) {
            dialog.dismiss();
            browse(connection, item.path);
            return;
        }
        if (item.directory) {
            dialog.dismiss();
            browse(connection, item.path);
            return;
        }

        // Es un archivo de video seleccionado
        SmbFile file = new SmbFile(connection, item.path);

        // Buscar si existe un subtítulo complementario (.srt / .vtt) mediante coincidencia inteligente
        SmbEntry matchingSub = findBestMatchingSubtitle(item.name, videoEntries, subtitleEntries);

        if (matchingSub != null) {
            // Descargar el archivo de subtítulo rápidamente en caché
            final SmbEntry targetSub = matchingSub;
            ProgressDialog subProgress = ProgressDialog.show(activity, "Subtítulos", "Descargando " + targetSub.name, true, false);
            if (subProgress.getWindow() != null) {
                subProgress.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
            executor.execute(() -> {
                File cachedSub = downloadSubtitle(connection, targetSub);
                file.cachedSubtitleFile = cachedSub;
                activity.runOnUiThread(() -> {
                    subProgress.dismiss();
                    dialog.dismiss();
                    connection.clearPassword();
                    executor.shutdownNow();
                    restoreImmersiveMode();
                    listener.onFileSelected(file);
                });
            });
        } else {
            dialog.dismiss();
            connection.clearPassword();
            executor.shutdownNow();
            restoreImmersiveMode();
            listener.onFileSelected(file);
        }
    }

    private File downloadSubtitle(ConnectionInfo info, SmbEntry subEntry) {
        try {
            SmbConfig config = info.anonymous
                    ? SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1).build()
                    : SmbConfig.createDefaultConfig();
            try (SMBClient client = new SMBClient(config);
                 Connection connection = client.connect(info.server, info.port)) {
                AuthenticationContext authentication = info.anonymous
                        ? AuthenticationContext.anonymous()
                        : new AuthenticationContext(info.username, info.password, info.domain);
                Session session = connection.authenticate(authentication);
                try (DiskShare share = (DiskShare) session.connectShare(info.share)) {
                    com.hierynomus.smbj.share.File remoteFile = share.openFile(
                            subEntry.path,
                            Collections.singleton(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                            null,
                            com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
                            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                            null);
                    File localFile = new File(activity.getCacheDir(), "smb_sub_" + System.currentTimeMillis() + "_" + subEntry.name);
                    try (InputStream is = remoteFile.getInputStream();
                         FileOutputStream fos = new FileOutputStream(localFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                    Log.i(TAG, "Subtítulo SMB descargado exitosamente: " + localFile.getAbsolutePath());
                    return localFile;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo descargar el subtítulo de SMB: " + subEntry.name, e);
            return null;
        }
    }

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "or", "of", "in", "a", "an", "el", "la", "los", "las", "un", "una",
            "3d", "sbs", "hsbs", "fsbs", "half", "full", "tab", "ou", "hou", "half-sbs", "half-ou",
            "tablet", "skyy", "imax", "h-sbs", "h-ou", "mvc", "anaglyph",
            "1080p", "720p", "2160p", "4k", "uhd", "fhd", "hd", "sd",
            "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "hdr", "sdr",
            "bluray", "bdrip", "brrip", "dvdrip", "dvd", "web", "webrip", "web-dl", "webdl", "remux", "hdtv",
            "ac3", "dts", "dts-hd", "truehd", "atmos", "aac", "mp3", "pcm", "flac", "dd5", "ddp5", "5.1", "7.1",
            "yify", "yts", "rarbg", "evo", "sparks", "amiable", "geckos", "cinefile",
            "spanish", "español", "castellano", "latino", "english", "ingles", "spa", "es", "eng", "en",
            "sub", "subs", "subtitles", "subtitulos", "forced", "forzados", "completo", "srt", "vtt"
    ));

    public static String extractCoreTitle(String fileName) {
        if (fileName == null) return "";
        String base = stripExtension(fileName).toLowerCase(Locale.US);
        // Quitar años (e.g. 1999, 2025, (2025), [2025])
        base = base.replaceAll("[(\\[]?\\b(19\\d{2}|20\\d{2})\\b[\\])]?", " ");
        base = base.replaceAll("[._\\-+()\\[\\]{}]+", " ").trim();
        String[] words = base.split("\\s+");
        StringBuilder core = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty() || STOP_WORDS.contains(w) || w.matches("^\\d+$")) continue;
            if (core.length() > 0) core.append(" ");
            core.append(w);
        }
        return core.toString();
    }

    public static SmbEntry findBestMatchingSubtitle(String videoName, List<SmbEntry> videoEntries, List<SmbEntry> subtitleEntries) {
        if (subtitleEntries == null || subtitleEntries.isEmpty() || videoName == null) {
            return null;
        }

        String rawVideoNoExt = stripExtension(videoName).toLowerCase(Locale.US);
        String videoClean = cleanNameForMatching(rawVideoNoExt);
        String videoCore = extractCoreTitle(videoName);
        SmbEntry bestSub = null;
        int bestScore = -1;

        int videoCount = 0;
        if (videoEntries != null) {
            for (SmbEntry e : videoEntries) {
                if (!e.directory && !e.isParent) videoCount++;
            }
        }

        for (SmbEntry sub : subtitleEntries) {
            String rawSubNoExt = stripExtension(sub.name).toLowerCase(Locale.US);
            String subRaw = sub.name.toLowerCase(Locale.US);
            String subClean = cleanNameForMatching(rawSubNoExt);
            String subCore = extractCoreTitle(sub.name);

            int score = calculateMatchScore(rawVideoNoExt, videoClean, videoCore,
                                            rawSubNoExt, subClean, subCore, subRaw, videoCount);

            if (score > bestScore) {
                bestScore = score;
                bestSub = sub;
            }
        }

        return (bestScore > 0) ? bestSub : null;
    }

    private static String cleanNameForMatching(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.US)
                .replaceAll("[._\\-+()\\[\\]{}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int calculateMatchScore(String rawVideo, String videoClean, String videoCore,
                                           String rawSub, String subClean, String subCore,
                                           String subRaw, int videoCount) {
        // 1. Coincidencia idéntica del nombre de archivo (excepto extensión)
        if (rawVideo.equals(rawSub)) {
            return 1000;
        }

        // 2. Coincidencia idéntica con limpieza de puntuación
        if (videoClean.equals(subClean)) {
            return 950;
        }

        // 3. Subtítulo con sufijo de idioma adjunto al mismo nombre exacto (e.g. Pelicula.es.srt o Pelicula.spa.srt)
        if (rawSub.startsWith(rawVideo) || subClean.startsWith(videoClean)) {
            String remainder = rawSub.substring(Math.min(rawSub.length(), rawVideo.length()));
            if (remainder.contains("es") || remainder.contains("spa") || remainder.contains("spanish") || remainder.contains("latino")) {
                return 920;
            }
            return 900;
        }

        // 4. Título principal idéntico no vacío (e.g. "TRON ARES" en video y en srt)
        if (!videoCore.isEmpty() && videoCore.equals(subCore)) {
            return 850;
        }

        // 5. Coincidencia por palabras del título principal (excluyendo tags técnicos y formatos 3D)
        if (!videoCore.isEmpty() && !subCore.isEmpty()) {
            String[] videoWords = videoCore.split(" ");
            String[] subWords = subCore.split(" ");
            int matchedWords = 0;
            for (String sw : subWords) {
                for (String vw : videoWords) {
                    if (vw.equals(sw)) {
                        matchedWords++;
                        break;
                    }
                }
            }

            int minRequired = Math.max(1, (Math.min(videoWords.length, subWords.length) + 1) / 2);
            if (matchedWords >= minRequired && matchedWords >= 1) {
                boolean hasSignificantMatch = false;
                for (String sw : subWords) {
                    if (sw.length() >= 4) {
                        for (String vw : videoWords) {
                            if (vw.equals(sw)) {
                                hasSignificantMatch = true;
                                break;
                            }
                        }
                    }
                }
                if (hasSignificantMatch || videoWords.length == 1) {
                    int score = 500 + matchedWords * 60;
                    if (subRaw.contains("spanish") || subRaw.contains("español") || subRaw.contains("spa")
                            || subRaw.contains("latino") || subRaw.contains(".es")) {
                        score += 50;
                    }
                    return score;
                }
            }
        }

        // 6. Si hay UN SOLO video en toda la carpeta y el subtítulo es genérico (e.g. "spanish.srt", "sub.srt")
        if (videoCount == 1 && subCore.isEmpty()) {
            if (subRaw.contains("spanish") || subRaw.contains("español") || subRaw.contains("latino")
                    || subRaw.contains("subs") || subRaw.contains("spa")) {
                return 400;
            }
        }

        return 0;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static final class SmbItemAdapter extends BaseAdapter {
        private final Context context;
        private final List<SmbEntry> items;
        private final Set<String> videosWithSubtitles;
        final boolean isMosaic;

        SmbItemAdapter(Context context, List<SmbEntry> items, Set<String> videosWithSubtitles, boolean isMosaic) {
            this.context = context;
            this.items = items;
            this.videosWithSubtitles = videosWithSubtitles;
            this.isMosaic = isMosaic;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SmbEntry item = items.get(position);

            if (isMosaic) {
                return buildMosaicItem(item);
            } else {
                return buildListItem(item);
            }
        }

        private View buildMosaicItem(SmbEntry item) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(12), dp(14), dp(12), dp(14));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xDD15222E);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(1), 0x2254E0C7);
            card.setBackground(bg);

            // Fila superior: Badges (3D, SUB) e icono
            FrameLayout iconArea = new FrameLayout(context);
            LinearLayout.LayoutParams iconAreaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            iconArea.setLayoutParams(iconAreaParams);

            TextView iconView = new TextView(context);
            iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
            iconView.setGravity(Gravity.CENTER);
            if (item.isParent) {
                iconView.setText("📁");
            } else if (item.directory) {
                iconView.setText("📁");
            } else {
                iconView.setText("🎬");
            }
            iconArea.addView(iconView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

            // Insignia 3D si aplica
            if (!item.directory && is3D(item.name)) {
                TextView badge3D = new TextView(context);
                badge3D.setText("3D");
                badge3D.setTextColor(0xFF0C131A);
                badge3D.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                badge3D.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                badge3D.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(0xFF54E0C7);
                badgeBg.setCornerRadius(dp(6));
                badge3D.setBackground(badgeBg);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
                iconArea.addView(badge3D, badgeParams);
            }

            // Insignia SUB si tiene subtítulo detectado
            if (!item.directory && videosWithSubtitles.contains(item.name)) {
                TextView badgeSub = new TextView(context);
                badgeSub.setText("SUB");
                badgeSub.setTextColor(0xFF0C131A);
                badgeSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
                badgeSub.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                badgeSub.setPadding(dp(5), dp(2), dp(5), dp(2));
                GradientDrawable subBg = new GradientDrawable();
                subBg.setColor(0xFF81C784);
                subBg.setCornerRadius(dp(6));
                badgeSub.setBackground(subBg);
                FrameLayout.LayoutParams subParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START);
                iconArea.addView(badgeSub, subParams);
            }

            card.addView(iconArea);

            // Nombre del archivo
            TextView nameView = new TextView(context);
            nameView.setText(item.name);
            nameView.setTextColor(0xFFECEFF1);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            nameView.setTypeface(Typeface.DEFAULT, item.directory ? Typeface.BOLD : Typeface.NORMAL);
            nameView.setGravity(Gravity.CENTER);
            nameView.setMaxLines(2);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setPadding(0, dp(6), 0, 0);
            card.addView(nameView);

            return card;
        }

        private View buildListItem(SmbEntry item) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xDD15222E);
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), 0x2254E0C7);
            row.setBackground(bg);

            TextView icon = new TextView(context);
            icon.setText(item.directory ? "📁" : "🎬");
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            icon.setPadding(0, 0, dp(12), 0);
            row.addView(icon);

            TextView name = new TextView(context);
            name.setText(item.name);
            name.setTextColor(0xFFECEFF1);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!item.directory && is3D(item.name)) {
                TextView badge3D = new TextView(context);
                badge3D.setText("3D");
                badge3D.setTextColor(0xFF0C131A);
                badge3D.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                badge3D.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                badge3D.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable b3d = new GradientDrawable();
                b3d.setColor(0xFF54E0C7);
                b3d.setCornerRadius(dp(6));
                badge3D.setBackground(b3d);
                row.addView(badge3D);
            }

            if (!item.directory && videosWithSubtitles.contains(item.name)) {
                TextView badgeSub = new TextView(context);
                badgeSub.setText("SUB");
                badgeSub.setTextColor(0xFF0C131A);
                badgeSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
                badgeSub.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                badgeSub.setPadding(dp(5), dp(2), dp(5), dp(2));
                GradientDrawable subBg = new GradientDrawable();
                subBg.setColor(0xFF81C784);
                subBg.setCornerRadius(dp(6));
                badgeSub.setBackground(subBg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(6), 0, 0, 0);
                row.addView(badgeSub, lp);
            }

            return row;
        }

        private static boolean is3D(String name) {
            String lower = name.toLowerCase(Locale.US);
            return lower.contains("3d") || lower.contains("sbs") || lower.contains("hsbs")
                    || lower.contains("fsbs") || lower.contains("tab") || lower.contains("htab")
                    || lower.contains("ftab") || lower.contains("ou");
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    private void showError(ConnectionInfo connection, String path, Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("SMB connection failed")
                .setMessage(message)
                .setNegativeButton("CLOSE", (ignored, which) -> {
                    connection.clearPassword();
                    executor.shutdownNow();
                    restoreImmersiveMode();
                })
                .setPositiveButton("RETRY", (ignored, which) -> browse(connection, path))
                .create();

        configureDialogImmersive(dialog);
        showImmersiveDialog(dialog);
    }

    private boolean isVideo(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mkv")
                || lower.endsWith(".mp4")
                || lower.endsWith(".webm")
                || lower.endsWith(".avi")
                || lower.endsWith(".mov")
                || lower.endsWith(".m2ts")
                || lower.endsWith(".ts");
    }

    private boolean isSubtitle(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".srt")
                || lower.endsWith(".vtt")
                || lower.endsWith(".sub");
    }

    private String locationLabel(ConnectionInfo connection, String path) {
        String suffix = path.isEmpty() ? "" : "/" + path.replace('\\', '/');
        return "//" + connection.host + "/" + connection.share + suffix;
    }

    private String normalizeHost(String host) {
        String value = host.trim();
        if (value.toLowerCase(Locale.US).startsWith("smb://")) {
            value = value.substring(6);
        }
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private String trimSlashes(String value) {
        return value.trim().replaceAll("^[\\\\/]+|[\\\\/]+$", "");
    }

    private HostAndPort parseEndpoint(String authority) {
        String server = authority;
        int port = SMBClient.DEFAULT_PORT;
        int firstColon = authority.indexOf(':');
        int lastColon = authority.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            server = authority.substring(0, firstColon);
            String portText = authority.substring(firstColon + 1);
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("The SMB port must be a number");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("The SMB port must be between 1 and 65535");
            }
        }
        if (server.isEmpty()) {
            throw new IllegalArgumentException("The SMB server is required");
        }
        return new HostAndPort(authority, server, port);
    }

    private String normalizePath(String value) {
        return trimSlashes(value).replace('/', '\\');
    }

    private String join(String parent, String child) {
        return parent.isEmpty() ? child : parent + "\\" + child;
    }

    private String parent(String path) {
        int separator = path.lastIndexOf('\\');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class ConnectionInfo {
        final String host;
        final String server;
        final int port;
        final String share;
        final String path;
        final String username;
        final char[] password;
        final String domain;
        final boolean anonymous;

        ConnectionInfo(HostAndPort endpoint, String share, String path, String username,
                       char[] password, String domain, boolean anonymous) {
            this.host = endpoint.authority;
            this.server = endpoint.server;
            this.port = endpoint.port;
            this.share = share;
            this.path = path;
            this.username = username;
            this.password = Arrays.copyOf(password, password.length);
            Arrays.fill(password, '\0');
            this.domain = domain;
            this.anonymous = anonymous;
        }

        void clearPassword() {
            Arrays.fill(password, '\0');
        }
    }

    private static final class HostAndPort {
        final String authority;
        final String server;
        final int port;

        HostAndPort(String authority, String server, int port) {
            this.authority = authority;
            this.server = server;
            this.port = port;
        }
    }

    public static final class SmbEntry {
        public final String name;
        public final String path;
        public final boolean directory;
        public final boolean isParent;

        public SmbEntry(String name, String path, boolean directory) {
            this(name, path, directory, false);
        }

        public SmbEntry(String name, String path, boolean directory, boolean isParent) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.isParent = isParent;
        }
    }
}
