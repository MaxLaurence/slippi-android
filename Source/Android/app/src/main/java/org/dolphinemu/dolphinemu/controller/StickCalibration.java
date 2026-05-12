package org.dolphinemu.dolphinemu.controller;

/**
 * Per-stick calibration: capture a center + per-direction outer bounds,
 * then map raw input to ideal Melee-style output (-1..+1 per axis with
 * a circular magnitude clamp and a small inner deadzone).
 *
 * Why per-direction scaling: real analog sticks are rarely symmetric.
 * A typical handheld stick on this build pulls maybe 0.92 to the right
 * but a full 1.00 to the left, and the Y axis can drift ±5%. Treating
 * each half-axis independently means small physical asymmetries don't
 * show up as Marth-can-only-FFair issues in-game.
 *
 * Why a magnitude (circular) clamp instead of per-axis: the GC stick
 * is gated by an octagonal restrictor — the maximum *radial* distance
 * matters far more than per-axis maxima. A pad whose square corners
 * read (1, 1) without a circular clamp would deliver radius ~1.41,
 * which the game treats as full-tilt smashes constantly.
 */
public final class StickCalibration {

    /**
     * Default radial deadzones, per stick. Different sticks see
     * different use, so different defaults work better:
     *
     * - **Main (left) stick** lives at 2% — it's used for fine
     *   movement, walking, tilting; a generous deadzone here makes
     *   slow walks feel mushy.
     * - **C (right) stick** lives at 10% — Melee only uses the C-stick
     *   for smash attacks, all of which sit above ~0.7 magnitude;
     *   a bigger deadzone here kills accidental nudges that would
     *   otherwise trigger an unintended smash.
     */
    public static final float DEFAULT_DEADZONE_MAIN = 0.02f;
    public static final float DEFAULT_DEADZONE_C    = 0.10f;
    /** Back-compat: callers without stick context get the main-stick default. */
    public static final float DEFAULT_DEADZONE = DEFAULT_DEADZONE_MAIN;

    /** Default outer scale applied when no per-direction calibration is set. */
    public static final float DEFAULT_SCALE = 1.0f;

    /**
     * Default response curve exponent (`out = sign * pow(|in|, sens)`),
     * per stick:
     *
     * - **Main (left) stick** at 1.5 — gentle low-end softening, full
     *   range still reachable for jumps / smashes.
     * - **C (right) stick** at 2.0 — steeper curve. Melee's C-stick
     *   smashes only fire at magnitude > ~0.7, so we want everything
     *   below that gone; a steeper exponent makes it harder to
     *   accidentally smash with a light flick.
     */
    public static final float DEFAULT_SENSITIVITY_MAIN = 1.5f;
    public static final float DEFAULT_SENSITIVITY_C    = 2.0f;
    /** Back-compat: callers without stick context get the main-stick default. */
    public static final float DEFAULT_SENSITIVITY = DEFAULT_SENSITIVITY_MAIN;

    /**
     * Maximum output magnitude, applied AFTER deadzone + curve. The
     * point of this slider is so people on a small handheld stick
     * (which is easy to push to full physical range) can still avoid
     * accidental jumps / smashes — at outputCap=0.65 even a full
     * physical push produces magnitude 0.65, which is just under
     * Melee's 0.66 jump threshold, so jumping requires Y/X buttons.
     */
    public static final float DEFAULT_OUTPUT_CAP = 1.0f;

    public static final float MIN_DEADZONE = 0.0f;
    public static final float MAX_DEADZONE = 0.50f;     // half the radius dead
    public static final float MIN_SENSITIVITY = 1.0f;   // pure linear
    public static final float MAX_SENSITIVITY = 10.0f;  // jump only at ~98% physical push
    public static final float MIN_OUTPUT_CAP = 0.30f;   // very restricted (tilts-only)
    public static final float MAX_OUTPUT_CAP = 1.00f;   // unlimited

    /**
     * Identity-ish calibration (no offset, no per-direction scale) but
     * still applies the default deadzone + response curve, since those
     * are quality-of-life defaults rather than per-device captures.
     *
     * Aliases the main-stick defaults; callers that know which stick
     * they're targeting should prefer {@link #defaultsForMain()} or
     * {@link #defaultsForC()} so the C-stick gets its steeper curve.
     */
    public static final StickCalibration IDENTITY = new StickCalibration(
            0f, 0f,
            DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE,
            DEFAULT_DEADZONE_MAIN,
            DEFAULT_SENSITIVITY_MAIN,
            DEFAULT_OUTPUT_CAP);

    public static StickCalibration defaultsForMain() {
        return new StickCalibration(0f, 0f,
                DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE,
                DEFAULT_DEADZONE_MAIN, DEFAULT_SENSITIVITY_MAIN, DEFAULT_OUTPUT_CAP);
    }

    public static StickCalibration defaultsForC() {
        return new StickCalibration(0f, 0f,
                DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE,
                DEFAULT_DEADZONE_C, DEFAULT_SENSITIVITY_C, DEFAULT_OUTPUT_CAP);
    }

    public final float centerX;          // -1..+1
    public final float centerY;          // -1..+1
    public final float scaleXPos;        // multiplier: captured rightward max -> 1.0
    public final float scaleXNeg;        // multiplier: captured leftward max  -> 1.0
    public final float scaleYPos;        // multiplier: captured downward max  -> 1.0
    public final float scaleYNeg;        // multiplier: captured upward max    -> 1.0
    public final float deadzone;         // inner radius (0..MAX_DEADZONE)
    public final float sensitivity;      // power-curve exponent (>=1 flattens low end)
    public final float outputCap;        // max output magnitude (0.3..1.0)
    public final boolean useOuterScale;  // true when samples came from raw, unsaturated axes

    public StickCalibration(float centerX, float centerY,
                            float scaleXPos, float scaleXNeg,
                            float scaleYPos, float scaleYNeg,
                            float deadzone,
                            float sensitivity,
                            float outputCap) {
        this(centerX, centerY,
                scaleXPos, scaleXNeg, scaleYPos, scaleYNeg,
                deadzone, sensitivity, outputCap, false);
    }

    public StickCalibration(float centerX, float centerY,
                            float scaleXPos, float scaleXNeg,
                            float scaleYPos, float scaleYNeg,
                            float deadzone,
                            float sensitivity,
                            float outputCap,
                            boolean useOuterScale) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.scaleXPos = scaleXPos;
        this.scaleXNeg = scaleXNeg;
        this.scaleYPos = scaleYPos;
        this.scaleYNeg = scaleYNeg;
        this.deadzone = deadzone;
        this.sensitivity = sensitivity;
        this.outputCap = outputCap;
        this.useOuterScale = useOuterScale;
    }

    /** Replace just the deadzone, preserving everything else (for live tuning). */
    public StickCalibration withDeadzone(float newDeadzone) {
        return new StickCalibration(centerX, centerY,
                scaleXPos, scaleXNeg, scaleYPos, scaleYNeg,
                newDeadzone, sensitivity, outputCap, useOuterScale);
    }

    /** Replace just the sensitivity exponent. */
    public StickCalibration withSensitivity(float newSensitivity) {
        return new StickCalibration(centerX, centerY,
                scaleXPos, scaleXNeg, scaleYPos, scaleYNeg,
                deadzone, newSensitivity, outputCap, useOuterScale);
    }

    public StickCalibration withOutputCap(float newOutputCap) {
        return new StickCalibration(centerX, centerY,
                scaleXPos, scaleXNeg, scaleYPos, scaleYNeg,
                deadzone, sensitivity, newOutputCap, useOuterScale);
    }

    public StickCalibration withOuterScaleEnabled(boolean enabled) {
        return new StickCalibration(centerX, centerY,
                scaleXPos, scaleXNeg, scaleYPos, scaleYNeg,
                deadzone, sensitivity, outputCap, enabled);
    }

    /** Result of {@link #apply(float, float)} — populated in-place to avoid GC churn. */
    public static final class Out {
        public float x;
        public float y;
    }

    /**
     * Apply this calibration to a raw normalized input pair (each in -1..+1).
     * Writes into {@code out} so callers in the input hot path don't allocate.
     */
    public void apply(float rawX, float rawY, Out out) {
        // Re-center.
        float dx = rawX - centerX;
        float dy = rawY - centerY;

        // Only apply captured outer scaling when the capture came from
        // unsaturated raw axes. MotionEvent axes may already hit ±1.0
        // early in the physical throw; scaling those samples would amplify
        // the bad range instead of recovering it.
        if (useOuterScale) {
            dx *= (dx >= 0f) ? scaleXPos : scaleXNeg;
            dy *= (dy >= 0f) ? scaleYPos : scaleYNeg;
        }

        // Inner deadzone (radial). Below this magnitude the stick is
        // considered released — kills the "accidental tap-jump from
        // resting your thumb" failure mode.
        float mag = (float) Math.sqrt(dx * dx + dy * dy);
        if (mag < deadzone) {
            out.x = 0f;
            out.y = 0f;
            return;
        }

        // Re-map the active range so the deadzone edge becomes
        // magnitude 0, then apply the response-curve exponent. The
        // exponent flattens the low end of the active range, so
        // small physical deflections produce small logical output
        // instead of immediately hitting Melee's jump/dash thresholds.
        float scaled = (mag - deadzone) / (1f - deadzone);
        if (scaled > 1f) scaled = 1f;
        if (sensitivity > 1.0f) scaled = (float) Math.pow(scaled, sensitivity);

        // Output cap — even at full physical push the output magnitude
        // can't exceed this value. Lets the user prevent full smashes
        // / jumps from stick input alone (set the cap below Melee's
        // 0.66 jump threshold to make Y/X buttons the only jump
        // trigger).
        scaled *= outputCap;

        // Reapply on the original direction.
        dx = dx / mag * scaled;
        dy = dy / mag * scaled;

        out.x = dx;
        out.y = dy;
    }

    /**
     * Build a calibration from samples captured during the wizard.
     *
     * @param sampledMinX, sampledMaxX  smallest / largest X observed while rolling
     * @param sampledMinY, sampledMaxY  smallest / largest Y observed while rolling
     * @param restCenterX, restCenterY  averaged stick position while at rest
     * @param deadzone                  inner deadzone to apply (0..0.5)
     */
    public static StickCalibration fromSamples(
            float sampledMinX, float sampledMaxX,
            float sampledMinY, float sampledMaxY,
            float restCenterX, float restCenterY,
            float deadzone,
            float sensitivity,
            float outputCap) {
        return fromSamples(
                sampledMinX, sampledMaxX, sampledMinY, sampledMaxY,
                restCenterX, restCenterY,
                deadzone, sensitivity, outputCap,
                false);
    }

    public static StickCalibration fromSamples(
            float sampledMinX, float sampledMaxX,
            float sampledMinY, float sampledMaxY,
            float restCenterX, float restCenterY,
            float deadzone,
            float sensitivity,
            float outputCap,
            boolean useOuterScale) {

        // Distance from rest to each captured extreme. Guard against pads
        // that reported one side worse than the other by clamping to a
        // floor — otherwise a noisy capture could produce huge multipliers
        // that turn the slightest touch into a full smash.
        float distXPos = Math.max(0.10f, sampledMaxX - restCenterX);
        float distXNeg = Math.max(0.10f, restCenterX - sampledMinX);
        float distYPos = Math.max(0.10f, sampledMaxY - restCenterY);
        float distYNeg = Math.max(0.10f, restCenterY - sampledMinY);

        return new StickCalibration(
                restCenterX, restCenterY,
                /* scale X+ */ 1f / distXPos,
                /* scale X- */ 1f / distXNeg,
                /* scale Y+ */ 1f / distYPos,
                /* scale Y- */ 1f / distYNeg,
                deadzone,
                sensitivity,
                outputCap,
                useOuterScale);
    }
}
