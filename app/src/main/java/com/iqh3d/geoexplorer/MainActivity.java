package com.iqh3d.geoexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "SkyyMkvPlayer";
    private static final int PICK_VIDEO = 1001;
    private static final int PICK_SUBTITLE = 1002;
    private static final String PLAYBACK_PREFS = "playback_positions";
    private static final long MIN_RESUME_POSITION_MS = 5_000L;
    private static final long COMPLETION_MARGIN_MS = 15_000L;
    private static final long POSITION_SAVE_INTERVAL_MS = 10_000L;

    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private SurfaceView vlcSurface;
    private StereoSubtitleView stereoSubtitleView;
    private SubtitleParser.SubtitleTrack activeSubtitleTrack;
    private int activeStereoMode = StereoSubtitleView.MODE_2D;
    private LibVLC libVlc;
    private MediaPlayer vlcPlayer;
    private ParcelFileDescriptor vlcInput;
    private TextView statusView;
    private TextView playbackTimeView;
    private TextView durationTimeView;
    private TextView titleView;
    private TextView playPauseView;
    private SeekBar playbackSeekBar;
    private FrameLayout chrome;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideChromeRunnable = this::hideChrome;
    private final Runnable updateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
            uiHandler.postDelayed(this, 500);
        }
    };

    private Uri pendingUri;
    private SmbBrowserDialog.SmbFile pendingSmbFile;
    private String pendingName = "No file selected";
    private boolean usingVlc;
    private boolean vlcPlaybackPending;
    private long pendingVlcPosition;
    private String pendingVlcReason;
    private String appliedPackedAspect;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean userSeeking;
    private String activePlaybackKey;
    private long lastPositionSaveElapsedMs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        enterImmersiveMode();

        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                uiHandler.removeCallbacks(this::enterImmersiveMode);
                uiHandler.postDelayed(this::enterImmersiveMode, 1500);
            }
        });

        buildExoPlayer();
        setContentView(buildLayout());
        configureSurface(playerView.getVideoSurfaceView());
        updateStatus("Ready");
        uiHandler.post(updateProgressRunnable);
        checkPermissionsAndInitProvisioning();
    }

    public void enterImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    public void enterImmersiveModePublic() {
        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    private void buildExoPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        exoPlayer = new ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .build();
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                if (!usingVlc && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
                    startVlcFallback(exoPlayer.getCurrentPosition(), "audio not supported by Media3");
                }
                if (!usingVlc && activeSubtitleTrack == null) {
                    autoSelectMedia3SubtitleIfAvailable(tracks);
                }
            }

            @Override
            public void onCues(@NonNull CueGroup cueGroup) {
                if (usingVlc) {
                    return;
                }
                // Si no hay archivo de subtítulo externo cargado, usar los subtítulos embebidos de Media3
                if (activeSubtitleTrack == null && stereoSubtitleView != null) {
                    if (cueGroup.cues != null && !cueGroup.cues.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (Cue cue : cueGroup.cues) {
                            if (cue.text != null) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(cue.text);
                            }
                        }
                        stereoSubtitleView.setExternalCueText(sb.toString());
                    } else {
                        stereoSubtitleView.setExternalCueText("");
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (!usingVlc && pendingUri != null) {
                    startVlcFallback(exoPlayer.getCurrentPosition(), "Media3 decoder error");
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (usingVlc) {
                    return;
                }
                if (isPlaying) {
                    hideChromeSoon();
                } else {
                    showChrome();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (!usingVlc && playbackState == Player.STATE_ENDED) {
                    clearActivePlaybackPosition();
                }
            }
        });
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        FrameLayout videoHost = new FrameLayout(this);
        videoHost.setOnClickListener(v -> showChromeTemporarily());
        root.addView(videoHost, matchParent());

        playerView = new PlayerView(this);
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(false);
        playerView.setOnClickListener(v -> showChromeTemporarily());
        playerView.setKeepScreenOn(true);
        playerView.setUseArtwork(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        if (playerView.getSubtitleView() != null) {
            // Ocultar renderizado plano 2D interno de Media3 para dar paso a StereoSubtitleView
            playerView.getSubtitleView().setVisibility(View.GONE);
        }
        videoHost.addView(playerView, matchParent());

        vlcSurface = new SurfaceView(this);
        vlcSurface.setKeepScreenOn(true);
        vlcSurface.setOnClickListener(v -> showChromeTemporarily());
        vlcSurface.setVisibility(View.GONE);
        vlcSurface.getHolder().setSizeFromLayout();
        vlcSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.i(TAG, "VLC SurfaceView created: " + holder.getSurfaceFrame());
                attachVlcSurface();
                startPendingVlcPlayback();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                recordSurfaceSize(width, height);
                if (vlcPlayer != null) {
                    vlcPlayer.getVLCVout().setWindowSize(width, height);
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.i(TAG, "VLC SurfaceView destroyed");
                if (vlcPlayer != null && vlcPlayer.getVLCVout().areViewsAttached()) {
                    vlcPlayer.getVLCVout().detachViews();
                }
            }
        });
        videoHost.addView(vlcSurface, matchParent());

        // Capa de subtítulos estereoscópicos
        stereoSubtitleView = new StereoSubtitleView(this);
        root.addView(stereoSubtitleView, matchParent());

        View playbackTouchLayer = new View(this);
        playbackTouchLayer.setBackgroundColor(0x00000000);
        playbackTouchLayer.setOnClickListener(v -> showChromeTemporarily());
        root.addView(playbackTouchLayer, matchParent());

        chrome = new FrameLayout(this);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), dp(10), dp(18), dp(10));
        topBar.setBackgroundColor(0x7A000000);

        TextView back = createTextControl("<", 30f, v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(54)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_skyy_logo);
        logo.setContentDescription("SKYY 3D Player logo");
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        topBar.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(8), 0, dp(12), 0);
        titleView = new TextView(this);
        titleView.setText("SKYY MKV 3D");
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18f);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setSingleLine(true);
        statusView = new TextView(this);
        statusView.setTextColor(0xFFB9C2CC);
        statusView.setTextSize(11f);
        statusView.setSingleLine(true);
        titleBlock.addView(titleView);
        titleBlock.addView(statusView);
        topBar.addView(titleBlock, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView open = createTextControl("OPEN", 13f, v -> pickVideo());
        TextView network = createTextControl("SMB", 13f, v -> openSmbBrowser());
        TextView audio = createTextControl("AUDIO", 13f, v -> showAudioTrackSelector());
        TextView subs = createTextControl("SUBS", 13f, v -> showSubtitleSelector());
        TextView mode = createTextControl("3D", 13f, v -> Toast.makeText(this,
                "Use the 3DFV selector on the left edge", Toast.LENGTH_SHORT).show());
        topBar.addView(open, new LinearLayout.LayoutParams(dp(88), dp(54)));
        topBar.addView(network, new LinearLayout.LayoutParams(dp(72), dp(54)));
        topBar.addView(audio, new LinearLayout.LayoutParams(dp(88), dp(54)));
        topBar.addView(subs, new LinearLayout.LayoutParams(dp(80), dp(54)));
        topBar.addView(mode, new LinearLayout.LayoutParams(dp(64), dp(54)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        chrome.addView(topBar, topParams);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(18), dp(8), dp(18), dp(12));
        bottomBar.setBackgroundColor(0x8A000000);

        LinearLayout timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.HORIZONTAL);
        timeline.setGravity(Gravity.CENTER_VERTICAL);

        playbackTimeView = new TextView(this);
        playbackTimeView.setTextColor(0xFFFFFFFF);
        playbackTimeView.setText("00:00");
        playbackTimeView.setTextSize(15f);
        playbackTimeView.setGravity(Gravity.CENTER);

        playbackSeekBar = new SeekBar(this);
        playbackSeekBar.setMax(1000);
        playbackSeekBar.setMinimumHeight(dp(42));
        playbackSeekBar.setProgressTintList(ColorStateList.valueOf(0xFF57C7FF));
        playbackSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x80FFFFFF));
        playbackSeekBar.setThumbTintList(ColorStateList.valueOf(0xFF57C7FF));
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateTimeForProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                uiHandler.removeCallbacks(hideChromeRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekToProgress(seekBar.getProgress());
                userSeeking = false;
                hideChromeSoon();
            }
        });
        durationTimeView = new TextView(this);
        durationTimeView.setTextColor(0xFFFFFFFF);
        durationTimeView.setText("00:00");
        durationTimeView.setTextSize(15f);
        durationTimeView.setGravity(Gravity.CENTER);

        timeline.addView(playbackTimeView, new LinearLayout.LayoutParams(dp(80), dp(44)));
        timeline.addView(playbackSeekBar, new LinearLayout.LayoutParams(
                0,
                dp(44),
                1f));
        timeline.addView(durationTimeView, new LinearLayout.LayoutParams(dp(80), dp(44)));
        bottomBar.addView(timeline);

        FrameLayout controlRow = new FrameLayout(this);
        LinearLayout centerControls = new LinearLayout(this);
        centerControls.setOrientation(LinearLayout.HORIZONTAL);
        centerControls.setGravity(Gravity.CENTER);

        TextView rewind = createRoundControl("-10", 18f, v -> seekRelative(-10_000L));
        playPauseView = createRoundControl("PAUSE", 15f, v -> togglePlayback());
        TextView forward = createRoundControl("+10", 18f, v -> seekRelative(10_000L));
        centerControls.addView(rewind, spacedControlParams(dp(64), dp(56)));
        centerControls.addView(playPauseView, spacedControlParams(dp(86), dp(64)));
        centerControls.addView(forward, spacedControlParams(dp(64), dp(56)));

        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        controlRow.addView(centerControls, centerParams);

        TextView folder = createTextControl("FILE", 12f, v -> pickVideo());
        FrameLayout.LayoutParams folderParams = new FrameLayout.LayoutParams(dp(100), dp(56), Gravity.START);
        controlRow.addView(folder, folderParams);

        TextView fit = createTextControl("FIT", 12f, v -> Toast.makeText(this,
                "Video surface remains 2560x1600", Toast.LENGTH_SHORT).show());
        FrameLayout.LayoutParams fitParams = new FrameLayout.LayoutParams(dp(100), dp(56), Gravity.END);
        controlRow.addView(fit, fitParams);
        bottomBar.addView(controlRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        chrome.addView(bottomBar, bottomParams);
        root.addView(chrome, matchParent());
        return root;
    }

    private TextView createTextControl(String label, float textSize, View.OnClickListener listener) {
        TextView control = new TextView(this);
        control.setText(label);
        control.setTextColor(0xFFFFFFFF);
        control.setTextSize(textSize);
        control.setGravity(Gravity.CENTER);
        control.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        control.setOnClickListener(listener);
        return control;
    }

    private TextView createRoundControl(String label, float textSize, View.OnClickListener listener) {
        TextView control = createTextControl(label, textSize, listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC11151A);
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(40));
        background.setStroke(dp(1), 0x66FFFFFF);
        control.setBackground(background);
        return control;
    }

    private LinearLayout.LayoutParams spacedControlParams(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(8), 0, dp(8), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void configureSurface(View surface) {
        if (!(surface instanceof SurfaceView)) {
            throw new IllegalStateException("3DFV requires a real SurfaceView");
        }
        SurfaceHolder holder = ((SurfaceView) surface).getHolder();
        holder.setSizeFromLayout();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
                Log.i(TAG, "Media3 SurfaceView created: " + surfaceHolder.getSurfaceFrame());
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {
                recordSurfaceSize(width, height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
                Log.i(TAG, "Media3 SurfaceView destroyed");
            }
        });
    }

    private void recordSurfaceSize(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        Log.i(TAG, "SurfaceView buffer/layout: " + width + "x" + height);
        updateStatus(usingVlc ? "VLC active" : "Media3 active");
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onPause() {
        pauseActivePlayer();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveActivePlaybackPosition();
        uiHandler.removeCallbacksAndMessages(null);
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        releaseVlc();
        if (pendingSmbFile != null) {
            pendingSmbFile.clearPassword();
            pendingSmbFile = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            enterImmersiveMode();
            return;
        }
        if (requestCode == PICK_VIDEO) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                Log.w(TAG, "The provider does not allow persistent read permission");
            }
            pendingUri = uri;
            if (pendingSmbFile != null) {
                pendingSmbFile.clearPassword();
                pendingSmbFile = null;
            }
            pendingName = queryDisplayName(uri);
            playPending();
        } else if (requestCode == PICK_SUBTITLE) {
            Uri subUri = data.getData();
            if (subUri != null) {
                loadExternalSubtitleFromUri(subUri);
            }
            enterImmersiveMode();
        }
    }

    private static final int REQUEST_STORAGE_PERMS = 1010;

    private void checkPermissionsAndInitProvisioning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                ThreeDfvProvisioner.autoProvisionAsync(this);
            } else {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE_PERMS);
            }
        } else {
            ThreeDfvProvisioner.autoProvisionAsync(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ThreeDfvProvisioner.autoProvisionAsync(this);
            }
        }
        enterImmersiveMode();
    }

    private void openSmbBrowser() {
        showChrome();
        new SmbBrowserDialog(this, this::playSmbFile).show();
    }

    private void playSmbFile(SmbBrowserDialog.SmbFile file) {
        if (pendingSmbFile != null) {
            pendingSmbFile.clearPassword();
        }
        pendingSmbFile = file;
        pendingUri = file.uri();
        pendingName = file.displayName();
        enterImmersiveMode();
        playPending();
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "video/x-matroska",
                "video/mp4",
                "video/webm",
                "video/*"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }

    private void pickSubtitle() {
        showChrome();
        uiHandler.removeCallbacks(hideChromeRunnable);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "text/plain",
                "application/x-subrip",
                "text/vtt",
                "application/octet-stream",
                "*/*"
        });
        startActivityForResult(intent, PICK_SUBTITLE);
    }

    private void loadExternalSubtitleFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            String name = queryDisplayName(uri);
            activeSubtitleTrack = SubtitleParser.parseStream(is, name);
            if (activeSubtitleTrack != null && !activeSubtitleTrack.cues.isEmpty()) {
                applySubtitleMode(activeStereoMode);
                Toast.makeText(this, "Subtítulo: " + name + " (" + activeSubtitleTrack.cues.size() + " frases)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No se encontraron entradas válidas de subtítulos", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cargando archivo de subtítulo", e);
            Toast.makeText(this, "Error al leer el archivo de subtítulo", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchLocalCompanionSubtitle(Uri uri, String name) {
        if (uri == null || name == null) return;
        try {
            File videoFile = resolveFileFromUri(uri);
            if (videoFile != null && videoFile.exists()) {
                File parent = videoFile.getParentFile();
                if (parent != null && parent.exists() && parent.isDirectory()) {
                    searchInDirectory(parent, name);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error buscando subtítulo complementario local", e);
        }
    }

    private boolean searchInDirectory(File dir, String name) {
        File[] siblings = dir.listFiles();
        if (siblings == null || siblings.length == 0) return false;

        List<SmbBrowserDialog.SmbEntry> videoEntries = new ArrayList<>();
        List<SmbBrowserDialog.SmbEntry> subEntries = new ArrayList<>();

        for (File f : siblings) {
            String fName = f.getName().toLowerCase(Locale.US);
            if (fName.endsWith(".mkv") || fName.endsWith(".mp4") || fName.endsWith(".webm") || fName.endsWith(".avi") || fName.endsWith(".ts")) {
                videoEntries.add(new SmbBrowserDialog.SmbEntry(f.getName(), f.getAbsolutePath(), false));
            } else if (fName.endsWith(".srt") || fName.endsWith(".vtt")) {
                subEntries.add(new SmbBrowserDialog.SmbEntry(f.getName(), f.getAbsolutePath(), false));
            }
        }

        SmbBrowserDialog.SmbEntry bestSub = SmbBrowserDialog.findBestMatchingSubtitle(name, videoEntries, subEntries);
        if (bestSub != null) {
            File subFile = new File(bestSub.path);
            if (subFile.exists()) {
                activeSubtitleTrack = SubtitleParser.parseFile(subFile);
                if (activeSubtitleTrack != null) {
                    applySubtitleMode(activeStereoMode);
                    Toast.makeText(this, "Subtítulo local detectado: " + subFile.getName(), Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        }
        return false;
    }

    private File resolveFileFromUri(Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath());
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try {
                if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                    String docId = android.provider.DocumentsContract.getDocumentId(uri);
                    if (docId != null) {
                        if (docId.startsWith("primary:")) {
                            return new File(Environment.getExternalStorageDirectory(), docId.substring("primary:".length()));
                        } else if (docId.startsWith("raw:")) {
                            return new File(docId.substring("raw:".length()));
                        }
                    }
                }
                String[] projection = { android.provider.MediaStore.Video.Media.DATA };
                try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int index = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA);
                        String path = cursor.getString(index);
                        if (path != null) return new File(path);
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void playOrResume() {
        if (pendingUri == null) {
            Toast.makeText(this, "Open an MKV or video first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (usingVlc && vlcPlayer != null && vlcPlayer.hasMedia()) {
            vlcPlayer.play();
            hideChromeSoon();
        } else if (!usingVlc && exoPlayer.getMediaItemCount() > 0) {
            exoPlayer.play();
            hideChromeSoon();
        } else {
            playPending();
        }
    }

    private void showAudioTrackSelector() {
        showChrome();
        uiHandler.removeCallbacks(hideChromeRunnable);
        if (pendingUri == null) {
            Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (usingVlc) {
            showVlcAudioTrackSelector();
        } else {
            showMedia3AudioTrackSelector();
        }
    }

    private void showVlcAudioTrackSelector() {
        if (vlcPlayer == null || !vlcPlayer.hasMedia()) {
            Toast.makeText(this, "Audio tracks are not available yet", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        if (tracks == null || tracks.length == 0) {
            Toast.makeText(this, "No audio tracks found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[tracks.length];
        int selectedIndex = -1;
        int activeTrackId = vlcPlayer.getAudioTrack();
        for (int index = 0; index < tracks.length; index++) {
            MediaPlayer.TrackDescription track = tracks[index];
            labels[index] = buildVlcAudioLabel(track, index);
            if (track.id == activeTrackId) {
                selectedIndex = index;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Audio track")
                .setSingleChoiceItems(labels, selectedIndex, null)
                .setNegativeButton("CANCEL", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    MediaPlayer.TrackDescription track = tracks[position];
                    if (vlcPlayer.setAudioTrack(track.id)) {
                        Toast.makeText(this, "Audio: " + labels[position], Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "Selected VLC audio track " + track.id + ": " + labels[position]);
                    } else {
                        Toast.makeText(this, "Could not change the audio track", Toast.LENGTH_LONG).show();
                    }
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> {
            enterImmersiveMode();
            resumeChromeTimeout();
        });
        dialog.show();
    }

    private String buildVlcAudioLabel(MediaPlayer.TrackDescription description, int index) {
        if (description.id < 0) {
            return "Audio off";
        }
        String name = cleanTrackText(description.name);
        String language = null;
        int channels = 0;
        String codec = null;
        IMedia media = vlcPlayer.getMedia();
        if (media != null) {
            try {
                for (int trackIndex = 0; trackIndex < media.getTrackCount(); trackIndex++) {
                    IMedia.Track mediaTrack = media.getTrack(trackIndex);
                    if (mediaTrack instanceof IMedia.AudioTrack && mediaTrack.id == description.id) {
                        IMedia.AudioTrack audioTrack = (IMedia.AudioTrack) mediaTrack;
                        language = displayLanguage(audioTrack.language);
                        channels = audioTrack.channels;
                        codec = cleanTrackText(audioTrack.codec);
                        if (name == null) {
                            name = cleanTrackText(audioTrack.description);
                        }
                        break;
                    }
                }
            } finally {
                media.release();
            }
        }
        if (name == null) {
            name = "Track " + (index + 1);
        }
        return joinTrackDetails(name, language, channelLabel(channels), codec);
    }

    private void showMedia3AudioTrackSelector() {
        List<Media3AudioChoice> choices = new ArrayList<>();
        int selectedIndex = -1;
        for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (!group.isTrackSupported(trackIndex)) {
                    continue;
                }
                Format format = group.getTrackFormat(trackIndex);
                choices.add(new Media3AudioChoice(group, trackIndex, buildMedia3AudioLabel(format,
                        choices.size())));
                if (group.isTrackSelected(trackIndex)) {
                    selectedIndex = choices.size() - 1;
                }
            }
        }
        if (choices.isEmpty()) {
            Toast.makeText(this, "No selectable audio tracks found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[choices.size()];
        for (int index = 0; index < choices.size(); index++) {
            labels[index] = choices.get(index).label;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Audio track")
                .setSingleChoiceItems(labels, selectedIndex, null)
                .setNegativeButton("CANCEL", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    Media3AudioChoice choice = choices.get(position);
                    exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters()
                            .buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(
                                     choice.group.getMediaTrackGroup(), choice.trackIndex))
                            .build());
                    Toast.makeText(this, "Audio: " + choice.label, Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "Selected Media3 audio track: " + choice.label);
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> {
            enterImmersiveMode();
            resumeChromeTimeout();
        });
        dialog.show();
    }

    private String buildMedia3AudioLabel(Format format, int index) {
        String name = cleanTrackText(format.label);
        if (name == null) {
            name = "Track " + (index + 1);
        }
        String codec = cleanTrackText(format.codecs);
        if (codec == null) {
            codec = cleanTrackText(format.sampleMimeType);
            if (codec != null && codec.startsWith("audio/")) {
                codec = codec.substring("audio/".length());
            }
        }
        return joinTrackDetails(name, displayLanguage(format.language),
                channelLabel(format.channelCount), codec);
    }

    private String joinTrackDetails(String name, String language, String channels, String codec) {
        StringBuilder label = new StringBuilder(name);
        appendTrackDetail(label, language);
        appendTrackDetail(label, channels);
        appendTrackDetail(label, codec);
        return label.toString();
    }

    private void appendTrackDetail(StringBuilder label, String detail) {
        if (detail != null && !detail.isEmpty()
                && !label.toString().toLowerCase(Locale.US).contains(detail.toLowerCase(Locale.US))) {
            label.append("  |  ").append(detail);
        }
    }

    private String displayLanguage(String languageCode) {
        String cleaned = cleanTrackText(languageCode);
        if (cleaned == null || "und".equalsIgnoreCase(cleaned)) {
            return null;
        }
        String displayName = Locale.forLanguageTag(cleaned.replace('_', '-'))
                .getDisplayLanguage(Locale.ENGLISH);
        return displayName.isEmpty() ? cleaned : displayName;
    }

    private String channelLabel(int channels) {
        if (channels <= 0) {
            return null;
        }
        if (channels == 1) {
            return "Mono";
        }
        if (channels == 2) {
            return "Stereo";
        }
        if (channels == 6) {
            return "5.1";
        }
        if (channels == 8) {
            return "7.1";
        }
        return channels + " channels";
    }

    private String cleanTrackText(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private void resumeChromeTimeout() {
        if (isActivePlayerPlaying()) {
            hideChromeSoon();
        }
    }

    private static final class Media3AudioChoice {
        final Tracks.Group group;
        final int trackIndex;
        final String label;

        Media3AudioChoice(Tracks.Group group, int trackIndex, String label) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.label = label;
        }
    }

    private void showSubtitleSelector() {
        showChrome();
        uiHandler.removeCallbacks(hideChromeRunnable);

        final AlertDialog[] dialogHolder = new AlertDialog[1];

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(12), dp(22), dp(16));
        layout.setBackgroundColor(0xFF0C131A);

        // 1. Pista activa
        layout.addView(createSectionTitle("PISTA DE SUBTÍTULOS ACTIVA"));
        TextView trackStatus = new TextView(this);
        trackStatus.setTextColor(0xFF54E0C7);
        trackStatus.setTextSize(14f);
        trackStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        trackStatus.setPadding(0, dp(4), 0, dp(10));

        String currentTrackName = "(Desactivado / Ninguno)";
        if (activeSubtitleTrack != null) {
            currentTrackName = "✓ Externo: " + activeSubtitleTrack.name + " (" + activeSubtitleTrack.cues.size() + " frases)";
        } else if (usingVlc && vlcPlayer != null) {
            int currentSpu = vlcPlayer.getSpuTrack();
            if (currentSpu != -1) {
                MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
                if (tracks != null) {
                    for (MediaPlayer.TrackDescription td : tracks) {
                        if (td.id == currentSpu) {
                            currentTrackName = "✓ Video (VLC): " + td.name;
                            break;
                        }
                    }
                }
            }
        } else if (!usingVlc && exoPlayer != null) {
            for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
                if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
                    for (int i = 0; i < group.length; i++) {
                        if (group.isTrackSelected(i)) {
                            Format fmt = group.getTrackFormat(i);
                            currentTrackName = "✓ Video (Media3): " + buildMedia3TextLabel(fmt, i);
                            break;
                        }
                    }
                }
            }
        }
        trackStatus.setText(currentTrackName);
        layout.addView(trackStatus);

        // Subtítulos incorporados en el video (MKV / MP4)
        if (usingVlc && vlcPlayer != null) {
            MediaPlayer.TrackDescription[] spuTracks = vlcPlayer.getSpuTracks();
            if (spuTracks != null && spuTracks.length > 0) {
                layout.addView(createSectionTitle("SUBTÍTULOS INCORPORADOS EN EL VIDEO"));
                LinearLayout spuLayout = new LinearLayout(this);
                spuLayout.setOrientation(LinearLayout.VERTICAL);
                int currentSpu = (activeSubtitleTrack != null) ? -1 : vlcPlayer.getSpuTrack();

                for (MediaPlayer.TrackDescription td : spuTracks) {
                    boolean isSelected = (currentSpu == td.id);
                    TextView btn = createSelectableButton(td.name, isSelected);
                    btn.setOnClickListener(v -> {
                        vlcPlayer.setSpuTrack(td.id);
                        activeSubtitleTrack = null;
                        stereoSubtitleView.clear();
                        trackStatus.setText(td.id == -1 ? "(Desactivado)" : "✓ Video: " + td.name);
                        Toast.makeText(this, "Subtítulo: " + td.name, Toast.LENGTH_SHORT).show();
                        if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
                    lp.setMargins(0, 0, 0, dp(6));
                    spuLayout.addView(btn, lp);
                }
                layout.addView(spuLayout);
            }
        } else if (!usingVlc && exoPlayer != null) {
            List<Media3TextChoice> choices = getMedia3TextChoices();
            if (!choices.isEmpty()) {
                layout.addView(createSectionTitle("SUBTÍTULOS INCORPORADOS EN EL VIDEO"));
                LinearLayout textLayout = new LinearLayout(this);
                textLayout.setOrientation(LinearLayout.VERTICAL);

                boolean noneSelected = (activeSubtitleTrack != null) || !isAnyMedia3TextSelected();
                TextView btnNone = createSelectableButton("Desactivado", noneSelected);
                btnNone.setOnClickListener(v -> {
                    exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters()
                            .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                    activeSubtitleTrack = null;
                    stereoSubtitleView.clear();
                    trackStatus.setText("(Desactivado)");
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                });
                LinearLayout.LayoutParams lpNone = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
                lpNone.setMargins(0, 0, 0, dp(6));
                textLayout.addView(btnNone, lpNone);

                for (Media3TextChoice choice : choices) {
                    boolean isSelected = (activeSubtitleTrack == null && choice.isSelected);
                    TextView btn = createSelectableButton(choice.label, isSelected);
                    btn.setOnClickListener(v -> {
                        exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(choice.group.getMediaTrackGroup(), choice.trackIndex))
                                .build());
                        activeSubtitleTrack = null;
                        trackStatus.setText("✓ Video: " + choice.label);
                        Toast.makeText(this, "Subtítulo: " + choice.label, Toast.LENGTH_SHORT).show();
                        if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
                    lp.setMargins(0, 0, 0, dp(6));
                    textLayout.addView(btn, lp);
                }
                layout.addView(textLayout);
            }
        }

        // Subtítulo externo
        layout.addView(createSectionTitle("SUBTÍTULO EXTERNO (.SRT / .VTT)"));
        LinearLayout trackButtons = new LinearLayout(this);
        trackButtons.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnPick = createDialogActionButton("CARGAR ARCHIVO EXTERNO", v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            pickSubtitle();
        });
        TextView btnOff = createDialogActionButton("DESACTIVAR TODO", v -> {
            activeSubtitleTrack = null;
            stereoSubtitleView.clear();
            if (usingVlc && vlcPlayer != null) {
                vlcPlayer.setSpuTrack(-1);
            } else if (!usingVlc && exoPlayer != null) {
                exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters()
                        .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            }
            trackStatus.setText("(Desactivado)");
            Toast.makeText(this, "Subtítulos desactivados", Toast.LENGTH_SHORT).show();
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        trackButtons.addView(btnPick, new LinearLayout.LayoutParams(0, dp(44), 1f));
        trackButtons.addView(btnOff, new LinearLayout.LayoutParams(dp(150), dp(44)));
        layout.addView(trackButtons);

        // 2. Modo 3D
        layout.addView(createSectionTitle("MODO DE VISUALIZACIÓN DE SUBTÍTULOS"));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] modeLabels = {"2D (Centrado)", "3D SBS (En video)"};
        int[] modeValues = {
                StereoSubtitleView.MODE_2D,
                StereoSubtitleView.MODE_SBS
        };
        for (int i = 0; i < modeLabels.length; i++) {
            final int m = modeValues[i];
            final String lbl = modeLabels[i];
            TextView btnMode = createSelectableButton(lbl, activeStereoMode == m);
            btnMode.setOnClickListener(v -> {
                applySubtitleMode(m);
                updateSelectableRow(modeRow, btnMode);
            });
            modeRow.addView(btnMode, spacedParam());
        }
        layout.addView(modeRow);

        // 3. Profundidad 3D / Paralaje (Para modo 3D SBS)
        layout.addView(createSectionTitle("PROFUNDIDAD 3D / PARALAJE"));
        LinearLayout parallaxRow = new LinearLayout(this);
        parallaxRow.setOrientation(LinearLayout.HORIZONTAL);
        parallaxRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView pMinus = createDialogActionButton("–", null);
        TextView pLabel = new TextView(this);
        pLabel.setTextColor(0xFFFFFFFF);
        pLabel.setTextSize(14f);
        pLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pLabel.setGravity(Gravity.CENTER);
        pLabel.setText(formatParallaxText(stereoSubtitleView.getParallaxOffsetPx()));
        TextView pPlus = createDialogActionButton("+", null);

        pMinus.setOnClickListener(v -> {
            int current = stereoSubtitleView.getParallaxOffsetPx() - 4;
            stereoSubtitleView.setParallaxOffsetPx(current);
            pLabel.setText(formatParallaxText(current));
            if (activeStereoMode == StereoSubtitleView.MODE_SBS) {
                applySubtitleMode(StereoSubtitleView.MODE_SBS);
            }
        });
        pPlus.setOnClickListener(v -> {
            int current = stereoSubtitleView.getParallaxOffsetPx() + 4;
            stereoSubtitleView.setParallaxOffsetPx(current);
            pLabel.setText(formatParallaxText(current));
            if (activeStereoMode == StereoSubtitleView.MODE_SBS) {
                applySubtitleMode(StereoSubtitleView.MODE_SBS);
            }
        });

        parallaxRow.addView(pMinus, new LinearLayout.LayoutParams(dp(54), dp(40)));
        parallaxRow.addView(pLabel, new LinearLayout.LayoutParams(0, dp(40), 1f));
        parallaxRow.addView(pPlus, new LinearLayout.LayoutParams(dp(54), dp(40)));
        layout.addView(parallaxRow);

        // 4. Tamaño de texto
        layout.addView(createSectionTitle("TAMAÑO DE TEXTO"));
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        float[] sizeValues = {16f, 20f, 25f, 30f};
        String[] sizeLabels = {"Pequeño", "Normal", "Grande", "Extra"};
        for (int i = 0; i < sizeValues.length; i++) {
            final float sz = sizeValues[i];
            TextView btnSize = createSelectableButton(sizeLabels[i], Math.abs(stereoSubtitleView.getTextSizeSp() - sz) < 1f);
            btnSize.setOnClickListener(v -> {
                stereoSubtitleView.setTextSizeSp(sz);
                updateSelectableRow(sizeRow, btnSize);
                if (activeStereoMode == StereoSubtitleView.MODE_SBS) {
                    applySubtitleMode(StereoSubtitleView.MODE_SBS);
                }
            });
            sizeRow.addView(btnSize, spacedParam());
        }
        layout.addView(sizeRow);

        // 5. Sincronización / Retraso
        layout.addView(createSectionTitle("SINCRONIZACIÓN / RETRASO"));
        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView sMinus = createDialogActionButton("–0.5s", null);
        TextView sLabel = new TextView(this);
        sLabel.setTextColor(0xFFFFFFFF);
        sLabel.setTextSize(14f);
        sLabel.setGravity(Gravity.CENTER);
        sLabel.setText(formatSyncText(stereoSubtitleView.getSyncOffsetMs()));
        TextView sPlus = createDialogActionButton("+0.5s", null);
        TextView sReset = createDialogActionButton("0s", null);

        sMinus.setOnClickListener(v -> {
            long newSync = stereoSubtitleView.getSyncOffsetMs() - 500L;
            stereoSubtitleView.setSyncOffsetMs(newSync);
            sLabel.setText(formatSyncText(newSync));
        });
        sPlus.setOnClickListener(v -> {
            long newSync = stereoSubtitleView.getSyncOffsetMs() + 500L;
            stereoSubtitleView.setSyncOffsetMs(newSync);
            sLabel.setText(formatSyncText(newSync));
        });
        sReset.setOnClickListener(v -> {
            stereoSubtitleView.setSyncOffsetMs(0L);
            sLabel.setText(formatSyncText(0L));
        });

        syncRow.addView(sMinus, new LinearLayout.LayoutParams(dp(72), dp(40)));
        syncRow.addView(sLabel, new LinearLayout.LayoutParams(0, dp(40), 1f));
        syncRow.addView(sPlus, new LinearLayout.LayoutParams(dp(72), dp(40)));
        syncRow.addView(sReset, new LinearLayout.LayoutParams(dp(54), dp(40)));
        layout.addView(syncRow);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Subtítulos")
                .setView(scrollView)
                .setPositiveButton("LISTO", (d, w) -> resumeChromeTimeout())
                .create();
        dialogHolder[0] = dialog;

        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.setOnDismissListener(ignored -> {
            enterImmersiveMode();
            resumeChromeTimeout();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility());
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    private void applySubtitleMode(int mode) {
        activeStereoMode = mode;
        if (mode == StereoSubtitleView.MODE_SBS) {
            if (usingVlc && vlcPlayer != null && activeSubtitleTrack != null) {
                int vw = 1920;
                int vh = 1080;
                IMedia.VideoTrack vt = vlcPlayer.getCurrentVideoTrack();
                if (vt != null && vt.width > 0 && vt.height > 0) {
                    vw = vt.width;
                    vh = vt.height;
                } else if (vlcSurface != null && vlcSurface.getWidth() > 0) {
                    vw = vlcSurface.getWidth();
                    vh = vlcSurface.getHeight();
                }
                File ass = SubtitleParser.generateSbsAssFile(
                        getCacheDir(),
                        activeSubtitleTrack,
                        vw,
                        vh,
                        stereoSubtitleView.getParallaxOffsetPx(),
                        stereoSubtitleView.getTextSizeSp());
                if (ass != null && ass.exists()) {
                    boolean ok = vlcPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(ass), true);
                    stereoSubtitleView.clear();
                    Toast.makeText(this, ok ? "Subtítulo 3D SBS integrado en video (3D real)" : "Error al agregar subtítulo a VLC", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else if (activeSubtitleTrack != null) {
                Toast.makeText(this, "Modo 3D SBS requiere reproducción en VLC", Toast.LENGTH_SHORT).show();
            }
        }

        // Modo 2D (Centrado en plano nítido) o fallback
        if (usingVlc && vlcPlayer != null) {
            vlcPlayer.setSpuTrack(-1);
        }
        stereoSubtitleView.setMode(StereoSubtitleView.MODE_2D);
        if (activeSubtitleTrack != null) {
            stereoSubtitleView.setSubtitleTrack(activeSubtitleTrack);
        }
    }

    private static final class Media3TextChoice {
        final Tracks.Group group;
        final int trackIndex;
        final String label;
        final boolean isSelected;

        Media3TextChoice(Tracks.Group group, int trackIndex, String label, boolean isSelected) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.label = label;
            this.isSelected = isSelected;
        }
    }

    private List<Media3TextChoice> getMedia3TextChoices() {
        List<Media3TextChoice> choices = new ArrayList<>();
        if (exoPlayer == null) return choices;
        for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                String label = buildMedia3TextLabel(format, choices.size());
                choices.add(new Media3TextChoice(group, i, label, group.isTrackSelected(i)));
            }
        }
        return choices;
    }

    private String buildMedia3TextLabel(Format format, int index) {
        String name = cleanTrackText(format.label);
        if (name == null) {
            name = "Pista " + (index + 1);
        }
        String lang = displayLanguage(format.language);
        return lang != null ? (name + " (" + lang + ")") : name;
    }

    private boolean isAnyMedia3TextSelected() {
        if (exoPlayer == null) return false;
        for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
                return true;
            }
        }
        return false;
    }

    private void autoSelectVlcSubtitleIfAvailable() {
        if (vlcPlayer == null || activeSubtitleTrack != null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        if (tracks == null || tracks.length <= 1) return;

        int currentTrack = vlcPlayer.getSpuTrack();
        if (currentTrack != -1) return;

        int targetId = -1;
        String targetName = null;
        for (MediaPlayer.TrackDescription td : tracks) {
            if (td.id == -1) continue;
            String lower = td.name.toLowerCase(Locale.US);
            if (lower.contains("spanish") || lower.contains("español") || lower.contains("spa")
                    || lower.contains("[es]") || lower.contains("es-") || lower.contains("latino")) {
                targetId = td.id;
                targetName = td.name;
                break;
            }
        }
        if (targetId == -1) {
            // Si no hay español, tomar la primera pista válida
            for (MediaPlayer.TrackDescription td : tracks) {
                if (td.id != -1) {
                    targetId = td.id;
                    targetName = td.name;
                    break;
                }
            }
        }
        if (targetId != -1) {
            vlcPlayer.setSpuTrack(targetId);
            Toast.makeText(this, "Subtítulo detectado: " + targetName, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Subtítulo VLC detectado y activado: " + targetName + " (id=" + targetId + ")");
        }
    }

    private void autoSelectMedia3SubtitleIfAvailable(Tracks tracks) {
        if (tracks == null || activeSubtitleTrack != null || exoPlayer == null) return;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
                return;
            }
        }
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                String lang = format.language != null ? format.language.toLowerCase(Locale.US) : "";
                String label = format.label != null ? format.label.toLowerCase(Locale.US) : "";
                if (lang.contains("es") || lang.contains("spa") || label.contains("spanish")
                        || label.contains("español") || label.contains("latino")) {
                    exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
                            .build());
                    String name = format.label != null ? format.label : "Español";
                    Toast.makeText(this, "Subtítulo detectado: " + name, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
    }

    private TextView createSectionTitle(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFF8899A6);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(6));
        return tv;
    }

    private TextView createDialogActionButton(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(12f);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1E293B);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x4454E0C7);
        btn.setBackground(bg);
        btn.setOnClickListener(listener);
        return btn;
    }

    private TextView createSelectableButton(String text, boolean selected) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12f);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        styleSelectableButton(btn, selected);
        return btn;
    }

    private void styleSelectableButton(TextView btn, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        if (selected) {
            bg.setColor(0xFF20726F);
            bg.setStroke(dp(2), 0xFF54E0C7);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0xCC111822);
            bg.setStroke(dp(1), 0x33445566);
            btn.setTextColor(0xFF8899A6);
        }
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
    }

    private void updateSelectableRow(LinearLayout row, TextView selectedBtn) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView) {
                styleSelectableButton((TextView) child, child == selectedBtn);
            }
        }
    }

    private LinearLayout.LayoutParams spacedParam() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private String formatParallaxText(int px) {
        if (px == 0) return "0 px (En plano)";
        if (px > 0) return "+" + px + " px (Flotante al frente)";
        return px + " px (Hacia el fondo)";
    }

    private String formatSyncText(long ms) {
        if (ms == 0) return "0.0 s";
        double s = ms / 1000.0;
        return String.format(Locale.US, "%+.1f s", s);
    }

    private void playPending() {
        enterImmersiveMode();
        saveActivePlaybackPosition();
        stopPlayers();
        if (stereoSubtitleView != null) {
            stereoSubtitleView.clear();
            stereoSubtitleView.setVideoHint(pendingName);
        }
        if (pendingSmbFile != null) {
            if (pendingSmbFile.cachedSubtitleFile != null) {
                activeSubtitleTrack = SubtitleParser.parseFile(pendingSmbFile.cachedSubtitleFile);
                if (activeSubtitleTrack != null) {
                    applySubtitleMode(activeStereoMode);
                    String subName = pendingSmbFile.cachedSubtitleFile.getName().replaceFirst("^smb_sub_\\d+_", "");
                    Toast.makeText(this, "Subtítulo SMB detectado: " + subName, Toast.LENGTH_SHORT).show();
                }
            } else {
                activeSubtitleTrack = null;
            }
        } else {
            activeSubtitleTrack = null;
            searchLocalCompanionSubtitle(pendingUri, pendingName);
        }

        activePlaybackKey = playbackKey(pendingUri);
        long resumePosition = loadActivePlaybackPosition();
        lastPositionSaveElapsedMs = SystemClock.elapsedRealtime();
        if (resumePosition >= MIN_RESUME_POSITION_MS) {
            showResumeDialog(resumePosition);
        } else {
            startSelectedMedia(0L);
        }
    }

    private void showResumeDialog(long resumePosition) {
        showChrome();
        uiHandler.removeCallbacks(hideChromeRunnable);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Resume playback?")
                .setMessage("Continue from " + formatTime(resumePosition) + " or start from the beginning?")
                .setNegativeButton("START OVER", (ignored, which) -> {
                    clearActivePlaybackPosition();
                    startSelectedMedia(0L);
                })
                .setPositiveButton("RESUME", (ignored, which) -> startSelectedMedia(resumePosition))
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(ignored -> enterImmersiveMode());
        dialog.show();
    }

    private void startSelectedMedia(long resumePosition) {
        enterImmersiveMode();
        if (pendingSmbFile != null) {
            startVlcFallback(resumePosition, "SMB network stream");
            return;
        }
        if (isMatroska(pendingName, pendingUri)) {
            startVlcFallback(resumePosition, "PCM fallback for MKV audio");
            return;
        }
        usingVlc = false;
        vlcSurface.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        exoPlayer.setMediaItem(MediaItem.fromUri(pendingUri), resumePosition);
        exoPlayer.prepare();
        exoPlayer.play();
        showResumeToast(resumePosition);
        hideChromeSoon();
        updateStatus("Media3 active");
    }

    private void startVlcFallback(long startPositionMs, String reason) {
        enterImmersiveMode();
        if (pendingUri == null) {
            return;
        }
        long resumePosition = Math.max(0L, startPositionMs);
        exoPlayer.stop();
        ensureVlc();
        usingVlc = true;
        playerView.setVisibility(View.GONE);
        vlcSurface.setVisibility(View.VISIBLE);

        pendingVlcPosition = resumePosition;
        pendingVlcReason = reason;
        vlcPlaybackPending = true;
        startPendingVlcPlayback();
    }

    private void startPendingVlcPlayback() {
        if (!vlcPlaybackPending || !vlcSurface.getHolder().getSurface().isValid()) {
            return;
        }
        vlcPlaybackPending = false;
        attachVlcSurface();

        Media media;
        closeVlcInput();
        if (pendingSmbFile != null) {
            media = new Media(libVlc, pendingUri);
            if (!pendingSmbFile.username.isEmpty()) {
                media.addOption(":smb-user=" + pendingSmbFile.username);
                media.addOption(":smb-pwd=" + pendingSmbFile.passwordOption());
                if (!pendingSmbFile.domain.isEmpty()) {
                    media.addOption(":smb-domain=" + pendingSmbFile.domain);
                }
            }
            media.addOption(":network-caching=3000");
        } else {
            try {
                vlcInput = getContentResolver().openFileDescriptor(pendingUri, "r");
            } catch (IOException | SecurityException error) {
                Log.e(TAG, "Could not open the MKV file descriptor", error);
                updateStatus("No permission to open the MKV");
                Toast.makeText(this, "Could not open the selected file", Toast.LENGTH_LONG).show();
                return;
            }
            if (vlcInput == null) {
                updateStatus("Empty file descriptor");
                return;
            }
            media = new Media(libVlc, vlcInput.getFileDescriptor());
        }
        appliedPackedAspect = null;
        vlcPlayer.setAspectRatio(null);
        vlcPlayer.setScale(0f);
        media.setHWDecoderEnabled(true, false);
        media.addOption(":no-audio-passthrough");
        media.addOption(":audio-replay-gain-mode=none");
        vlcPlayer.setMedia(media);
        media.release();
        if (pendingSmbFile == null) {
            applyPackedAspectFromMetadata();
        }
        vlcPlayer.setAudioDigitalOutputEnabled(false);
        if (activeSubtitleTrack != null) {
            vlcPlayer.setSpuTrack(-1);
        }
        boolean willResume = pendingVlcPosition >= MIN_RESUME_POSITION_MS;
        vlcPlayer.play();
        hideChromeSoon();
        updateStatus("VLC: " + pendingVlcReason);
        if (!willResume) {
            Toast.makeText(this,
                    pendingSmbFile != null ? "Streaming from SMB" : "Compatible audio enabled (VLC/PCM)",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void ensureVlc() {
        if (libVlc != null) {
            return;
        }
        ArrayList<String> options = new ArrayList<>();
        options.add("--audio-resampler=soxr");
        libVlc = new LibVLC(getApplicationContext(), options);
        vlcPlayer = new MediaPlayer(libVlc);
        vlcPlayer.setAudioDigitalOutputEnabled(false);
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Playing) {
                runOnUiThread(() -> {
                    long resumePosition = pendingVlcPosition;
                    pendingVlcPosition = 0L;
                    if (resumePosition >= MIN_RESUME_POSITION_MS) {
                        vlcPlayer.setTime(resumePosition);
                        showResumeToast(resumePosition);
                    }
                    IMedia.VideoTrack videoTrack = vlcPlayer.getCurrentVideoTrack();
                    if (videoTrack != null) {
                        Log.i(TAG, "Active VLC video track: "
                                + videoTrack.width + "x" + videoTrack.height);
                        adjustPackedStereoAspect(videoTrack.width, videoTrack.height,
                                videoTrack.sarNum, videoTrack.sarDen);
                    }
                    if (activeSubtitleTrack != null) {
                        applySubtitleMode(activeStereoMode);
                    } else {
                        autoSelectVlcSubtitleIfAvailable();
                    }
                    updateStatus("VLC active, PCM audio");
                    hideChromeSoon();
                });
            } else if (event.type == MediaPlayer.Event.EndReached) {
                runOnUiThread(this::clearActivePlaybackPosition);
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                runOnUiThread(() -> {
                    updateStatus("LibVLC error");
                    Toast.makeText(this, "This file could not be decoded", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void attachVlcSurface() {
        if (vlcPlayer == null || !vlcSurface.getHolder().getSurface().isValid()) {
            return;
        }
        if (vlcPlayer.getVLCVout().areViewsAttached()) {
            vlcPlayer.getVLCVout().detachViews();
        }
        vlcPlayer.getVLCVout().setVideoView(vlcSurface);
        vlcPlayer.getVLCVout().attachViews((vlcVout, width, height,
                visibleWidth, visibleHeight, sarNum, sarDen) ->
                adjustPackedStereoAspect(visibleWidth, visibleHeight, sarNum, sarDen));
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            vlcPlayer.getVLCVout().setWindowSize(surfaceWidth, surfaceHeight);
        }
    }

    private void adjustPackedStereoAspect(int width, int height, int sarNum, int sarDen) {
        if (width <= 0 || height <= 0 || vlcPlayer == null) {
            return;
        }
        int safeSarNum = sarNum > 0 ? sarNum : 1;
        int safeSarDen = sarDen > 0 ? sarDen : 1;
        double packedRatio = (width * (double) safeSarNum) / (height * (double) safeSarDen);

        long aspectWidth;
        long aspectHeight;
        String layout;
        if (packedRatio > 2.75d) {
            aspectWidth = (long) width * safeSarNum;
            aspectHeight = 2L * height * safeSarDen;
            layout = "Full-SBS";
        } else if (looksLikeFullTopBottom()) {
            aspectWidth = 2L * width * safeSarNum;
            aspectHeight = (long) height * safeSarDen;
            layout = "Full Top/Bottom";
        } else {
            return;
        }

        long divisor = greatestCommonDivisor(aspectWidth, aspectHeight);
        String aspect = (aspectWidth / divisor) + ":" + (aspectHeight / divisor);
        if (aspect.equals(appliedPackedAspect)) {
            return;
        }
        appliedPackedAspect = aspect;
        Log.i(TAG, layout + " normalized for native transport " + aspect);
        vlcPlayer.setAspectRatio(aspect);
        vlcPlayer.setScale(0f);
        vlcPlayer.updateVideoSurfaces();
    }

    private void applyPackedAspectFromMetadata() {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, pendingUri);
            String widthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (widthValue != null && heightValue != null) {
                int width = Integer.parseInt(widthValue);
                int height = Integer.parseInt(heightValue);
                Log.i(TAG, "Dimensions detected before playback: " + width + "x" + height);
                adjustPackedStereoAspect(width, height, 1, 1);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read video dimensions before playback", error);
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Could not release MediaMetadataRetriever", error);
            }
        }
    }

    private boolean looksLikeFullTopBottom() {
        String name = pendingName.toLowerCase(Locale.US);
        return name.contains("full-tb")
                || name.contains("full_tb")
                || name.contains("full top")
                || name.contains("full-ou")
                || name.contains("full_ou")
                || name.contains("ftab");
    }

    private long greatestCommonDivisor(long first, long second) {
        long a = Math.abs(first);
        long b = Math.abs(second);
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a == 0 ? 1 : a;
    }

    private void pauseActivePlayer() {
        saveActivePlaybackPosition();
        if (usingVlc && vlcPlayer != null && vlcPlayer.isPlaying()) {
            vlcPlayer.pause();
        } else if (exoPlayer != null) {
            exoPlayer.pause();
        }
        showChrome();
    }

    private void togglePlayback() {
        if (isActivePlayerPlaying()) {
            pauseActivePlayer();
        } else {
            playOrResume();
        }
        updatePlayPauseControl();
    }

    private void saveActivePlaybackPosition() {
        if (activePlaybackKey == null) {
            return;
        }
        long position = getPlaybackPosition();
        long duration = getPlaybackDuration();
        SharedPreferences.Editor editor = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit();
        if (position < MIN_RESUME_POSITION_MS
                || (duration > 0L && duration - position <= COMPLETION_MARGIN_MS)) {
            editor.remove(activePlaybackKey);
        } else {
            editor.putLong(activePlaybackKey, position);
        }
        editor.apply();
        lastPositionSaveElapsedMs = SystemClock.elapsedRealtime();
    }

    private long loadActivePlaybackPosition() {
        if (activePlaybackKey == null) {
            return 0L;
        }
        long position = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE)
                .getLong(activePlaybackKey, 0L);
        return position >= MIN_RESUME_POSITION_MS ? position : 0L;
    }

    private void clearActivePlaybackPosition() {
        if (activePlaybackKey == null) {
            return;
        }
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE)
                .edit()
                .remove(activePlaybackKey)
                .apply();
        lastPositionSaveElapsedMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Cleared completed playback position");
    }

    private String playbackKey(Uri uri) {
        String identity = uri == null ? "" : uri.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("media_");
            for (byte value : digest) {
                key.append(String.format(Locale.US, "%02x", value & 0xFF));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "media_" + Integer.toHexString(identity.hashCode());
        }
    }

    private void showResumeToast(long positionMs) {
        if (positionMs >= MIN_RESUME_POSITION_MS) {
            Toast.makeText(this, "Resuming at " + formatTime(positionMs), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isActivePlayerPlaying() {
        if (usingVlc && vlcPlayer != null) {
            return vlcPlayer.isPlaying();
        }
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    private void seekRelative(long offsetMs) {
        long duration = getPlaybackDuration();
        if (duration <= 0) {
            return;
        }
        long target = Math.max(0L, Math.min(duration, getPlaybackPosition() + offsetMs));
        if (usingVlc && vlcPlayer != null) {
            vlcPlayer.setTime(target);
        } else if (exoPlayer != null) {
            exoPlayer.seekTo(target);
        }
        updatePlaybackProgress();
        hideChromeSoon();
    }

    private void updatePlayPauseControl() {
        if (playPauseView != null) {
            playPauseView.setText(isActivePlayerPlaying() ? "PAUSE" : "PLAY");
        }
    }

    private void stopPlayers() {
        vlcPlaybackPending = false;
        exoPlayer.stop();
        if (vlcPlayer != null) {
            vlcPlayer.stop();
        }
        closeVlcInput();
    }

    private void releaseVlc() {
        if (vlcPlayer != null) {
            vlcPlayer.getVLCVout().detachViews();
            vlcPlayer.release();
            vlcPlayer = null;
        }
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        closeVlcInput();
    }

    private void closeVlcInput() {
        if (vlcInput == null) {
            return;
        }
        try {
            vlcInput.close();
        } catch (IOException error) {
            Log.w(TAG, "Could not close the MKV file descriptor", error);
        }
        vlcInput = null;
    }

    private void updatePlaybackProgress() {
        if (playbackSeekBar == null || userSeeking) {
            return;
        }
        long duration = getPlaybackDuration();
        long position = getPlaybackPosition();
        if (duration > 0) {
            int progress = (int) Math.min(1000L, position * 1000L / duration);
            playbackSeekBar.setEnabled(true);
            playbackSeekBar.setProgress(progress);
            playbackTimeView.setText(formatTime(position));
            durationTimeView.setText(formatTime(duration));
        } else {
            playbackSeekBar.setEnabled(true);
            playbackSeekBar.setProgress(0);
            playbackTimeView.setText("00:00");
            durationTimeView.setText("00:00");
        }
        if (stereoSubtitleView != null) {
            stereoSubtitleView.updateTime(position);
        }
        updatePlayPauseControl();
        long elapsed = SystemClock.elapsedRealtime();
        if (isActivePlayerPlaying()
                && elapsed - lastPositionSaveElapsedMs >= POSITION_SAVE_INTERVAL_MS) {
            saveActivePlaybackPosition();
        }
    }

    private void updateTimeForProgress(int progress) {
        long duration = getPlaybackDuration();
        if (duration > 0) {
            long target = duration * progress / 1000L;
            playbackTimeView.setText(formatTime(target));
            durationTimeView.setText(formatTime(duration));
        }
    }

    private void seekToProgress(int progress) {
        long duration = getPlaybackDuration();
        if (duration <= 0) {
            return;
        }
        long target = duration * progress / 1000L;
        if (usingVlc && vlcPlayer != null) {
            vlcPlayer.setTime(target);
        } else if (exoPlayer != null) {
            exoPlayer.seekTo(target);
        }
    }

    private long getPlaybackDuration() {
        if (usingVlc && vlcPlayer != null) {
            return Math.max(0L, vlcPlayer.getLength());
        }
        if (exoPlayer == null || exoPlayer.getDuration() == C.TIME_UNSET) {
            return 0L;
        }
        return Math.max(0L, exoPlayer.getDuration());
    }

    private long getPlaybackPosition() {
        if (usingVlc && vlcPlayer != null) {
            return Math.max(0L, vlcPlayer.getTime());
        }
        return exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
    }

    private String formatTime(long timeMs) {
        long totalSeconds = Math.max(0L, timeMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private boolean isMatroska(String name, Uri uri) {
        String lowerName = name.toLowerCase(Locale.US);
        String mimeType = getContentResolver().getType(uri);
        return lowerName.endsWith(".mkv")
                || "video/x-matroska".equalsIgnoreCase(mimeType)
                || "video/mkv".equalsIgnoreCase(mimeType);
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read the file name", error);
        }
        return uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uri.toString();
    }

    private void updateStatus(String state) {
        if (statusView == null) {
            return;
        }
        statusView.setText("v1.1.4 | " + state
                + " | " + surfaceWidth + "x" + surfaceHeight
                + " | 3DFV");
        if (titleView != null) {
            titleView.setText("No file selected".equals(pendingName) ? "SKYY MKV 3D" : pendingName);
        }
    }

    private void hideChromeSoon() {
        uiHandler.removeCallbacks(hideChromeRunnable);
        uiHandler.postDelayed(hideChromeRunnable, 5000);
    }

    private void showChromeTemporarily() {
        showChrome();
        if ((usingVlc && vlcPlayer != null && vlcPlayer.isPlaying())
                || (!usingVlc && exoPlayer != null && exoPlayer.isPlaying())) {
            uiHandler.postDelayed(hideChromeRunnable, 5000);
        }
    }

    private void showChrome() {
        if (chrome == null) {
            return;
        }
        uiHandler.removeCallbacks(hideChromeRunnable);
        chrome.animate().cancel();
        chrome.setAlpha(1f);
        chrome.setVisibility(View.VISIBLE);
        Log.i(TAG, "Playback controls visible");
    }

    private void hideChrome() {
        if (chrome == null) {
            return;
        }
        chrome.animate().cancel();
        chrome.setAlpha(0f);
        chrome.setVisibility(View.GONE);
        Log.i(TAG, "Playback controls hidden");
        enterImmersiveMode();
    }
}
