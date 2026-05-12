package org.dolphinemu.dolphinemu.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Compact two-dot stick visualization used in the calibration wizard.
 *
 * Draws:
 *   - the octagonal Melee stick gate (outline)
 *   - a small "raw" dot at the controller's current physical-stick
 *     position (faint grey)
 *   - a larger "calibrated" dot at what the game actually sees after
 *     deadzone + curve are applied (slippi green)
 *   - the optional captured-min/max box from the wizard's roll phase,
 *     so the user can see whether they've hit every cardinal yet.
 *
 * All coordinates are in -1..+1; the view scales them into pixel space
 * based on the smaller of its width/height each draw. Calls to
 * {@link #setRaw}/{@link #setCalibrated}/{@link #setCaptured} are
 * cheap and trigger an invalidate.
 */
public class StickPreviewView extends View {

    private static final float CAL_DOT_RADIUS_DP = 8f;
    private static final float RAW_DOT_RADIUS_DP = 5f;

    private final Paint gatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capturedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint calPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path gatePath = new Path();

    private float rawX, rawY;
    private float calX, calY;
    private float capMinX = Float.POSITIVE_INFINITY;
    private float capMaxX = Float.NEGATIVE_INFINITY;
    private float capMinY = Float.POSITIVE_INFINITY;
    private float capMaxY = Float.NEGATIVE_INFINITY;
    private boolean hasCaptured = false;

    public StickPreviewView(Context c) { super(c); init(c); }
    public StickPreviewView(Context c, AttributeSet a) { super(c, a); init(c); }
    public StickPreviewView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init(c);
    }

    private void init(Context c) {
        gatePaint.setStyle(Paint.Style.STROKE);
        gatePaint.setStrokeWidth(dp(c, 1.5f));
        gatePaint.setColor(0x4D8FA398);   // text_secondary @ ~30 %

        capturedPaint.setStyle(Paint.Style.FILL);
        capturedPaint.setColor(0x2244A963); // slippi_green @ ~13 %

        rawPaint.setStyle(Paint.Style.FILL);
        rawPaint.setColor(0x998FA398);    // text_secondary @ 60 %

        calPaint.setStyle(Paint.Style.FILL);
        calPaint.setColor(0xFF44A963);    // slippi_green
    }

    public void setRaw(float x, float y) {
        rawX = clamp(x);
        rawY = clamp(y);
        invalidate();
    }

    public void setCalibrated(float x, float y) {
        calX = clamp(x);
        calY = clamp(y);
        invalidate();
    }

    public void setCaptured(float minX, float maxX, float minY, float maxY) {
        capMinX = minX;
        capMaxX = maxX;
        capMinY = minY;
        capMaxY = maxY;
        hasCaptured = !(Float.isInfinite(minX) || Float.isInfinite(maxX)
                || Float.isInfinite(minY) || Float.isInfinite(maxY));
        invalidate();
    }

    public void resetCaptured() {
        capMinX = Float.POSITIVE_INFINITY;
        capMaxX = Float.NEGATIVE_INFINITY;
        capMinY = Float.POSITIVE_INFINITY;
        capMaxY = Float.NEGATIVE_INFINITY;
        hasCaptured = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float cx = w * 0.5f;
        final float cy = h * 0.5f;
        // Inset so the dot at ±1 doesn't clip on the edge.
        final float r = Math.min(w, h) * 0.46f;

        // Octagonal gate. Approximates the GC stick's restrictor — the
        // diagonals sit at ~0.71 magnitude, cardinals at ~0.95.
        gatePath.rewind();
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 / 8.0) * i - Math.PI / 2.0;
            float px = cx + (float) Math.cos(angle) * r;
            float py = cy + (float) Math.sin(angle) * r;
            if (i == 0) gatePath.moveTo(px, py);
            else gatePath.lineTo(px, py);
        }
        gatePath.close();
        canvas.drawPath(gatePath, gatePaint);

        // Captured min/max — a translucent green box showing the user
        // how much of the stick range they've reached so far.
        if (hasCaptured) {
            float l = cx + capMinX * r;
            float t = cy + capMinY * r;
            float right = cx + capMaxX * r;
            float bottom = cy + capMaxY * r;
            // Keep within the gate bounding box just for tidy visuals.
            l = Math.max(l, cx - r);
            t = Math.max(t, cy - r);
            right = Math.min(right, cx + r);
            bottom = Math.min(bottom, cy + r);
            if (right > l && bottom > t)
                canvas.drawRect(l, t, right, bottom, capturedPaint);
        }

        // Raw dot — what the OS / kernel is reporting right now.
        canvas.drawCircle(cx + rawX * r, cy + rawY * r,
                dp(getContext(), RAW_DOT_RADIUS_DP), rawPaint);

        // Calibrated dot — what the game will actually see.
        canvas.drawCircle(cx + calX * r, cy + calY * r,
                dp(getContext(), CAL_DOT_RADIUS_DP), calPaint);
    }

    private static float clamp(float v) {
        if (v < -1f) return -1f;
        if (v > 1f) return 1f;
        return v;
    }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }
}
