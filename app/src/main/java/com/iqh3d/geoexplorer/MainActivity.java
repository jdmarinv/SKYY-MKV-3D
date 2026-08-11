package com.iqh3d.geoexplorer;

import android.app.Activity;
import android.content.Intent;
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
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "SkyyMkvPlayer";
    private static final int PICK_VIDEO = 1001;

    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private SurfaceView vlcSurface;
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
    private String pendingName = "No file selected";
    private boolean usingVlc;
    private boolean vlcPlaybackPending;
    private long pendingVlcPosition;
    private String pendingVlcReason;
    private String appliedPackedAspect;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean userSeeking;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        enterImmersiveMode();

        buildExoPlayer();
        setContentView(buildLayout());
        configureSurface(playerView.getVideoSurfaceView());
        updateStatus("Ready");
        uiHandler.post(updateProgressRunnable);
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
        TextView audio = createTextControl("AUDIO", 13f, v -> Toast.makeText(this,
                usingVlc ? "VLC/PCM audio active" : "Media3 audio active", Toast.LENGTH_SHORT).show());
        TextView mode = createTextControl("3D", 13f, v -> Toast.makeText(this,
                "Use the 3DFV selector on the left edge", Toast.LENGTH_SHORT).show());
        topBar.addView(open, new LinearLayout.LayoutParams(dp(88), dp(54)));
        topBar.addView(audio, new LinearLayout.LayoutParams(dp(88), dp(54)));
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
        uiHandler.removeCallbacksAndMessages(null);
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        releaseVlc();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK || data == null) {
            return;
        }
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
        pendingName = queryDisplayName(uri);
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

    private void playPending() {
        stopPlayers();
        if (isMatroska(pendingName, pendingUri)) {
            startVlcFallback(0, "PCM fallback for MKV audio");
            return;
        }
        usingVlc = false;
        vlcSurface.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        exoPlayer.setMediaItem(MediaItem.fromUri(pendingUri));
        exoPlayer.prepare();
        exoPlayer.play();
        hideChromeSoon();
        updateStatus("Media3 active");
    }

    private void startVlcFallback(long startPositionMs, String reason) {
        if (pendingUri == null) {
            return;
        }
        long resumePosition = Math.max(startPositionMs, exoPlayer.getCurrentPosition());
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

        closeVlcInput();
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

        Media media = new Media(libVlc, vlcInput.getFileDescriptor());
        appliedPackedAspect = null;
        vlcPlayer.setAspectRatio(null);
        vlcPlayer.setScale(0f);
        media.setHWDecoderEnabled(true, false);
        media.addOption(":no-audio-passthrough");
        media.addOption(":audio-replay-gain-mode=none");
        vlcPlayer.setMedia(media);
        media.release();
        applyPackedAspectFromMetadata();
        vlcPlayer.setAudioDigitalOutputEnabled(false);
        vlcPlayer.play();
        hideChromeSoon();
        if (pendingVlcPosition > 0) {
            vlcPlayer.setTime(pendingVlcPosition);
        }
        updateStatus("VLC: " + pendingVlcReason);
        Toast.makeText(this, "Compatible audio enabled (VLC/PCM)", Toast.LENGTH_SHORT).show();
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
                    updateStatus("VLC active, PCM audio");
                    hideChromeSoon();
                });
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
        updatePlayPauseControl();
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
        statusView.setText("v1.0.0 | " + state
                + " | " + surfaceWidth + "x" + surfaceHeight
                + " | 3DFV");
        if (titleView != null) {
            titleView.setText("No file selected".equals(pendingName) ? "SKYY MKV 3D" : pendingName);
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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
    }
}
