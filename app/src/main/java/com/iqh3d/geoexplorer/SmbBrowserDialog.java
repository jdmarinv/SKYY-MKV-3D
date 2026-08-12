package com.iqh3d.geoexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SmbBrowserDialog {
    private static final String TAG = "SkyySmbBrowser";

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
        EditText password = field(form, "Password", "", true);
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
                .setNegativeButton("CANCEL", (ignored, which) -> executor.shutdownNow())
                .setPositiveButton("CONNECT", null)
                .create();
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
                    dialog.dismiss();
                    browse(connection, connection.path);
                }));
        dialog.setOnCancelListener(ignored -> executor.shutdownNow());
        dialog.show();
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

    private void browse(ConnectionInfo connection, String path) {
        ProgressDialog progress = ProgressDialog.show(
                activity, "SMB", "Loading " + locationLabel(connection, path), true, false);
        executor.execute(() -> {
            try {
                List<SmbEntry> entries = list(connection, path);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        connection.clearPassword();
                        executor.shutdownNow();
                        return;
                    }
                    progress.dismiss();
                    showEntries(connection, path, entries);
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

    private List<SmbEntry> list(ConnectionInfo info, String path) throws IOException {
        List<SmbEntry> entries = new ArrayList<>();
        // SMB 3.x key derivation requires a session key that anonymous logins do not provide.
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
                    }
                }
            }
        }
        Collections.sort(entries, Comparator
                .comparing((SmbEntry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name.toLowerCase(Locale.US)));
        return entries;
    }

    private void showEntries(ConnectionInfo connection, String path, List<SmbEntry> entries) {
        List<String> labels = new ArrayList<>();
        boolean hasParent = !path.isEmpty();
        if (hasParent) {
            labels.add("[..] Parent folder");
        }
        for (SmbEntry entry : entries) {
            labels.add(entry.directory ? "[DIR] " + entry.name : entry.name);
        }
        if (labels.isEmpty()) {
            labels.add("No video files or folders");
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(locationLabel(connection, path))
                .setItems(labels.toArray(new String[0]), (ignored, index) -> {
                    if (entries.isEmpty() && !hasParent) {
                        return;
                    }
                    if (hasParent && index == 0) {
                        browse(connection, parent(path));
                        return;
                    }
                    int entryIndex = index - (hasParent ? 1 : 0);
                    if (entryIndex < 0 || entryIndex >= entries.size()) {
                        return;
                    }
                    SmbEntry entry = entries.get(entryIndex);
                    if (entry.directory) {
                        browse(connection, entry.path);
                    } else {
                        SmbFile file = new SmbFile(connection, entry.path);
                        connection.clearPassword();
                        executor.shutdownNow();
                        listener.onFileSelected(file);
                    }
                })
                .setNegativeButton("CLOSE", (ignored, which) -> {
                    connection.clearPassword();
                    executor.shutdownNow();
                })
                .create();
        dialog.setOnCancelListener(ignored -> {
            connection.clearPassword();
            executor.shutdownNow();
        });
        dialog.show();
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
                })
                .setPositiveButton("RETRY", (ignored, which) -> browse(connection, path))
                .create();
        dialog.setOnCancelListener(ignored -> {
            connection.clearPassword();
            executor.shutdownNow();
        });
        dialog.show();
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

    private static final class SmbEntry {
        final String name;
        final String path;
        final boolean directory;

        SmbEntry(String name, String path, boolean directory) {
            this.name = name;
            this.path = path;
            this.directory = directory;
        }
    }
}
