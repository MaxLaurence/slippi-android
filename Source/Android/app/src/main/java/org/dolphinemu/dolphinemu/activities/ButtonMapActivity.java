package org.dolphinemu.dolphinemu.activities;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;

import java.util.List;
import java.util.Locale;

/**
 * Per-device button remap UI. Displays each GameCube pad button as a
 * card row with its color-coded glyph + the physical buttons currently
 * bound to it. Tapping a row opens a listen-mode dialog that captures
 * the next physical key press as a new binding.
 *
 * The activity intentionally consumes KeyEvents while the listen dialog
 * is open so the user can press buttons that would normally navigate
 * the UI (A, B, dpad, etc.) without losing focus on the dialog.
 */
public class ButtonMapActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_KEY = "device_key";
    public static final String EXTRA_DEVICE_LABEL = "device_label";

    private String deviceKey;
    private String deviceLabel;
    private ControllerProfile profile;
    private ButtonMap map;
    private boolean isAdapterDevice;
    private int adapterPort;

    private RowAdapter adapter;

    // ─── listen-mode state ────────────────────────────────────────
    private AlertDialog listenDialog;
    private int listenTargetGcBit;
    private int listenCapturedKeyCode = 0;
    private TextView listenDetectedText;
    private TextView listenConflictText;
    private Button listenSaveBtn;

    // For adapter listen: poll the adapter's raw button mask at ~60Hz
    // and treat any newly-set bit as the captured "key code" (we
    // overload ButtonMap's keyCode field to also store source GC bits
    // for adapter devices — see the ButtonMap class header).
    private final android.os.Handler pollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int prevAdapterButtons = 0;
    private final Runnable adapterButtonPoll = new Runnable() {
        @Override public void run() {
            if (listenDialog == null || !listenDialog.isShowing()) return;
            int cur = NativeLibrary.GetGCAdapterButtonsRaw(adapterPort);
            int newBits = cur & ~prevAdapterButtons;
            prevAdapterButtons = cur;
            if (newBits != 0) {
                // Pick the lowest set bit — if the user mashed two buttons
                // simultaneously we just take the first one to keep the
                // semantics simple.
                int captured = Integer.lowestOneBit(newBits);
                handleKeyCaptured(captured);
            }
            pollHandler.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_button_map);

        deviceKey = getIntent().getStringExtra(EXTRA_DEVICE_KEY);
        deviceLabel = getIntent().getStringExtra(EXTRA_DEVICE_LABEL);
        if (deviceKey == null) deviceKey = ControllerProfile.DEVICE_BUILTIN;
        if (deviceLabel == null) deviceLabel = "Controller";

        // For adapter ports, the wizard's listen mode polls the
        // adapter's raw button mask instead of waiting for an Android
        // KeyEvent (the adapter doesn't generate KeyEvents — it goes
        // straight through USB to GCAdapter::Input).
        isAdapterDevice = deviceKey.startsWith("adapter:");
        if (isAdapterDevice) {
            try {
                adapterPort = Integer.parseInt(deviceKey.substring("adapter:".length()));
            } catch (NumberFormatException e) {
                adapterPort = 0;
            }
        }

        profile = new ControllerProfile(this);
        map = profile.getButtonMap(deviceKey);

        ((TextView) findViewById(R.id.bm_subtitle)).setText(deviceLabel);

        RecyclerView list = findViewById(R.id.bm_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.bm_done).setOnClickListener(v -> {
            profile.putButtonMap(deviceKey, map);
            finish();
        });
        findViewById(R.id.bm_reset).setOnClickListener(v -> confirmReset());
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset all bindings?")
                .setMessage("This restores the original button mapping for " + deviceLabel + ".")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> {
                    map = ButtonMap.defaults();
                    profile.clearButtonMap(deviceKey);
                    adapter.notifyDataSetChanged();
                })
                .show();
    }

    // ─── Listen dialog ─────────────────────────────────────────────

    private void openListen(int gcBit) {
        listenTargetGcBit = gcBit;
        listenCapturedKeyCode = 0;

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_button_listen, null);
        ((TextView) v.findViewById(R.id.listen_title)).setText(
                "Bind GC " + ButtonMap.labelForGcBit(gcBit));
        TextView existing = v.findViewById(R.id.listen_existing);
        existing.setText("Currently bound: " + map.describeBindingsFor(gcBit, isAdapterDevice));
        listenDetectedText = v.findViewById(R.id.listen_detected);
        listenConflictText = v.findViewById(R.id.listen_conflict);
        listenDetectedText.setText(isAdapterDevice
                ? "Press a button on the controller in adapter port "
                        + (adapterPort + 1) + "…"
                : "Press a button…");

        listenSaveBtn = v.findViewById(R.id.listen_save);
        listenSaveBtn.setEnabled(false);
        listenSaveBtn.setOnClickListener(b -> commitListen(/*replace*/ false));

        Button cancel = v.findViewById(R.id.listen_cancel);
        cancel.setOnClickListener(b -> dismissListen());

        Button unbindAll = v.findViewById(R.id.listen_unbind_all);
        unbindAll.setEnabled(!map.keysBoundTo(gcBit).isEmpty());
        unbindAll.setOnClickListener(b -> {
            map.unbindAllFor(gcBit);
            adapter.notifyDataSetChanged();
            dismissListen();
        });

        listenDialog = new AlertDialog.Builder(this)
                .setView(v)
                .setCancelable(true)
                .create();
        listenDialog.setOnDismissListener(d -> {
            listenDialog = null;
            pollHandler.removeCallbacks(adapterButtonPoll);
        });
        if (isAdapterDevice) {
            // Adapter port: poll GCAdapter::Input bit mask. A rising
            // edge on any GC button bit = the user just pressed a
            // physical button on the adapter controller, capture it.
            prevAdapterButtons = NativeLibrary.GetGCAdapterButtonsRaw(adapterPort);
            pollHandler.post(adapterButtonPoll);
            // Still wire OnKeyListener so the Thor's own back / escape
            // keys can dismiss the dialog cleanly.
            listenDialog.setOnKeyListener((d, keyCode, ev) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) dismissListen();
                    return true;
                }
                // Don't consume — let dialog buttons receive touch focus.
                return false;
            });
        } else {
            // Built-in / Bluetooth path: AlertDialog opens in its own
            // window so the Activity's dispatchKeyEvent doesn't fire
            // here. Intercept keys at the dialog level. Returning true
            // consumes the event so the dialog's default focus
            // navigation doesn't fire on dpad / etc.
            listenDialog.setOnKeyListener((d, keyCode, ev) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) dismissListen();
                    return true;
                }
                if (KeyEvent.isModifierKey(keyCode)) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    handleKeyCaptured(keyCode);
                }
                return true;
            });
        }
        listenDialog.show();
    }

    private void handleKeyCaptured(int captured) {
        listenCapturedKeyCode = captured;
        // For adapter ports the captured value is a GC source bit, not
        // an Android key code — describe it as "GC A" rather than the
        // raw integer.
        listenDetectedText.setText(isAdapterDevice
                ? "GC " + ButtonMap.labelForGcBit(captured) + " button"
                : ButtonMap.keyName(captured) + "  (keyCode=" + captured + ")");
        listenSaveBtn.setEnabled(true);
        int existing = map.currentGcFor(captured);
        if (existing != 0 && existing != listenTargetGcBit) {
            listenConflictText.setText("Heads-up: that physical button is currently bound to GC "
                    + ButtonMap.labelForGcBit(existing) + ".");
            listenConflictText.setVisibility(View.VISIBLE);
        } else {
            listenConflictText.setVisibility(View.GONE);
        }
    }

    private void dismissListen() {
        if (listenDialog != null) listenDialog.dismiss();
    }

    private void commitListen(boolean replaceConflicting) {
        if (listenCapturedKeyCode == 0) return;
        // If the captured key is already bound elsewhere and the user
        // hasn't confirmed replacing it yet, route through the
        // conflict-confirmation dialog.
        int existingGc = map.currentGcFor(listenCapturedKeyCode);
        if (existingGc != 0 && existingGc != listenTargetGcBit && !replaceConflicting) {
            new AlertDialog.Builder(this)
                    .setTitle("Replace existing binding?")
                    .setMessage(ButtonMap.keyName(listenCapturedKeyCode)
                            + " is currently bound to GC " + ButtonMap.labelForGcBit(existingGc)
                            + ". Replace that with GC " + ButtonMap.labelForGcBit(listenTargetGcBit) + "?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace", (d, w) -> commitListen(true))
                    .show();
            return;
        }
        map.bind(listenCapturedKeyCode, listenTargetGcBit);
        adapter.notifyDataSetChanged();
        dismissListen();
    }

    // ─── Per-row glyph styling ────────────────────────────────────

    private static int colorForGcBit(int gcBit) {
        switch (gcBit) {
            case ButtonMap.GC_BTN_A:     return R.color.gc_a_green;
            case ButtonMap.GC_BTN_B:     return R.color.gc_b_red;
            case ButtonMap.GC_BTN_X:     return R.color.gc_x_grey;
            case ButtonMap.GC_BTN_Y:     return R.color.gc_y_grey;
            case ButtonMap.GC_BTN_START: return R.color.gc_start_red;
            case ButtonMap.GC_TRIG_Z:    return R.color.gc_z_purple;
            case ButtonMap.GC_TRIG_L:
            case ButtonMap.GC_TRIG_R:    return R.color.gc_trigger;
            default:                     return R.color.gc_dpad;
        }
    }

    private static String letterForGcBit(int gcBit) {
        switch (gcBit) {
            case ButtonMap.GC_BTN_A:     return "A";
            case ButtonMap.GC_BTN_B:     return "B";
            case ButtonMap.GC_BTN_X:     return "X";
            case ButtonMap.GC_BTN_Y:     return "Y";
            case ButtonMap.GC_BTN_START: return "ST";
            case ButtonMap.GC_TRIG_Z:    return "Z";
            case ButtonMap.GC_TRIG_L:    return "L";
            case ButtonMap.GC_TRIG_R:    return "R";
            case ButtonMap.GC_BTN_UP:    return "↑";
            case ButtonMap.GC_BTN_DOWN:  return "↓";
            case ButtonMap.GC_BTN_LEFT:  return "←";
            case ButtonMap.GC_BTN_RIGHT: return "→";
            default:                     return "?";
        }
    }

    private class RowAdapter extends RecyclerView.Adapter<RowVH> {
        @Override
        public RowVH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_button_map, parent, false);
            return new RowVH(v);
        }

        @Override
        public void onBindViewHolder(RowVH h, int position) {
            int gcBit = ButtonMap.GC_BUTTONS_DISPLAY_ORDER[position];
            h.name.setText("GC " + ButtonMap.labelForGcBit(gcBit));
            h.bindings.setText(map.describeBindingsFor(gcBit, isAdapterDevice));
            h.glyphLetter.setText(letterForGcBit(gcBit));
            int color = ContextCompat.getColor(h.itemView.getContext(), colorForGcBit(gcBit));
            h.glyphBg.setBackgroundTintList(ColorStateList.valueOf(color));
            // Slight glyph-letter color contrast: dark glyph (dark text)
            // for light backgrounds, light glyph for dark backgrounds.
            float[] hsl = new float[3];
            androidx.core.graphics.ColorUtils.colorToHSL(color, hsl);
            h.glyphLetter.setTextColor(hsl[2] > 0.6f
                    ? 0xFF0B100E : 0xFFEDEDED);
            h.itemView.setOnClickListener(v -> openListen(gcBit));
        }

        @Override
        public int getItemCount() {
            return ButtonMap.GC_BUTTONS_DISPLAY_ORDER.length;
        }
    }

    private static class RowVH extends RecyclerView.ViewHolder {
        final View glyphBg;
        final TextView glyphLetter;
        final TextView name;
        final TextView bindings;
        RowVH(View v) {
            super(v);
            glyphBg = v.findViewById(R.id.row_glyph_bg);
            glyphLetter = v.findViewById(R.id.row_glyph_letter);
            name = v.findViewById(R.id.row_name);
            bindings = v.findViewById(R.id.row_bindings);
        }
    }
}
