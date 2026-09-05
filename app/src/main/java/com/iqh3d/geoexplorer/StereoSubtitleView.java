package com.iqh3d.geoexplorer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Vista de subtítulos estereoscópicos para visualización 3D en la tableta IQH3D SKYY.
 * Duplica y posiciona los subtítulos según el formato del video:
 * - SBS (Side-by-Side): Renderiza para ojo izquierdo y ojo derecho con ajuste de paralaje 3D.
 * - TAB (Top-and-Bottom): Renderiza para ojo superior y ojo inferior.
 * - 2D: Renderiza un subtítulo estándar centrado.
 */
public final class StereoSubtitleView extends View {
    public static final int MODE_AUTO = 0;
    public static final int MODE_SBS = 1;
    public static final int MODE_TAB = 2;
    public static final int MODE_2D = 3;

    private int currentMode = MODE_2D;
    private int resolvedMode = MODE_2D;
    private String videoTitleHint = "";

    private SubtitleParser.SubtitleTrack currentTrack;
    private CharSequence activeText = "";
    private long currentPlaybackTimeMs = -1L;
    private long syncOffsetMs = 0L;
    private int parallaxOffsetPx = 12; // Desfase horizontal para profundidad estereoscópica (+ = flotar al frente)
    private float textSizeSp = 20f;

    private final TextPaint strokePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fillPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public StereoSubtitleView(Context context) {
        super(context);
        init();
    }

    public StereoSubtitleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StereoSubtitleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(dp(3.5f));

        fillPaint.setColor(Color.WHITE);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShadowLayer(dp(2f), 0, dp(1f), 0xCC000000);

        updateTextPaints();
    }

    private void updateTextPaints() {
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, getResources().getDisplayMetrics());
        strokePaint.setTextSize(px);
        fillPaint.setTextSize(px);
    }

    public void setSubtitleTrack(@Nullable SubtitleParser.SubtitleTrack track) {
        this.currentTrack = track;
        this.activeText = "";
        updateTime(currentPlaybackTimeMs);
        invalidate();
    }

    public void setExternalCueText(@Nullable CharSequence text) {
        this.currentTrack = null;
        CharSequence newText = (text != null) ? text : "";
        if (!activeText.toString().equals(newText.toString())) {
            this.activeText = newText;
            invalidate();
        }
    }

    public void updateTime(long timeMs) {
        this.currentPlaybackTimeMs = timeMs;
        if (currentTrack != null) {
            SubtitleParser.Cue cue = currentTrack.getCueAt(timeMs, syncOffsetMs);
            CharSequence newText = (cue != null) ? cue.text : "";
            if (!activeText.toString().equals(newText.toString())) {
                this.activeText = newText;
                invalidate();
            }
        }
    }

    public void setVideoHint(String title) {
        this.videoTitleHint = (title != null) ? title : "";
        resolveMode();
        invalidate();
    }

    public void setMode(int mode) {
        this.currentMode = mode;
        resolveMode();
        invalidate();
    }

    public int getMode() {
        return currentMode;
    }

    public int getResolvedMode() {
        return resolvedMode;
    }

    public void setParallaxOffsetPx(int offsetPx) {
        this.parallaxOffsetPx = offsetPx;
        invalidate();
    }

    public int getParallaxOffsetPx() {
        return parallaxOffsetPx;
    }

    public void setTextSizeSp(float sp) {
        this.textSizeSp = sp;
        updateTextPaints();
        invalidate();
    }

    public float getTextSizeSp() {
        return textSizeSp;
    }

    public void setSyncOffsetMs(long offsetMs) {
        this.syncOffsetMs = offsetMs;
        updateTime(currentPlaybackTimeMs);
        invalidate();
    }

    public long getSyncOffsetMs() {
        return syncOffsetMs;
    }

    public void clear() {
        this.activeText = "";
        this.currentTrack = null;
        invalidate();
    }

    private void resolveMode() {
        if (currentMode != MODE_AUTO) {
            resolvedMode = currentMode;
            return;
        }

        // Por defecto en pantalla autostereoscópica, el overlay UI 2D debe estar centrado
        // para verse nítido en el plano focal y no dividirse en 2 textos en la pantalla.
        resolvedMode = MODE_2D;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (activeText == null || activeText.length() == 0) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        if (resolvedMode == MODE_SBS) {
            // NOTA CRÍTICA DE HARDWARE 3D (IQH3D SKYY):
            // El servicio lenticular 3DFV del tablet sólo entrelaza buffers nativos de SurfaceView (video).
            // La capa de ventanas 2D de Android nunca se entrelaza por hardware.
            // Si dibujamos texto Side-by-Side aquí, el usuario ve 2 textos simultáneos a 10 cm de distancia.
            // Por ello, el 3D SBS real se delega a LibVLC (inyectando el subtítulo .ass en SurfaceView).
            // En este canvas overlay 2D, se renderiza siempre centrado para evitar subtítulos dobles.
            drawMono2D(canvas, width, height);
        } else if (resolvedMode == MODE_TAB) {
            drawStereoTab(canvas, width, height);
        } else {
            drawMono2D(canvas, width, height);
        }
    }

    /**
     * Renderizado Side-by-Side:
     * Ojo izquierdo ocupa [0, W/2]
     * Ojo derecho ocupa [W/2, W]
     * El paralaje desplaza el ojo izquierdo a la derecha (+dx) y el ojo derecho a la izquierda (-dx),
     * haciendo que converjan por delante del plano de la pantalla.
     */
    private void drawStereoSbs(Canvas canvas, int width, int height) {
        int halfWidth = width / 2;
        int maxLayoutWidth = (int) (halfWidth * 0.82f);
        StaticLayout layoutStroke = createLayout(activeText, strokePaint, maxLayoutWidth);
        StaticLayout layoutFill = createLayout(activeText, fillPaint, maxLayoutWidth);

        float bottomMargin = height * 0.08f;
        float y = height - bottomMargin - layoutFill.getHeight();

        // Ojo Izquierdo: centro en halfWidth / 2 = width * 0.25f + parallax
        float leftCenterX = (halfWidth * 0.5f) + parallaxOffsetPx;
        float leftX = leftCenterX - (layoutFill.getWidth() * 0.5f);

        canvas.save();
        canvas.clipRect(0, 0, halfWidth, height);
        canvas.translate(leftX, y);
        layoutStroke.draw(canvas);
        layoutFill.draw(canvas);
        canvas.restore();

        // Ojo Derecho: centro en halfWidth + halfWidth / 2 = width * 0.75f - parallax
        float rightCenterX = halfWidth + (halfWidth * 0.5f) - parallaxOffsetPx;
        float rightX = rightCenterX - (layoutFill.getWidth() * 0.5f);

        canvas.save();
        canvas.clipRect(halfWidth, 0, width, height);
        canvas.translate(rightX, y);
        layoutStroke.draw(canvas);
        layoutFill.draw(canvas);
        canvas.restore();
    }

    /**
     * Renderizado Top-and-Bottom:
     * Ojo superior ocupa [0, H/2]
     * Ojo inferior ocupa [H/2, H]
     */
    private void drawStereoTab(Canvas canvas, int width, int height) {
        int halfHeight = height / 2;
        int maxLayoutWidth = (int) (width * 0.85f);
        StaticLayout layoutStroke = createLayout(activeText, strokePaint, maxLayoutWidth);
        StaticLayout layoutFill = createLayout(activeText, fillPaint, maxLayoutWidth);

        float centerX = width * 0.5f;
        float x = centerX - (layoutFill.getWidth() * 0.5f);

        // Ojo Superior (Top Eye)
        float topMarginBottom = halfHeight * 0.08f;
        float topY = halfHeight - topMarginBottom - layoutFill.getHeight();

        canvas.save();
        canvas.clipRect(0, 0, width, halfHeight);
        canvas.translate(x, topY);
        layoutStroke.draw(canvas);
        layoutFill.draw(canvas);
        canvas.restore();

        // Ojo Inferior (Bottom Eye)
        float bottomMarginBottom = halfHeight * 0.08f;
        float bottomY = height - bottomMarginBottom - layoutFill.getHeight();

        canvas.save();
        canvas.clipRect(0, halfHeight, width, height);
        canvas.translate(x, bottomY);
        layoutStroke.draw(canvas);
        layoutFill.draw(canvas);
        canvas.restore();
    }

    /**
     * Renderizado 2D estándar centrado al fondo
     */
    private void drawMono2D(Canvas canvas, int width, int height) {
        int maxLayoutWidth = (int) (width * 0.85f);
        StaticLayout layoutStroke = createLayout(activeText, strokePaint, maxLayoutWidth);
        StaticLayout layoutFill = createLayout(activeText, fillPaint, maxLayoutWidth);

        float bottomMargin = height * 0.08f;
        float y = height - bottomMargin - layoutFill.getHeight();
        float x = (width * 0.5f) - (layoutFill.getWidth() * 0.5f);

        canvas.save();
        canvas.translate(x, y);
        layoutStroke.draw(canvas);
        layoutFill.draw(canvas);
        canvas.restore();
    }

    private StaticLayout createLayout(CharSequence text, TextPaint paint, int maxWidth) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.1f)
                    .setIncludePad(false)
                    .build();
        } else {
            return new StaticLayout(text, paint, maxWidth,
                    Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
