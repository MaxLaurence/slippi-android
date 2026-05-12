package org.dolphinemu.dolphinemu.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.controller.StickCalibration;
import org.dolphinemu.dolphinemu.utils.RawStickInputProvider;
import org.dolphinemu.dolphinemu.utils.RawStickInputProviders;
import org.dolphinemu.dolphinemu.utils.RawStickState;
import org.dolphinemu.dolphinemu.views.StickPreviewView;

import java.util.Locale;

/**
 * Single-screen stick calibration wizard.
 *
 * Compared to the previous REST/ROLL/CONFIRM flow, the user now sees
 * everything on one screen:
 *   - a stick visualization showing both the raw input and what the
 *     game will see after the current sliders are applied
 *   - a Main / C-stick toggle
 *   - the two tunable sliders (deadzone, curve)
 *   - a Save / Cancel / Reset row
 *
 * Capture (resting center + per-axis range) happens passively while
 * the user is on this screen — they just play with the stick and tap
 * Save when it feels right. Calibration is **not** required for the
 * sticks to work: the launcher's per-stick defaults are applied
 * whenever no profile is saved, so a user who never opens this
 * wizard still gets a sane experience.
 */
public class CalibrationActivity extends AppCompatActivity {

    private static final String TAG = "SlippiCal";

    public static final String EXTRA_DEVICE_KEY = "device_key";
    public static final String EXTRA_DEVICE_LABEL = "device_label";

    private enum Stick { MAIN, C }

    private String deviceKey;
    private String deviceLabel;
    private boolean isAdapter;
    private int adapterPort;
    private Stick stick = Stick.MAIN;

    // ─── view bindings ────────────────────────────────────────────
    private StickPreviewView previewView;
    private MaterialButtonToggleGroup stickToggle;
    private TextView deadzoneLabel;
    private TextView sensitivityLabel;
    private TextView previewText;
    private TextView titleText;
    private TextView instructionsText;
    private Slider deadzoneSlider;
    private Slider sensitivitySlider;
    // Hidden in this build — output cap was never useful in practice;
    // we keep the field for forward-compatibility.
    private Slider outputCapSlider;

    // ─── current slider state ─────────────────────────────────────
    // Mirrors the sliders the user is interacting with right now. The
    // canonical per-stick state lives on `mainEdit` / `cEdit`; we
    // copy in / out of these fields when the user toggles sticks.
    private float currentDeadzone;
    private float currentSensitivity;
    private float currentOutputCap = StickCalibration.DEFAULT_OUTPUT_CAP;

    // ─── per-stick edit state ─────────────────────────────────────
    // Holds the capture buffers + the slider values for each stick.
    // Tracked separately so toggling between Main / C doesn't wipe
    // the other stick's progress, and so a single Save commits both
    // sticks if both have been touched.
    private final StickEdit mainEdit = new StickEdit();
    private final StickEdit cEdit    = new StickEdit();

    // ─── input source ────────────────────────────────────────────
    private RawStickInputProvider rawStickInput;
    private float lastRawX, lastRawY;
    private final StickCalibration.Out previewOut = new StickCalibration.Out();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable adapterPoll = new Runnable() {
        @Override
        public void run() {
            int[] raw = NativeLibrary.GetRawAdapterStick(adapterPort,
                    stick == Stick.MAIN ? 0 : 1);
            if (raw != null && raw.length == 2) {
                float nx = (raw[0] - 128f) / 127f;
                float ny = (raw[1] - 128f) / 127f;
                onRawSample(nx, ny);
            }
            ui.postDelayed(this, 16);
        }
    };
    private final Runnable rawInputPoll = new Runnable() {
        @Override
        public void run() {
            if (rawStickInput == null) return;
            RawStickState s = rawStickInput.snapshot();
            if (s != null && s.hasAnyAxis()) {
                int xAxis = stick == Stick.MAIN
                        ? MotionEvent.AXIS_X : MotionEvent.AXIS_Z;
                int yAxis = stick == Stick.MAIN
                        ? MotionEvent.AXIS_Y : MotionEvent.AXIS_RZ;
                Float x = s.valueForAxis(xAxis);
                Float y = s.valueForAxis(yAxis);
                if (x != null && y != null) onRawSample(x, y);
            }
            ui.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        deviceKey = getIntent().getStringExtra(EXTRA_DEVICE_KEY);
        deviceLabel = getIntent().getStringExtra(EXTRA_DEVICE_LABEL);
        if (deviceKey == null) deviceKey = ControllerProfile.DEVICE_BUILTIN;
        if (deviceLabel == null) deviceLabel = "Controller";
        isAdapter = deviceKey.startsWith("adapter:");
        if (isAdapter) {
            try {
                adapterPort = Integer.parseInt(deviceKey.substring("adapter:".length()));
            } catch (NumberFormatException e) {
                adapterPort = 0;
            }
        }

        previewView = findViewById(R.id.cal_preview_view);
        stickToggle = findViewById(R.id.cal_stick_toggle);
        deadzoneLabel = findViewById(R.id.cal_deadzone_label);
        sensitivityLabel = findViewById(R.id.cal_sensitivity_label);
        previewText = findViewById(R.id.cal_preview);
        titleText = findViewById(R.id.cal_title);
        instructionsText = findViewById(R.id.cal_instructions);
        deadzoneSlider = findViewById(R.id.cal_deadzone_slider);
        sensitivitySlider = findViewById(R.id.cal_sensitivity_slider);
        outputCapSlider = findViewById(R.id.cal_output_cap_slider);

        findViewById(R.id.cal_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.cal_next).setOnClickListener(v -> commitAndFinish());
        findViewById(R.id.cal_reset_defaults).setOnClickListener(v ->
                resetSlidersToDefaults());

        stickToggle.check(R.id.cal_stick_main);
        stickToggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            // Persist the slider values for the OUTGOING stick before
            // we swap to the new one, so its state isn't lost.
            snapshotCurrentSlidersIntoActiveEdit();
            stick = id == R.id.cal_stick_c ? Stick.C : Stick.MAIN;
            loadSlidersForCurrentStick();
            renderHeader();
            renderPreview();
        });

        deadzoneSlider.addOnChangeListener((s, value, fromUser) -> {
            currentDeadzone = value;
            if (fromUser) {
                activeEdit().touched = true;
                activeEdit().deadzone = value;
            }
            updateTuneLabels();
            renderPreview();
        });
        sensitivitySlider.addOnChangeListener((s, value, fromUser) -> {
            currentSensitivity = value;
            if (fromUser) {
                activeEdit().touched = true;
                activeEdit().sensitivity = value;
            }
            updateTuneLabels();
            renderPreview();
        });

        if (!isAdapter) {
            rawStickInput = RawStickInputProviders.create(this);
            if (rawStickInput != null) rawStickInput.start();
        }

        loadSlidersForCurrentStick();
        renderHeader();
        renderPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAdapter) {
            ui.post(adapterPoll);
        } else if (rawStickInput != null) {
            ui.post(rawInputPoll);
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(adapterPoll);
        ui.removeCallbacks(rawInputPoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(adapterPoll);
        ui.removeCallbacks(rawInputPoll);
        if (rawStickInput != null) rawStickInput.stop();
        super.onDestroy();
    }

    /**
     * Built-in pad / Bluetooth path. Android's MotionEvent only fires
     * when the stick moves; the rawInputPoll above handles continuous
     * sampling when an evdev source is open instead.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (!isAdapter
                && rawStickInput == null
                && ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) != 0
                ||  (ev.getSource() & InputDevice.SOURCE_GAMEPAD) != 0)) {
            float x, y;
            if (stick == Stick.MAIN) {
                x = ev.getAxisValue(MotionEvent.AXIS_X);
                y = ev.getAxisValue(MotionEvent.AXIS_Y);
            } else {
                x = ev.getAxisValue(MotionEvent.AXIS_Z);
                y = ev.getAxisValue(MotionEvent.AXIS_RZ);
            }
            onRawSample(x, y);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private void onRawSample(float x, float y) {
        lastRawX = x;
        lastRawY = y;
        StickEdit e = activeEdit();
        e.capture.update(x, y);
        // Stick movement past the deadzone counts as "touched" — we
        // don't want a passing brush of the stick to mark it as
        // edited, only deliberate input.
        if (Math.sqrt(x * x + y * y) > 0.10) e.touched = true;
        renderPreview();
    }

    /**
     * Save whichever sticks have been touched in this session. If both
     * the user has interacted with both Main and C, both are written
     * in a single Save tap. Untouched sticks are left as-is — we don't
     * overwrite a previously-saved cal just because the user opened
     * the wizard.
     */
    private void commitAndFinish() {
        // Always flush the active stick's current slider state into
        // its per-stick edit before we serialize.
        snapshotCurrentSlidersIntoActiveEdit();

        ControllerProfile profile = new ControllerProfile(this);
        boolean savedAny = false;
        if (mainEdit.touched) {
            persistEdit(profile, Stick.MAIN, mainEdit);
            savedAny = true;
        }
        if (cEdit.touched) {
            persistEdit(profile, Stick.C, cEdit);
            savedAny = true;
        }
        // If the user opened the wizard, dragged sliders for ONLY the
        // currently-visible stick (no actual stick movement), still
        // honor that as a save for that stick.
        if (!savedAny) {
            persistEdit(profile, stick, activeEdit());
            savedAny = true;
        }

        Toast.makeText(this,
                (mainEdit.touched && cEdit.touched)
                        ? "Both stick calibrations saved."
                        : (stick == Stick.MAIN
                                ? "Main-stick calibration saved."
                                : "C-stick calibration saved."),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void persistEdit(ControllerProfile profile, Stick which, StickEdit edit) {
        StickCalibration cal = buildCalibration(edit);
        ControllerProfile.Stick s = which == Stick.MAIN
                ? ControllerProfile.Stick.MAIN : ControllerProfile.Stick.C;
        Log.i(TAG, "save device=" + deviceKey + " stick=" + s
                + " dz=" + cal.deadzone + " sens=" + cal.sensitivity
                + " scaleXPos=" + cal.scaleXPos);
        profile.putStick(deviceKey, s, cal);

        if (isAdapter) {
            float cxByte = cal.centerX * 127f + 128f;
            float cyByte = cal.centerY * 127f + 128f;
            int stickIdx = which == Stick.MAIN ? 0 : 1;
            NativeLibrary.SetGCAdapterStickCalibration(adapterPort, stickIdx,
                    cxByte, cyByte,
                    cal.scaleXPos, cal.scaleXNeg, cal.scaleYPos, cal.scaleYNeg,
                    cal.deadzone, cal.sensitivity);
        }
    }

    private StickCalibration buildCalibration(StickEdit edit) {
        Capture cap = edit.capture;
        float restCenterX = cap.restSamples > 0 ? cap.restSumX / cap.restSamples : 0f;
        float restCenterY = cap.restSamples > 0 ? cap.restSumY / cap.restSamples : 0f;
        float minX = Float.isFinite(cap.minX) ? cap.minX : -1f;
        float maxX = Float.isFinite(cap.maxX) ? cap.maxX : +1f;
        float minY = Float.isFinite(cap.minY) ? cap.minY : -1f;
        float maxY = Float.isFinite(cap.maxY) ? cap.maxY : +1f;
        return StickCalibration.fromSamples(
                minX, maxX, minY, maxY,
                restCenterX, restCenterY,
                edit.deadzone, edit.sensitivity, edit.outputCap,
                isAdapter || hasRawStickSource());
    }

    private boolean hasRawStickSource() {
        return rawStickInput != null && rawStickInput.isAvailable();
    }

    private StickEdit activeEdit() {
        return stick == Stick.MAIN ? mainEdit : cEdit;
    }

    /** Copy the live slider fields into the active stick's edit state. */
    private void snapshotCurrentSlidersIntoActiveEdit() {
        StickEdit e = activeEdit();
        e.deadzone = currentDeadzone;
        e.sensitivity = currentSensitivity;
        e.outputCap = currentOutputCap;
    }

    /**
     * Snap sliders to the per-stick factory defaults — useful when the
     * user has a stale saved cal from an older app version.
     */
    private void resetSlidersToDefaults() {
        currentDeadzone = stick == Stick.MAIN
                ? StickCalibration.DEFAULT_DEADZONE_MAIN
                : StickCalibration.DEFAULT_DEADZONE_C;
        currentSensitivity = stick == Stick.MAIN
                ? StickCalibration.DEFAULT_SENSITIVITY_MAIN
                : StickCalibration.DEFAULT_SENSITIVITY_C;
        currentOutputCap = StickCalibration.DEFAULT_OUTPUT_CAP;
        deadzoneSlider.setValue(currentDeadzone);
        sensitivitySlider.setValue(currentSensitivity);
        outputCapSlider.setValue(currentOutputCap);
        // Mark this stick as touched so Save persists it, even though
        // the user didn't manually drag the sliders.
        StickEdit e = activeEdit();
        e.touched = true;
        e.deadzone = currentDeadzone;
        e.sensitivity = currentSensitivity;
        e.outputCap = currentOutputCap;
        e.capture.reset();
        updateTuneLabels();
        renderPreview();
    }

    /**
     * Initialize the sliders to the saved-or-default values for the
     * currently-selected stick. Called on activity create and on
     * Main/C toggle.
     */
    private void loadSlidersForCurrentStick() {
        StickEdit e = activeEdit();
        // If this stick's edit state has already been populated this
        // session (user toggled away and back), restore those values.
        // Otherwise seed from the saved cal (or per-stick defaults).
        if (!e.seeded) {
            ControllerProfile profile = new ControllerProfile(this);
            ControllerProfile.Stick which = stick == Stick.MAIN
                    ? ControllerProfile.Stick.MAIN : ControllerProfile.Stick.C;
            StickCalibration saved = profile.getStick(deviceKey, which);
            float defaultDz = stick == Stick.MAIN
                    ? StickCalibration.DEFAULT_DEADZONE_MAIN
                    : StickCalibration.DEFAULT_DEADZONE_C;
            float defaultSens = stick == Stick.MAIN
                    ? StickCalibration.DEFAULT_SENSITIVITY_MAIN
                    : StickCalibration.DEFAULT_SENSITIVITY_C;
            e.deadzone = clampSlider(saved.deadzone,
                    StickCalibration.MIN_DEADZONE, StickCalibration.MAX_DEADZONE,
                    0.01f, defaultDz);
            e.sensitivity = clampSlider(saved.sensitivity,
                    StickCalibration.MIN_SENSITIVITY, StickCalibration.MAX_SENSITIVITY,
                    0.1f, defaultSens);
            e.outputCap = StickCalibration.DEFAULT_OUTPUT_CAP;
            e.seeded = true;
        }
        currentDeadzone = e.deadzone;
        currentSensitivity = e.sensitivity;
        currentOutputCap = e.outputCap;
        deadzoneSlider.setValue(currentDeadzone);
        sensitivitySlider.setValue(currentSensitivity);
        outputCapSlider.setValue(currentOutputCap);
        updateTuneLabels();
    }

    private void renderHeader() {
        titleText.setText(stick == Stick.MAIN
                ? "Calibrate Main Stick" : "Calibrate C-Stick");
        StringBuilder sub = new StringBuilder(deviceLabel);
        // Tip text gives the user one line of context — what to do.
        sub.append("  ·  ");
        sub.append("Roll the stick to map its range; tweak sliders to taste.");
        instructionsText.setText(sub.toString());
    }

    private void updateTuneLabels() {
        deadzoneLabel.setText(String.format(Locale.ROOT,
                "Deadzone · %.0f%%", currentDeadzone * 100f));
        String sensHint =
                currentSensitivity < 1.1f  ? "linear"
              : currentSensitivity < 1.6f  ? "gentle low end"
              : currentSensitivity < 2.6f  ? "moderate"
              : currentSensitivity < 5.1f  ? "strong"
              :                              "extreme";
        sensitivityLabel.setText(String.format(Locale.ROOT,
                "Stick curve · %.1f (%s)", currentSensitivity, sensHint));
    }

    /**
     * Update the preview view (live dots) and the small readout text
     * using the current calibration parameters. Runs on every motion
     * sample or slider tweak.
     */
    private void renderPreview() {
        StickEdit e = activeEdit();
        // Build the cal using the LIVE slider state, not the edit's
        // last-snapshotted state, so the preview updates instantly as
        // the user drags.
        e.deadzone = currentDeadzone;
        e.sensitivity = currentSensitivity;
        e.outputCap = currentOutputCap;
        StickCalibration cal = buildCalibration(e);
        cal.apply(lastRawX, lastRawY, previewOut);
        previewView.setRaw(lastRawX, lastRawY);
        previewView.setCalibrated(previewOut.x, previewOut.y);
        Capture cap = e.capture;
        if (cap.hasAny()) {
            previewView.setCaptured(cap.minX, cap.maxX, cap.minY, cap.maxY);
        } else {
            previewView.resetCaptured();
        }
        float rawMag = (float) Math.sqrt(lastRawX * lastRawX + lastRawY * lastRawY);
        float outMag = (float) Math.sqrt(previewOut.x * previewOut.x + previewOut.y * previewOut.y);
        previewText.setText(String.format(Locale.ROOT,
                "raw  X=%+.2f Y=%+.2f |%.2f|\ngame X=%+.2f Y=%+.2f |%.2f|",
                lastRawX, lastRawY, rawMag,
                previewOut.x, previewOut.y, outMag));
    }

    private static float clampSlider(float v, float lo, float hi, float step, float fallback) {
        if (Float.isNaN(v)) return fallback;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return Math.round((v - lo) / step) * step + lo;
    }

    /** Per-stick continuously-updated capture buffers. */
    private static final class Capture {
        float restSumX, restSumY;
        int restSamples;
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        void update(float x, float y) {
            // Anything with magnitude < 0.05 counts as "at rest".
            float mag = (float) Math.sqrt(x * x + y * y);
            if (mag < 0.05f) {
                restSumX += x;
                restSumY += y;
                restSamples += 1;
            }
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        boolean hasAny() {
            return !(Float.isInfinite(minX) || Float.isInfinite(maxX)
                    || Float.isInfinite(minY) || Float.isInfinite(maxY));
        }

        void reset() {
            restSumX = restSumY = 0;
            restSamples = 0;
            minX = Float.POSITIVE_INFINITY;
            maxX = Float.NEGATIVE_INFINITY;
            minY = Float.POSITIVE_INFINITY;
            maxY = Float.NEGATIVE_INFINITY;
        }
    }

    /**
     * Per-stick editor state. Holds the live slider values + the
     * passive capture buffer + a flag for whether the user has
     * touched this stick this session. A single Save iterates over
     * both edits and persists any that are touched.
     */
    private static final class StickEdit {
        final Capture capture = new Capture();
        float deadzone;
        float sensitivity;
        float outputCap = StickCalibration.DEFAULT_OUTPUT_CAP;
        boolean touched;
        /** True once we've populated `deadzone/sensitivity` from the
         *  saved profile or per-stick defaults; lets us cache that
         *  decision across stick toggles within one session. */
        boolean seeded;
    }
}
