package org.dolphinemu.dolphinemu.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.TouchOverlayLayoutStore;
import org.dolphinemu.dolphinemu.controller.TouchOverlayLayoutStore.Control;
import org.dolphinemu.dolphinemu.controller.TouchOverlayLayoutStore.Layout;

public class TouchControlOverlayView extends View {
    public interface Listener {
        void onOverlayButton(int gcBit, boolean pressed);
        void onOverlayStick(String stickId, float x, float y);
        void onOverlayDpad(boolean up, boolean down, boolean left, boolean right);
        void onOverlayEditModeChanged(boolean editing);
    }

    private static final int COLOR_PANEL = 0xDD101815;
    private static final int COLOR_RING = 0xCCF0F3F1;
    private static final int COLOR_FILL = 0xAA111817;
    private static final int COLOR_SELECTED = 0xFFFFC857;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<ActiveTouch> activeTouches = new SparseArray<>();

    private Layout layout;
    private Layout savedEditLayout;
    private Listener listener;
    private boolean controlsEnabled = true;
    private boolean editMode;
    private String selectedControlId = TouchOverlayLayoutStore.MAIN_STICK;
    private int editPointerId = -1;
    private int editGesture;

    private final RectF editButtonRect = new RectF();
    private final RectF saveRect = new RectF();
    private final RectF cancelRect = new RectF();
    private final RectF resetRect = new RectF();
    private final RectF sizeSliderRect = new RectF();
    private final RectF opacitySliderRect = new RectF();

    private float mainStickX;
    private float mainStickY;
    private float cStickX;
    private float cStickY;

    public TouchControlOverlayView(Context context) {
        super(context);
        init();
    }

    public TouchControlOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        layout = TouchOverlayLayoutStore.load(getContext());
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setControlsEnabled(boolean controlsEnabled) {
        if (this.controlsEnabled == controlsEnabled) return;
        this.controlsEnabled = controlsEnabled;
        if (!controlsEnabled) releaseAllControls();
        invalidate();
    }

    public void enterEditMode() {
        if (editMode) return;
        releaseAllControls();
        savedEditLayout = layout.copy();
        editMode = true;
        if (listener != null) listener.onOverlayEditModeChanged(true);
        invalidate();
    }

    public void exitEditModeSaving() {
        TouchOverlayLayoutStore.save(getContext(), layout);
        savedEditLayout = layout.copy();
        editMode = false;
        editPointerId = -1;
        if (listener != null) listener.onOverlayEditModeChanged(false);
        invalidate();
    }

    public void exitEditModeCanceling() {
        if (savedEditLayout != null) {
            TouchOverlayLayoutStore.copyInto(savedEditLayout, layout);
        }
        editMode = false;
        editPointerId = -1;
        if (listener != null) listener.onOverlayEditModeChanged(false);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!controlsEnabled && !editMode) return;

        for (Control control : layout.controls()) {
            drawControl(canvas, control);
        }
        drawEditButton(canvas);
        if (editMode) {
            drawEditor(canvas);
        }
    }

    private void drawControl(Canvas canvas, Control control) {
        float cx = control.x * getWidth();
        float cy = control.y * getHeight();
        float radius = radius(control);
        int alpha = Math.round(layout.opacity * 255f);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(controlColor(control));
        fill.setAlpha(alpha);
        stroke.setColor(COLOR_RING);
        stroke.setAlpha(Math.min(255, alpha + 45));
        stroke.setStrokeWidth(dp(2));

        canvas.drawCircle(cx, cy, radius, fill);
        canvas.drawCircle(cx, cy, radius, stroke);

        if (control.kind == TouchOverlayLayoutStore.KIND_STICK) {
            float sx = TouchOverlayLayoutStore.MAIN_STICK.equals(control.id) ? mainStickX : cStickX;
            float sy = TouchOverlayLayoutStore.MAIN_STICK.equals(control.id) ? mainStickY : cStickY;
            float knobRadius = radius * 0.42f;
            fill.setColor(0xCCF0F3F1);
            fill.setAlpha(Math.min(230, alpha + 60));
            canvas.drawCircle(cx + sx * radius * 0.58f, cy - sy * radius * 0.58f,
                    knobRadius, fill);
        } else if (control.kind == TouchOverlayLayoutStore.KIND_DPAD) {
            stroke.setColor(0xEEF0F3F1);
            stroke.setStrokeWidth(dp(5));
            canvas.drawLine(cx - radius * 0.54f, cy, cx + radius * 0.54f, cy, stroke);
            canvas.drawLine(cx, cy - radius * 0.54f, cx, cy + radius * 0.54f, stroke);
        }

        text.setColor(Color.WHITE);
        text.setAlpha(Math.min(255, alpha + 70));
        text.setFakeBoldText(true);
        text.setTextSize(Math.max(dp(12), radius * 0.48f));
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(control.label, cx, cy - (fm.ascent + fm.descent) * 0.5f, text);

        if (editMode && control.id.equals(selectedControlId)) {
            stroke.setColor(COLOR_SELECTED);
            stroke.setAlpha(255);
            stroke.setStrokeWidth(dp(3));
            canvas.drawCircle(cx, cy, radius + dp(4), stroke);
        }
    }

    private void drawEditButton(Canvas canvas) {
        float pad = dp(10);
        float w = dp(56);
        float h = dp(34);
        editButtonRect.set(getWidth() - w - pad, pad, getWidth() - pad, pad + h);
        fill.setColor(editMode ? COLOR_SELECTED : COLOR_PANEL);
        fill.setAlpha(editMode ? 230 : 190);
        canvas.drawRoundRect(editButtonRect, dp(8), dp(8), fill);
        text.setColor(editMode ? 0xFF101815 : Color.WHITE);
        text.setAlpha(255);
        text.setFakeBoldText(true);
        text.setTextSize(dp(12));
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText("EDIT", editButtonRect.centerX(),
                editButtonRect.centerY() - (fm.ascent + fm.descent) * 0.5f, text);
    }

    private void drawEditor(Canvas canvas) {
        updateEditorRects();
        RectF panel = new RectF(0, getHeight() - dp(106), getWidth(), getHeight());
        fill.setColor(COLOR_PANEL);
        fill.setAlpha(240);
        canvas.drawRect(panel, fill);

        drawEditorButton(canvas, saveRect, "SAVE");
        drawEditorButton(canvas, cancelRect, "CANCEL");
        drawEditorButton(canvas, resetRect, "RESET");
        drawSlider(canvas, sizeSliderRect, "SIZE", selectedSizeRatio());
        drawSlider(canvas, opacitySliderRect, "OPACITY", (layout.opacity - 0.2f) / 0.8f);
    }

    private void drawEditorButton(Canvas canvas, RectF rect, String label) {
        fill.setColor(0xFF1D2A24);
        fill.setAlpha(255);
        canvas.drawRoundRect(rect, dp(8), dp(8), fill);
        stroke.setColor(0xFF415149);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(1));
        canvas.drawRoundRect(rect, dp(8), dp(8), stroke);
        text.setColor(Color.WHITE);
        text.setAlpha(255);
        text.setFakeBoldText(true);
        text.setTextSize(dp(12));
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) * 0.5f, text);
    }

    private void drawSlider(Canvas canvas, RectF rect, String label, float value) {
        text.setColor(Color.WHITE);
        text.setAlpha(220);
        text.setFakeBoldText(false);
        text.setTextSize(dp(11));
        canvas.drawText(label, rect.left + dp(34), rect.top - dp(5), text);

        stroke.setColor(0xFF5D6E65);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(4));
        canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), stroke);

        float knobX = rect.left + clamp(value, 0f, 1f) * rect.width();
        fill.setColor(COLOR_SELECTED);
        fill.setAlpha(255);
        canvas.drawCircle(knobX, rect.centerY(), dp(9), fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!controlsEnabled && !editMode) return false;
        if (editMode) return handleEditTouch(event);
        return handlePlayTouch(event);
    }

    private boolean handlePlayTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(actionIndex);
            float y = event.getY(actionIndex);
            if (editButtonRect.contains(x, y) || hitEditButton(x, y)) {
                enterEditMode();
                return true;
            }
            Control control = hitControl(x, y);
            if (control == null) return false;
            int pointerId = event.getPointerId(actionIndex);
            ActiveTouch touch = new ActiveTouch(control.id);
            activeTouches.put(pointerId, touch);
            updateActiveTouch(touch, control, x, y);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                ActiveTouch touch = activeTouches.get(event.getPointerId(i));
                if (touch == null) continue;
                Control control = layout.get(touch.controlId);
                if (control != null) updateActiveTouch(touch, control, event.getX(i), event.getY(i));
            }
            invalidate();
            return activeTouches.size() > 0;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int pointerId = event.getPointerId(actionIndex);
            releaseTouch(activeTouches.get(pointerId));
            activeTouches.remove(pointerId);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAllControls();
            invalidate();
            return true;
        }
        return activeTouches.size() > 0;
    }

    private boolean handleEditTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (editPointerId != -1) return true;
            float x = event.getX(actionIndex);
            float y = event.getY(actionIndex);
            updateEditorRects();
            if (saveRect.contains(x, y)) {
                exitEditModeSaving();
                return true;
            }
            if (cancelRect.contains(x, y)) {
                exitEditModeCanceling();
                return true;
            }
            if (resetRect.contains(x, y)) {
                Layout defaults = TouchOverlayLayoutStore.defaults();
                TouchOverlayLayoutStore.copyInto(defaults, layout);
                selectedControlId = TouchOverlayLayoutStore.MAIN_STICK;
                invalidate();
                return true;
            }
            editPointerId = event.getPointerId(actionIndex);
            if (sizeSliderRect.contains(x, y)) {
                editGesture = 2;
                updateSelectedSizeFromX(x);
            } else if (opacitySliderRect.contains(x, y)) {
                editGesture = 3;
                updateOpacityFromX(x);
            } else {
                Control control = hitControl(x, y);
                if (control != null) {
                    selectedControlId = control.id;
                    editGesture = 1;
                    moveSelectedControl(x, y);
                } else {
                    editPointerId = -1;
                    editGesture = 0;
                }
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && editPointerId != -1) {
            int index = event.findPointerIndex(editPointerId);
            if (index < 0) return true;
            float x = event.getX(index);
            float y = event.getY(index);
            if (editGesture == 1) moveSelectedControl(x, y);
            else if (editGesture == 2) updateSelectedSizeFromX(x);
            else if (editGesture == 3) updateOpacityFromX(x);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            int pointerId = action == MotionEvent.ACTION_CANCEL ? editPointerId : event.getPointerId(actionIndex);
            if (pointerId == editPointerId) {
                editPointerId = -1;
                editGesture = 0;
            }
            return true;
        }
        return true;
    }

    private void updateActiveTouch(ActiveTouch touch, Control control, float x, float y) {
        if (control.kind == TouchOverlayLayoutStore.KIND_BUTTON) {
            if (!touch.pressed && listener != null) {
                listener.onOverlayButton(control.gcBit, true);
            }
            touch.pressed = true;
            return;
        }

        float cx = control.x * getWidth();
        float cy = control.y * getHeight();
        float radius = radius(control);
        float dx = (x - cx) / radius;
        float dy = (y - cy) / radius;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > 1f) {
            dx /= length;
            dy /= length;
        }

        if (control.kind == TouchOverlayLayoutStore.KIND_STICK) {
            float gameY = -dy;
            if (TouchOverlayLayoutStore.MAIN_STICK.equals(control.id)) {
                mainStickX = dx;
                mainStickY = gameY;
            } else {
                cStickX = dx;
                cStickY = gameY;
            }
            if (listener != null) listener.onOverlayStick(control.id, dx, gameY);
            return;
        }

        if (control.kind == TouchOverlayLayoutStore.KIND_DPAD) {
            boolean left = dx < -0.30f;
            boolean right = dx > 0.30f;
            boolean up = dy < -0.30f;
            boolean down = dy > 0.30f;
            touch.up = up;
            touch.down = down;
            touch.left = left;
            touch.right = right;
            if (listener != null) listener.onOverlayDpad(up, down, left, right);
        }
    }

    private void releaseTouch(ActiveTouch touch) {
        if (touch == null) return;
        Control control = layout.get(touch.controlId);
        if (control == null || listener == null) return;
        if (control.kind == TouchOverlayLayoutStore.KIND_BUTTON && touch.pressed) {
            listener.onOverlayButton(control.gcBit, false);
        } else if (control.kind == TouchOverlayLayoutStore.KIND_STICK) {
            if (TouchOverlayLayoutStore.MAIN_STICK.equals(control.id)) {
                mainStickX = 0f;
                mainStickY = 0f;
            } else {
                cStickX = 0f;
                cStickY = 0f;
            }
            listener.onOverlayStick(control.id, 0f, 0f);
        } else if (control.kind == TouchOverlayLayoutStore.KIND_DPAD) {
            listener.onOverlayDpad(false, false, false, false);
        }
    }

    private void releaseAllControls() {
        for (int i = 0; i < activeTouches.size(); i++) {
            releaseTouch(activeTouches.valueAt(i));
        }
        activeTouches.clear();
        mainStickX = 0f;
        mainStickY = 0f;
        cStickX = 0f;
        cStickY = 0f;
    }

    private Control hitControl(float x, float y) {
        Control best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Control control : layout.controls()) {
            float dx = x - control.x * getWidth();
            float dy = y - control.y * getHeight();
            float distance = dx * dx + dy * dy;
            float radius = radius(control) + (editMode ? dp(10) : 0);
            if (distance <= radius * radius && distance < bestDistance) {
                best = control;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean hitEditButton(float x, float y) {
        drawEditButtonRectOnly();
        return editButtonRect.contains(x, y);
    }

    private void drawEditButtonRectOnly() {
        float pad = dp(10);
        float w = dp(56);
        float h = dp(34);
        editButtonRect.set(getWidth() - w - pad, pad, getWidth() - pad, pad + h);
    }

    private int controlColor(Control control) {
        if (control.kind == TouchOverlayLayoutStore.KIND_STICK) return 0xFF26362F;
        if (control.kind == TouchOverlayLayoutStore.KIND_DPAD) return 0xFF26362F;
        switch (control.gcBit) {
            case ButtonMap.GC_BTN_A:
                return 0xFF02ABA8;
            case ButtonMap.GC_BTN_B:
                return 0xFFD0473D;
            case ButtonMap.GC_BTN_X:
            case ButtonMap.GC_BTN_Y:
                return 0xFFD2D2D2;
            case ButtonMap.GC_TRIG_Z:
                return 0xFF8351E5;
            case ButtonMap.GC_TRIG_L:
            case ButtonMap.GC_TRIG_R:
                return 0xFF8FA0A8;
            case ButtonMap.GC_BTN_START:
                return 0xFFD0473D;
            default:
                return COLOR_FILL;
        }
    }

    private void updateEditorRects() {
        float margin = dp(12);
        float buttonTop = getHeight() - dp(94);
        float buttonH = dp(34);
        float buttonW = dp(74);
        saveRect.set(margin, buttonTop, margin + buttonW, buttonTop + buttonH);
        cancelRect.set(saveRect.right + dp(8), buttonTop, saveRect.right + dp(8) + buttonW, buttonTop + buttonH);
        resetRect.set(cancelRect.right + dp(8), buttonTop, cancelRect.right + dp(8) + buttonW, buttonTop + buttonH);

        float sliderLeft = resetRect.right + dp(24);
        float sliderRight = getWidth() - margin;
        float sliderW = Math.max(dp(120), (sliderRight - sliderLeft - dp(24)) * 0.5f);
        sizeSliderRect.set(sliderLeft, getHeight() - dp(73), sliderLeft + sliderW, getHeight() - dp(49));
        opacitySliderRect.set(sizeSliderRect.right + dp(24), getHeight() - dp(73),
                Math.min(sliderRight, sizeSliderRect.right + dp(24) + sliderW), getHeight() - dp(49));
    }

    private float selectedSizeRatio() {
        Control control = layout.get(selectedControlId);
        if (control == null) return 0f;
        return (control.size - 0.045f) / (0.28f - 0.045f);
    }

    private void updateSelectedSizeFromX(float x) {
        Control control = layout.get(selectedControlId);
        if (control == null) return;
        float t = clamp((x - sizeSliderRect.left) / sizeSliderRect.width(), 0f, 1f);
        control.size = 0.045f + t * (0.28f - 0.045f);
        clampControlToViewport(control);
    }

    private void updateOpacityFromX(float x) {
        float t = clamp((x - opacitySliderRect.left) / opacitySliderRect.width(), 0f, 1f);
        layout.opacity = 0.2f + t * 0.8f;
    }

    private void moveSelectedControl(float x, float y) {
        Control control = layout.get(selectedControlId);
        if (control == null || getWidth() <= 0 || getHeight() <= 0) return;
        control.x = x / getWidth();
        control.y = y / getHeight();
        clampControlToViewport(control);
    }

    private void clampControlToViewport(Control control) {
        float r = radius(control);
        float rx = getWidth() <= 0 ? 0f : r / getWidth();
        float ry = getHeight() <= 0 ? 0f : r / getHeight();
        control.x = clamp(control.x, rx, 1f - rx);
        control.y = clamp(control.y, ry, 1f - ry);
    }

    private float radius(Control control) {
        return Math.max(dp(18), control.size * Math.min(getWidth(), getHeight()) * 0.5f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static final class ActiveTouch {
        final String controlId;
        boolean pressed;
        boolean up;
        boolean down;
        boolean left;
        boolean right;

        ActiveTouch(String controlId) {
            this.controlId = controlId;
        }
    }
}
