package org.dolphinemu.dolphinemu.activities;

import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.FileProvider;

import org.dolphinemu.dolphinemu.BuildConfig;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.settings.DolphinSettings;
import org.dolphinemu.dolphinemu.settings.GameSettingsOverride;
import org.dolphinemu.dolphinemu.utils.ControllerDiagnosticsCapture;
import org.dolphinemu.dolphinemu.utils.DiagnosticsExporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final String ISSUES_URL =
            "https://github.com/MaxLaurence/slippi-android/issues";

    private FrameLayout contentFrame;
    private LinearLayout nav;
    private ScrollView currentScroll;
    private Pane selectedPane = Pane.GRAPHICS;
    private final EnumMap<Pane, Integer> paneScrollY = new EnumMap<>(Pane.class);

    private final ActivityResultLauncher<String[]> pickGpuDriver =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                installImportedGpuDriver(uri);
            });

    private enum Pane {
        GRAPHICS("Graphics"),
        AUDIO("Audio & Latency"),
        CONTROLS("Controls"),
        GECKO("Gecko Codes"),
        SUPPORT("Support");

        final String label;

        Pane(String label) {
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserDirectoryBootstrap.ensureLayout(this);
        NativeLibrary.SetUserDirectory(UserDirectoryBootstrap.userDir(this).getAbsolutePath());
        NativeLibrary.CreateUserFolders();
        buildShell();
        showPane(Pane.GRAPHICS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPane(selectedPane);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(getColor(R.color.dark_bg));
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        setContentView(root);

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(0, 0, dp(14), 0);
        root.addView(nav, new LinearLayout.LayoutParams(dp(230),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(R.string.settings_screen_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        for (Pane pane : Pane.values()) {
            nav.addView(navButton(pane));
        }

        Space spacer = new Space(this);
        nav.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        Button done = new Button(this);
        done.setText(R.string.back);
        done.setOnClickListener(v -> finish());
        nav.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private TextView navButton(Pane pane) {
        TextView button = new TextView(this);
        button.setText(pane.label);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(4), 0, 0);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> showPane(pane));
        return button;
    }

    private void renderNav() {
        for (int i = 1; i < nav.getChildCount() - 2; i++) {
            View child = nav.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            Pane pane = Pane.values()[i - 1];
            boolean selected = pane == selectedPane;
            child.setBackground(rowBackground(selected));
            ((TextView) child).setTextColor(getColor(selected
                    ? android.R.color.black : R.color.text_primary));
            ((TextView) child).setTypeface(Typeface.DEFAULT, selected
                    ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void showPane(Pane pane) {
        if (currentScroll != null) {
            paneScrollY.put(selectedPane, currentScroll.getScrollY());
        }
        selectedPane = pane;
        renderNav();
        contentFrame.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        currentScroll = scroll;
        scroll.setFillViewport(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(6), 0, dp(2), dp(8));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addPaneHeader(body, pane.label);
        if (pane == Pane.GRAPHICS) {
            populateGraphicsPane(body);
        } else if (pane == Pane.AUDIO) {
            populateAudioPane(body);
        } else if (pane == Pane.CONTROLS) {
            populateControlsPane(body);
        } else if (pane == Pane.GECKO) {
            populateGeckoPane(body);
        } else {
            populateSupportPane(body);
        }

        Integer savedScrollY = paneScrollY.get(pane);
        if (savedScrollY != null && savedScrollY > 0) {
            scroll.post(() -> scroll.scrollTo(0, savedScrollY));
        }
    }

    private void refreshCurrentPane() {
        View anchor = currentScroll == null ? contentFrame : currentScroll;
        if (anchor == null) return;
        anchor.post(() -> showPane(selectedPane));
    }

    private void addPaneHeader(LinearLayout body, String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(getColor(R.color.text_primary));
        header.setTextSize(26);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void populateGraphicsPane(LinearLayout body) {
        addActionRow(body, "Backend", DolphinSettings.getBackend(this), v -> showBackendChooser());
        addActionRow(body, "Vulkan driver", GpuDriverManager.currentLabel(this),
                v -> showGpuDriverChooser());
        DolphinSettings.DisplayLatencyMode latency = DolphinSettings.getDisplayLatencyMode(this);
        addActionRow(body, "Display latency", getString(latency.labelResId),
                v -> showDisplayLatencyChooser());
        addActionRow(body, "Aspect ratio",
                DolphinSettings.labelForChoice(DolphinSettings.ASPECT_RATIOS,
                        DolphinSettings.aspectRatioForLaunch(this)),
                v -> showAspectChooser());
        addActionRow(body, "Internal resolution",
                DolphinSettings.labelForChoice(DolphinSettings.EFB_SCALES,
                        DolphinSettings.getEfbScale(this)),
                v -> showInternalResolutionChooser());
        addSwitchRow(body, "Melee widescreen mode",
                "16:9 display plus bundled Gecko widescreen code",
                GameSettingsOverride.isMeleeWidescreenEnabled(this), true,
                enabled -> {
                    DolphinSettings.setMeleeWidescreenEnabled(this, enabled);
                    refreshCurrentPane();
                });
        addSwitchRow(body, "Advanced: Dolphin widescreen hack",
                "Generic projection hack, off by default",
                DolphinSettings.isWidescreenHackEnabled(this), true,
                enabled -> {
                    DolphinSettings.setWidescreenHackEnabled(this, enabled);
                    DolphinSettings.applyIshiirukaGraphicsConfig(this, false);
                    refreshCurrentPane();
                });
    }

    private void populateAudioPane(LinearLayout body) {
        DolphinSettings.AudioPreset audio = DolphinSettings.getAudioPreset(this);
        addActionRow(body, "Audio preset", getString(audio.labelResId),
                v -> showAudioChooser());
        DolphinSettings.DisplayLatencyMode latency = DolphinSettings.getDisplayLatencyMode(this);
        addActionRow(body, "Display latency", getString(latency.labelResId),
                v -> showDisplayLatencyChooser());
    }

    private void populateControlsPane(LinearLayout body) {
        addActionRow(body, "Stick calibration", getString(R.string.calibrate_device),
                v -> showCalibrationChooser());
        addActionRow(body, "Button remap",
                hasWiiUAdapter() ? "Built-in controls and GC adapter ports"
                        : getString(R.string.calibrate_device),
                v -> showRemapChooser());
        addActionRow(body, "Touch controls", "Edit the on-screen controller layout",
                v -> startActivity(new Intent(this, TouchOverlayActivity.class)));
    }

    private void populateSupportPane(LinearLayout body) {
        addActionRow(body, "Report an issue",
                "Open the GitHub issue tracker",
                v -> openIssuesPage());
        addActionRow(body, "Export diagnostics",
                "App, device, settings, controller, and core state",
                v -> exportDiagnostics());
        addActionRow(body, "Record controller diagnostics on next launch",
                ControllerDiagnosticsCapture.captureStatus(this),
                v -> armControllerDiagnosticsCapture());
        addLabel(body, "Version " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
    }

    private void openIssuesPage() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open issues page", e);
            toast(ISSUES_URL);
        }
    }

    private void exportDiagnostics() {
        try {
            File report = DiagnosticsExporter.write(this);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".replays", report);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT,
                            getString(R.string.diagnostics_share_subject))
                    .putExtra(Intent.EXTRA_TEXT,
                            getString(R.string.diagnostics_share_text))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newUri(getContentResolver(),
                    report.getName(), uri));
            startActivity(Intent.createChooser(send,
                    getString(R.string.diagnostics_share_chooser)));
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "diagnostics export failed", e);
            toast(getString(R.string.diagnostics_export_failed));
        }
    }

    private void armControllerDiagnosticsCapture() {
        try {
            ControllerDiagnosticsCapture.armNextLaunch(this);
            new AlertDialog.Builder(this)
                    .setTitle("Controller diagnostics armed")
                    .setMessage("Launch CE Training or PLAY next. Once in-game, leave the controller neutral for a few seconds, then test main-stick directions, diagonals, C-stick directions, X/Y, shield, and modifiers. Exit back here and export diagnostics.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            refreshCurrentPane();
        } catch (IOException e) {
            Log.w(TAG, "controller diagnostics arm failed", e);
            toast(getString(R.string.diagnostics_export_failed));
        }
    }

    private void populateGeckoPane(LinearLayout body) {
        List<GameSettingsOverride.GeckoCodeEntry> bundled =
                GameSettingsOverride.loadBundledCodes(this);
        if (bundled.isEmpty()) {
            addLabel(body, "No bundled Gecko codes were found.");
        } else {
            addSectionLabel(body, "Bundled Melee codes");
            for (GameSettingsOverride.GeckoCodeEntry entry : bundled) {
                addGeckoCodeRow(body, entry);
            }
        }

        addSectionLabel(body, "Custom Gecko codes");
        List<GameSettingsOverride.CustomGeckoCode> custom =
                GameSettingsOverride.loadCustomCodes(this);
        for (GameSettingsOverride.CustomGeckoCode code : custom) {
            addCustomCodeRow(body, code);
        }
        Button add = new Button(this);
        add.setText("Add custom code");
        add.setOnClickListener(v -> showCustomCodeDialog(null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(8), 0, 0);
        body.addView(add, lp);
    }

    private void addGeckoCodeRow(LinearLayout body, GameSettingsOverride.GeckoCodeEntry entry) {
        String status;
        if (entry.required) {
            status = "Required by Slippi";
        } else if (GameSettingsOverride.isMeleeWidescreenCode(entry)) {
            status = "Controlled by Melee widescreen mode";
        } else if (entry.defaultEnabled) {
            status = "Recommended default";
        } else if (entry.optional) {
            status = "Optional";
        } else {
            status = "Bundled";
        }
        addSwitchRow(body, entry.title, status,
                GameSettingsOverride.isCodeEnabled(this, entry), !entry.required,
                enabled -> {
                    if (GameSettingsOverride.isMeleeWidescreenCode(entry)) {
                        DolphinSettings.setMeleeWidescreenEnabled(this, enabled);
                    } else {
                        GameSettingsOverride.setCodeEnabled(this, entry, enabled);
                        GameSettingsOverride.applyLiveMode(this);
                    }
                    refreshCurrentPane();
                });
    }

    private void addCustomCodeRow(LinearLayout body, GameSettingsOverride.CustomGeckoCode code) {
        addSwitchRow(body, code.title, "Custom Android override", code.enabled, true,
                enabled -> {
                    GameSettingsOverride.saveCustomCode(this,
                            new GameSettingsOverride.CustomGeckoCode(
                                    code.id, code.title, code.body, enabled));
                    GameSettingsOverride.applyLiveMode(this);
                    refreshCurrentPane();
                }, v -> showCustomCodeDialog(code));
    }

    private void addActionRow(LinearLayout body, String title, String summary,
                              View.OnClickListener listener) {
        LinearLayout row = baseRow();
        row.setOnClickListener(listener);

        LinearLayout texts = rowTextColumn(title, summary);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText(">");
        chevron.setTextColor(getColor(R.color.text_secondary));
        chevron.setTextSize(22);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(28),
                ViewGroup.LayoutParams.MATCH_PARENT));
        addRow(body, row);
    }

    private void addSwitchRow(LinearLayout body, String title, String summary,
                              boolean checked, boolean enabled,
                              SwitchChanged listener) {
        addSwitchRow(body, title, summary, checked, enabled, listener, null);
    }

    private void addSwitchRow(LinearLayout body, String title, String summary,
                              boolean checked, boolean enabled,
                              SwitchChanged listener, View.OnClickListener rowClick) {
        LinearLayout row = baseRow();
        LinearLayout texts = rowTextColumn(title, summary);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{getColor(R.color.slippi_green), getColor(R.color.text_secondary)}));
        if (rowClick == null) {
            toggle.setClickable(false);
            toggle.setFocusable(false);
        } else {
            toggle.setOnClickListener(v -> listener.onChanged(toggle.isChecked()));
        }
        row.addView(toggle, new LinearLayout.LayoutParams(dp(72),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (rowClick != null) {
            row.setOnClickListener(rowClick);
        } else if (enabled) {
            row.setOnClickListener(v -> {
                toggle.setChecked(!toggle.isChecked());
                listener.onChanged(toggle.isChecked());
            });
        }
        addRow(body, row);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setMinimumHeight(dp(68));
        row.setBackground(rowBackground(false));
        return row;
    }

    private LinearLayout rowTextColumn(String title, String summary) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!TextUtils.isEmpty(summary)) {
            TextView summaryView = new TextView(this);
            summaryView.setText(summary);
            summaryView.setTextColor(getColor(R.color.text_secondary));
            summaryView.setTextSize(13);
            summaryView.setMaxLines(2);
            texts.addView(summaryView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return texts;
    }

    private void addRow(LinearLayout body, View row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        body.addView(row, lp);
    }

    private void addSectionLabel(LinearLayout body, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(getColor(R.color.text_secondary));
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(18), 0, 0);
        body.addView(view, lp);
    }

    private void addLabel(LinearLayout body, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(getColor(R.color.text_secondary));
        view.setTextSize(14);
        addRow(body, view);
    }

    private GradientDrawable rowBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(8));
        drawable.setColor(getColor(selected ? R.color.slippi_green : R.color.card_bg));
        drawable.setStroke(dp(1), getColor(selected ? R.color.slippi_green : R.color.card_stroke));
        return drawable;
    }

    private void showBackendChooser() {
        String[] labels = {"Vulkan", "OpenGL"};
        String[] values = {DolphinSettings.BACKEND_VULKAN, DolphinSettings.BACKEND_OGL};
        String current = DolphinSettings.getBackend(this);
        int checked = DolphinSettings.BACKEND_OGL.equals(current) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Backend")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    DolphinSettings.setBackend(this, values[which]);
                    NativeLibrary.SetConfig("Dolphin.ini", "Core", "GFXBackend", values[which]);
                    applyGpuDriverConfig(values[which]);
                    d.dismiss();
                    showPane(selectedPane);
                })
                .show();
    }

    private void showAudioChooser() {
        DolphinSettings.AudioPreset[] presets = DolphinSettings.AUDIO_PRESETS;
        CharSequence[] labels = new CharSequence[presets.length];
        int checked = -1;
        DolphinSettings.AudioPreset current = DolphinSettings.getAudioPreset(this);
        for (int i = 0; i < presets.length; i++) {
            labels[i] = getString(presets[i].labelResId);
            if (presets[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.audio_pick_preset)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    DolphinSettings.setAudioPreset(this, presets[which]);
                    d.dismiss();
                    showPane(selectedPane);
                })
                .show();
    }

    private void showDisplayLatencyChooser() {
        DolphinSettings.DisplayLatencyMode[] modes = DolphinSettings.DISPLAY_LATENCY_MODES;
        CharSequence[] labels = new CharSequence[modes.length];
        int checked = -1;
        DolphinSettings.DisplayLatencyMode current = DolphinSettings.getDisplayLatencyMode(this);
        for (int i = 0; i < modes.length; i++) {
            labels[i] = getString(modes[i].labelResId);
            if (modes[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.display_latency_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    DolphinSettings.setDisplayLatencyMode(this, modes[which]);
                    NativeLibrary.SetConfig("GFX.ini", "Settings",
                            "AndroidPresentMode", modes[which].configValue);
                    d.dismiss();
                    showPane(selectedPane);
                })
                .show();
    }

    private void showAspectChooser() {
        DolphinSettings.Choice[] choices = DolphinSettings.ASPECT_RATIOS;
        CharSequence[] labels = new CharSequence[choices.length];
        int current = DolphinSettings.aspectRatioForLaunch(this);
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i].label;
            if (choices[i].value == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Aspect ratio")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    int value = choices[which].value;
                    if (value != DolphinSettings.ASPECT_16_9
                            && GameSettingsOverride.isMeleeWidescreenEnabled(this)) {
                        GameSettingsOverride.setMeleeWidescreenEnabled(this, false);
                    }
                    DolphinSettings.setAspectRatio(this, value);
                    DolphinSettings.applyIshiirukaGraphicsConfig(this, false);
                    GameSettingsOverride.applyLiveMode(this);
                    d.dismiss();
                    showPane(selectedPane);
                })
                .show();
    }

    private void showInternalResolutionChooser() {
        DolphinSettings.Choice[] choices = DolphinSettings.EFB_SCALES;
        CharSequence[] labels = new CharSequence[choices.length];
        int current = DolphinSettings.getEfbScale(this);
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i].label;
            if (choices[i].value == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Internal resolution")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    DolphinSettings.setEfbScale(this, choices[which].value);
                    DolphinSettings.applyIshiirukaGraphicsConfig(this, false);
                    d.dismiss();
                    showPane(selectedPane);
                })
                .show();
    }

    private void showGpuDriverChooser() {
        if (!GpuDriverManager.canAttemptCustomDriverLoading()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.gpu_driver_title)
                    .setMessage(R.string.gpu_driver_unsupported)
                    .setPositiveButton(R.string.gpu_driver_use_system, (dialog, which) -> {
                        GpuDriverManager.useSystemDriver(this);
                        applyGpuDriverConfig();
                        showPane(selectedPane);
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
            return;
        }

        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(R.string.gpu_driver_title)
                .setMessage(R.string.gpu_driver_fetching)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        loading.show();

        new Thread(() -> {
            try {
                List<GpuDriverManager.DriverPackage> packages = GpuDriverManager.fetchCatalog();
                runOnUiThread(() -> {
                    if (loading.isShowing()) loading.dismiss();
                    showGpuDriverCatalog(packages);
                });
            } catch (Exception e) {
                Log.w(TAG, "GPU driver catalog fetch failed", e);
                runOnUiThread(() -> {
                    if (loading.isShowing()) loading.dismiss();
                    toast(getString(R.string.gpu_driver_fetch_failed,
                            e.getMessage() == null ? e.toString() : e.getMessage()));
                    showGpuDriverCatalog(new ArrayList<>());
                });
            }
        }, "SettingsGpuDriverCatalog").start();
    }

    private void showGpuDriverCatalog(List<GpuDriverManager.DriverPackage> packages) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<GpuDriverManager.CachedDriver> cachedDrivers =
                GpuDriverManager.listCachedDrivers(this);

        labels.add(getString(R.string.gpu_driver_use_system));
        actions.add(() -> {
            GpuDriverManager.useSystemDriver(this);
            applyGpuDriverConfig();
            toast(getString(R.string.gpu_driver_system_selected));
            showPane(selectedPane);
        });
        labels.add(getString(R.string.gpu_driver_import));
        actions.add(() -> pickGpuDriver.launch(new String[]{
                "application/zip", "application/octet-stream", "*/*"}));
        for (GpuDriverManager.CachedDriver cached : cachedDrivers) {
            labels.add(getString(R.string.gpu_driver_cached_item,
                    cached.label, cached.summary()));
            actions.add(() -> selectCachedGpuDriver(cached));
        }
        if (!cachedDrivers.isEmpty()) {
            labels.add(getString(R.string.gpu_driver_remove));
            actions.add(() -> {
                GpuDriverManager.removeInstalledDriver(this);
                applyGpuDriverConfig();
                toast(getString(R.string.gpu_driver_removed));
                showPane(selectedPane);
            });
        }
        for (GpuDriverManager.DriverPackage pkg : packages) {
            labels.add(pkg.displayName + "\n" + pkg.summary() + " · " + pkg.repositoryLabel);
            actions.add(() -> confirmGpuDriverInstall(pkg));
        }
        if (packages.isEmpty() && cachedDrivers.isEmpty()) {
            labels.add(getString(R.string.gpu_driver_empty));
            actions.add(() -> {});
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.gpu_driver_title_current,
                        GpuDriverManager.currentLabel(this)))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    Runnable action = actions.get(which);
                    if (action != null) action.run();
                })
                .show();
    }

    private void confirmGpuDriverInstall(GpuDriverManager.DriverPackage pkg) {
        new AlertDialog.Builder(this)
                .setTitle(pkg.displayName)
                .setMessage(getString(R.string.gpu_driver_install_confirm,
                        pkg.repositoryLabel, pkg.summary()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.gpu_driver_download,
                        (dialog, which) -> installGpuDriver(pkg))
                .show();
    }

    private void installGpuDriver(GpuDriverManager.DriverPackage pkg) {
        showGpuDriverInstallProgress(pkg.displayName, progress ->
                GpuDriverManager.installFromUrl(getApplicationContext(), pkg, progress));
    }

    private void selectCachedGpuDriver(GpuDriverManager.CachedDriver driver) {
        showGpuDriverInstallProgress(driver.label, progress ->
                GpuDriverManager.selectCachedDriver(getApplicationContext(), driver));
    }

    private void installImportedGpuDriver(Uri uri) {
        showGpuDriverInstallProgress(getString(R.string.gpu_driver_imported_driver), progress -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open selected driver");
                return GpuDriverManager.installFromStream(getApplicationContext(), in,
                        getString(R.string.gpu_driver_imported_driver),
                        getString(R.string.gpu_driver_import_source),
                        uri.toString(), progress);
            }
        });
    }

    private void showGpuDriverInstallProgress(String title, GpuDriverInstallAction action) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        body.setPadding(pad, pad / 2, pad, 0);
        TextView label = new TextView(this);
        label.setTextColor(getColor(R.color.text_primary));
        label.setText(R.string.gpu_driver_installing);
        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setMax(1000);
        body.addView(label);
        body.addView(progress);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(body)
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            try {
                GpuDriverManager.InstallResult result = action.run(
                        (stage, completedBytes, totalBytes) -> runOnUiThread(() -> {
                            label.setText(stage);
                            if (totalBytes > 0L && completedBytes >= 0L) {
                                progress.setIndeterminate(false);
                                progress.setProgress((int) Math.min(
                                        1000L, completedBytes * 1000L / totalBytes));
                            } else {
                                progress.setIndeterminate(true);
                            }
                        }));
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (!result.success) {
                        showGpuDriverInstallFailure(result.error);
                        return;
                    }
                    applyGpuDriverConfig();
                    toast(getString(R.string.gpu_driver_installed,
                            GpuDriverManager.currentLabel(this)));
                    showPane(selectedPane);
                });
            } catch (Exception e) {
                Log.w(TAG, "GPU driver install failed", e);
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showGpuDriverInstallFailure(e.getMessage() == null
                            ? e.toString() : e.getMessage());
                });
            }
        }, "SettingsGpuDriverInstall").start();
    }

    private void showGpuDriverInstallFailure(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gpu_driver_install_failed_title)
                .setMessage(message == null ? getString(R.string.gpu_driver_install_failed) : message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void applyGpuDriverConfig() {
        applyGpuDriverConfig(DolphinSettings.getBackend(this));
    }

    private void applyGpuDriverConfig(String backend) {
        GpuDriverManager.prepareNativeDirectoriesForBackend(this, backend);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "DriverLibName",
                GpuDriverManager.selectedLibraryNameForBackend(this, backend));
    }

    private void showRemapChooser() {
        List<String> labels = new ArrayList<>();
        List<String> deviceKeys = new ArrayList<>();
        labels.add(getString(R.string.calibrate_device));
        deviceKeys.add(ControllerProfile.DEVICE_BUILTIN);
        if (hasWiiUAdapter()) {
            for (int i = 0; i < 4; i++) {
                labels.add(getString(R.string.calibrate_adapter_port, i + 1));
                deviceKeys.add(ControllerProfile.adapterDeviceKey(i));
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.remap_pick_controller)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    Intent it = new Intent(this, ButtonMapActivity.class);
                    it.putExtra(ButtonMapActivity.EXTRA_DEVICE_KEY, deviceKeys.get(which));
                    it.putExtra(ButtonMapActivity.EXTRA_DEVICE_LABEL, labels.get(which));
                    startActivity(it);
                })
                .show();
    }

    private void showCalibrationChooser() {
        List<String> labels = new ArrayList<>();
        List<String> deviceKeys = new ArrayList<>();
        labels.add(getString(R.string.calibrate_device));
        deviceKeys.add(ControllerProfile.DEVICE_BUILTIN);
        new AlertDialog.Builder(this)
                .setTitle(R.string.calibrate_pick_controller)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    Intent it = new Intent(this, CalibrationActivity.class);
                    it.putExtra(CalibrationActivity.EXTRA_DEVICE_KEY, deviceKeys.get(which));
                    it.putExtra(CalibrationActivity.EXTRA_DEVICE_LABEL, labels.get(which));
                    startActivity(it);
                })
                .show();
    }

    private void showCustomCodeDialog(GameSettingsOverride.CustomGeckoCode existing) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, 0);

        EditText title = new EditText(this);
        title.setHint("Code name");
        title.setSingleLine(true);
        title.setTextColor(getColor(R.color.text_primary));
        title.setHintTextColor(getColor(R.color.text_secondary));
        if (existing != null) title.setText(existing.title);
        body.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText code = new EditText(this);
        code.setHint("04000000 00000000");
        code.setMinLines(6);
        code.setGravity(Gravity.TOP | Gravity.START);
        code.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setTextColor(getColor(R.color.text_primary));
        code.setHintTextColor(getColor(R.color.text_secondary));
        if (existing != null) code.setText(existing.body);
        body.addView(code, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));

        SwitchCompat enabled = new SwitchCompat(this);
        enabled.setText("Enabled");
        enabled.setTextColor(getColor(R.color.text_primary));
        enabled.setChecked(existing == null || existing.enabled);
        body.addView(enabled, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add custom code" : "Edit custom code")
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        if (existing != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Delete",
                    (d, which) -> {
                        GameSettingsOverride.deleteCustomCode(this, existing.id);
                        GameSettingsOverride.applyLiveMode(this);
                        showPane(selectedPane);
                    });
        }
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String titleText = title.getText().toString().trim();
                    String bodyText = code.getText().toString().trim();
                    String error = GameSettingsOverride.validateCustomCode(titleText, bodyText);
                    if (error != null) {
                        toast(error);
                        return;
                    }
                    GameSettingsOverride.saveCustomCode(this,
                            new GameSettingsOverride.CustomGeckoCode(
                                    existing == null ? "" : existing.id,
                                    titleText, bodyText, enabled.isChecked()));
                    GameSettingsOverride.applyLiveMode(this);
                    dialog.dismiss();
                    showPane(selectedPane);
                }));
        dialog.show();
    }

    private boolean hasWiiUAdapter() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (manager == null) return false;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == 0x057E && device.getProductId() == 0x0337) {
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface SwitchChanged {
        void onChanged(boolean enabled);
    }

    private interface GpuDriverInstallAction {
        GpuDriverManager.InstallResult run(GpuDriverManager.ProgressListener progress)
                throws IOException;
    }
}
