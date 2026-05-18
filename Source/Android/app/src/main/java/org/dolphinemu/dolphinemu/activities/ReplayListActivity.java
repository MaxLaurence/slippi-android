// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.dolphinemu.dolphinemu.EmulatorCore;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.replay.ReplayItem;
import org.dolphinemu.dolphinemu.replay.ReplayMetadata;
import org.dolphinemu.dolphinemu.replay.ReplayStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lists every .slp under the managed Replays directory. Tap to play,
 * long-press to enter multi-select, overflow for bulk delete /
 * date-bucket purge. Metadata parses on a single background thread so
 * scrolling doesn't stall on cold cache.
 */
public class ReplayListActivity extends AppCompatActivity {
    private static final String TAG = "ReplayListActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String PREF_KEY_EMULATOR_CORE = EmulatorCore.PREF_KEY;

    private ReplayStore store;
    private ReplayAdapter adapter;
    private MaterialToolbar toolbar;
    private TextView emptyView;
    private TextView folderStatus;
    private long currentTotalBytes;
    private int refreshGeneration;
    private Future<?> scanFuture;
    private Future<?> metadataFuture;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService metaExecutor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                if (!ReplayConfig.setCustomReplayFolder(this, uri)) {
                    toast(getString(R.string.replay_folder_select_failed));
                    return;
                }
                applyReplayNativeConfig();
                emptyView.setVisibility(View.VISIBLE);
                emptyView.setText(R.string.replay_hud_loading);
                Context appContext = getApplicationContext();
                scanExecutor.execute(() -> {
                    ReplayConfig.MoveResult moved =
                            ReplayConfig.exportLocalReplaysToCustomFolder(appContext);
                    ReplayConfig.MoveResult staged =
                            ReplayConfig.drainNativeReplayStaging(appContext);
                    ui.post(() -> {
                        if (isFinishing()) return;
                        toast(getString(R.string.replay_folder_selected_toast,
                                moved.moved + staged.moved));
                        refresh();
                    });
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay_list);

        toolbar = findViewById(R.id.replay_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(this::onTopMenuItem);

        emptyView = findViewById(R.id.replay_empty);
        folderStatus = findViewById(R.id.replay_folder_status);

        store = new ReplayStore(this);
        adapter = new ReplayAdapter();
        RecyclerView rv = findViewById(R.id.replay_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        // "Save netplay replays" — INI-backed. Read the C++ default so
        // the visible state matches what BootCore will see.
        applyReplayNativeConfig();
        MaterialSwitch saveSwitch = findViewById(R.id.save_replays_switch);
        String saved = NativeLibrary.GetConfig(
                "Dolphin.ini", "Core", "SlippiSaveReplays", "True");
        saveSwitch.setChecked("True".equalsIgnoreCase(saved));
        saveSwitch.setOnCheckedChangeListener((b, isChecked) ->
                NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiSaveReplays",
                        isChecked ? "True" : "False"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanFuture != null) scanFuture.cancel(true);
        if (metadataFuture != null) metadataFuture.cancel(true);
        ui.removeCallbacksAndMessages(null);
        scanExecutor.shutdownNow();
        metaExecutor.shutdownNow();
    }

    private void refresh() {
        final int generation = ++refreshGeneration;
        if (scanFuture != null) scanFuture.cancel(true);
        if (metadataFuture != null) metadataFuture.cancel(true);
        if (adapter.getItemCount() == 0) {
            toolbar.setTitle(R.string.replay_list_title);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(R.string.replay_hud_loading);
        }
        Context appContext = getApplicationContext();
        scanFuture = scanExecutor.submit(() -> {
            ReplayStore.ScanResult result = store.scan();
            String folderLabel = ReplayConfig.replayFolderLabel(appContext);
            ui.post(() -> {
                if (generation != refreshGeneration || isFinishing()) return;
                currentTotalBytes = result.totalBytes;
                adapter.setItems(result.items);
                updateToolbarTitle();
                if (folderStatus != null) {
                    folderStatus.setText(getString(R.string.replay_folder_status, folderLabel));
                }
                updateEmptyState(result.items);
                enrichMetadata(generation, result.items);
            });
        });
    }

    private void enrichMetadata(int generation, List<ReplayItem> scanItems) {
        Context appContext = getApplicationContext();
        List<ReplayItem> ordered = new ArrayList<>(scanItems);
        metadataFuture = metaExecutor.submit(() -> {
            Map<ReplayItem, ReplayMetadata> parsed = new HashMap<>();
            for (ReplayItem item : ordered) {
                if (Thread.currentThread().isInterrupted()) return;
                parsed.put(item, ReplayMetadata.parse(appContext, item));
            }
            Collections.sort(ordered, (a, b) -> {
                ReplayMetadata am = parsed.get(a);
                ReplayMetadata bm = parsed.get(b);
                boolean ap = am != null && am.isPlayable();
                boolean bp = bm != null && bm.isPlayable();
                if (ap != bp) return ap ? -1 : 1;
                return Long.compare(b.lastModified(), a.lastModified());
            });
            ui.post(() -> {
                if (generation != refreshGeneration || isFinishing()) return;
                adapter.applyMetadata(ordered, parsed);
                updateToolbarTitle();
                updateEmptyState(ordered);
            });
        });
    }

    private void updateToolbarTitle() {
        if (adapter != null && adapter.hasSelection()) return;
        toolbar.setTitle(getString(R.string.replay_list_header,
                adapter.getItemCount(), ReplayStore.humanSize(currentTotalBytes)));
    }

    private void updateEmptyState(List<ReplayItem> files) {
        if (!files.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(hasIso()
                ? R.string.replay_list_empty
                : R.string.replay_list_empty_no_iso);
    }

    /** Reset the toolbar to the static "Replays · N · size" + top-bar menu. */
    private void resetToolbar() {
        toolbar.getMenu().clear();
        toolbar.inflateMenu(R.menu.menu_replay_list);
        toolbar.setOnMenuItemClickListener(this::onTopMenuItem);
    }

    private boolean onTopMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_replay_folder) {
            showReplayFolder();
            return true;
        }
        if (id == R.id.menu_replay_delete_30) {
            confirmDeleteOlder(30);
            return true;
        }
        if (id == R.id.menu_replay_delete_7) {
            confirmDeleteOlder(7);
            return true;
        }
        if (id == R.id.menu_replay_delete_all) {
            confirmDeleteAll();
            return true;
        }
        return false;
    }

    private void showReplayFolder() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_folder_title)
                .setMessage(getString(R.string.replay_folder_body,
                        ReplayConfig.replayFolderLabel(this)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.replay_folder_choose, (d, w) ->
                        folderLauncher.launch(null))
                .show();
    }

    private void applyReplayNativeConfig() {
        ReplayConfig.ensureReplayDirectories(this);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiReplayDir",
                ReplayConfig.nativeReplayWriteDir(this).getAbsolutePath());
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiReplayMonthFolders", "False");
    }

    private void confirmDeleteOlder(int days) {
        long ageMs = (long) days * 24L * 60L * 60L * 1000L;
        long cutoff = System.currentTimeMillis() - ageMs;
        List<ReplayItem> matched = new ArrayList<>();
        for (ReplayItem f : adapter.itemsSnapshot()) {
            if (f.lastModified() < cutoff) matched.add(f);
        }
        if (matched.isEmpty()) {
            toast("Nothing older than " + days + " days");
            return;
        }
        final int n = matched.size();
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_delete_confirm_title)
                .setMessage(getString(R.string.replay_delete_confirm_body, n))
                .setNegativeButton(R.string.replay_cancel, null)
                .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) ->
                        deleteReplays(matched, false))
                .show();
    }

    private void confirmDeleteAll() {
        List<ReplayItem> all = adapter.itemsSnapshot();
        int n = all.size();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_delete_confirm_title)
                .setMessage(getString(R.string.replay_delete_confirm_body, n))
                .setNegativeButton(R.string.replay_cancel, null)
                .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) ->
                        deleteReplays(all, false))
                .show();
    }

    private void confirmDeleteSelected(List<ReplayItem> selected) {
        int n = selected.size();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_delete_confirm_title)
                .setMessage(getString(R.string.replay_delete_confirm_body, n))
                .setNegativeButton(R.string.replay_cancel, null)
                .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) ->
                        deleteReplays(selected, true))
                .show();
    }

    private void deleteReplays(List<ReplayItem> selected, boolean clearSelection) {
        List<ReplayItem> files = new ArrayList<>(selected);
        scanExecutor.execute(() -> {
            int deleted = store.deleteMany(files);
            ui.post(() -> {
                if (isFinishing()) return;
                if (clearSelection) adapter.clearSelection();
                toast("Deleted " + deleted);
                refresh();
            });
        });
    }

    private boolean hasIso() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String iso = p.getString(PREF_KEY_ISO_URI, null);
        return !TextUtils.isEmpty(iso) && new File(iso).exists();
    }

    private void playReplay(ReplayItem replay) {
        if (!hasIso()) {
            toast(getString(R.string.replay_list_empty_no_iso));
            return;
        }
        ReplayMetadata meta = ReplayMetadata.parse(this, replay);
        if (!meta.isPlayable()) {
            toast(getString(R.string.replay_unparseable_toast));
            return;
        }
        File slp = ReplayConfig.prepareReplayForPlayback(this, replay);
        if (slp == null || !slp.isFile()) {
            toast(getString(R.string.replay_unparseable_toast));
            return;
        }
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String iso = p.getString(PREF_KEY_ISO_URI, null);
        EmulatorCore core = EmulatorCore.fromPref(
                p.getString(PREF_KEY_EMULATOR_CORE, EmulatorCore.DEFAULT.prefValue));
        if (core == EmulatorCore.MAINLINE) {
            if (!MainlineCore.isPackaged(this)) {
                toast(getString(R.string.core_mainline_missing_title));
                return;
            }
            Intent it = new Intent(this, MainlineEmulationActivity.class);
            it.putExtra(MainlineEmulationActivity.EXTRA_ISO_PATH, iso);
            it.putExtra(MainlineEmulationActivity.EXTRA_REPLAY_PATH, slp.getAbsolutePath());
            it.putExtra(MainlineEmulationActivity.EXTRA_USE_GC_ADAPTER, false);
            it.putExtra(MainlineEmulationActivity.EXTRA_LAUNCH_MODE,
                    MainlineEmulationActivity.LAUNCH_MODE_REPLAY);
            try {
                startActivity(it);
            } catch (RuntimeException ex) {
                Log.e(TAG, "Failed to launch embedded mainline replay: " + ex);
                toast(getString(R.string.core_mainline_launch_failed));
            }
            return;
        }
        Intent it = new Intent(this, EmulationActivity.class);
        it.putExtra(EmulationActivity.EXTRA_ISO_PATH, iso);
        it.putExtra(EmulationActivity.EXTRA_REPLAY_PATH, slp.getAbsolutePath());
        it.putExtra(EmulationActivity.EXTRA_LAUNCH_MODE, EmulationActivity.LAUNCH_MODE_REPLAY);
        // Replay mode never uses adapter input; leave the default (false).
        GpuDriverManager.prepareNativeDirectoriesForCurrentBackend(this);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "DriverLibName",
                GpuDriverManager.selectedLibraryNameForCurrentBackend(this));
        startActivity(it);
    }

    private void shareReplay(ReplayItem replay) {
        try {
            Uri uri = replay.isLocalFile()
                    ? FileProvider.getUriForFile(this, getPackageName() + ".replays", replay.file())
                    : replay.uri();
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("application/octet-stream");
            it.putExtra(Intent.EXTRA_STREAM, uri);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, getString(R.string.replay_share_chooser)));
        } catch (Throwable t) {
            Log.w(TAG, "share " + replay.name() + ": " + t);
            toast("Couldn't share");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }


    // --- adapter ----------------------------------------------------------

    private class ReplayAdapter extends RecyclerView.Adapter<ReplayRow> {
        private final List<ReplayItem> items = new ArrayList<>();
        private final Set<ReplayItem> selected = new HashSet<>();
        private final Map<ReplayItem, ReplayMetadata> metadata = new HashMap<>();

        void setItems(List<ReplayItem> next) {
            items.clear();
            items.addAll(next);
            selected.retainAll(items);
            metadata.keySet().retainAll(items);
            notifyDataSetChanged();
        }

        void applyMetadata(List<ReplayItem> ordered, Map<ReplayItem, ReplayMetadata> parsed) {
            items.clear();
            items.addAll(ordered);
            selected.retainAll(items);
            metadata.clear();
            metadata.putAll(parsed);
            notifyDataSetChanged();
        }

        void clearSelection() {
            selected.clear();
            notifyDataSetChanged();
        }

        List<ReplayItem> itemsSnapshot() {
            return new ArrayList<>(items);
        }

        boolean hasSelection() {
            return !selected.isEmpty();
        }

        @NonNull
        @Override
        public ReplayRow onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_replay, parent, false);
            return new ReplayRow(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ReplayRow h, int position) {
            ReplayItem f = items.get(position);
            boolean isSelected = selected.contains(f);
            h.bind(f, isSelected);

            h.itemView.setOnClickListener(v -> {
                if (!selected.isEmpty()) {
                    toggleSelection(f, h.getBindingAdapterPosition());
                    return;
                }
                playReplay(f);
            });
            h.itemView.setOnLongClickListener(v -> {
                toggleSelection(f, h.getBindingAdapterPosition());
                return true;
            });
            h.overflow.setOnClickListener(v -> showRowMenu(v, f));
        }

        private void toggleSelection(ReplayItem f, int position) {
            if (selected.contains(f)) selected.remove(f);
            else selected.add(f);
            if (position >= 0) notifyItemChanged(position);
            updateActionMode();
        }

        private void updateActionMode() {
            if (selected.isEmpty()) {
                resetToolbar();
                updateToolbarTitle();
                return;
            }
            toolbar.getMenu().clear();
            toolbar.getMenu().add(0, 1, 0, getString(R.string.replay_delete_selected,
                    selected.size()))
                    .setIcon(android.R.drawable.ic_menu_delete)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    confirmDeleteSelected(new ArrayList<>(selected));
                    return true;
                }
                return false;
            });
            toolbar.setTitle(getString(R.string.replay_delete_selected, selected.size()));
        }

        @Override
        public int getItemCount() { return items.size(); }

        private void showRowMenu(View anchor, ReplayItem f) {
            PopupMenu pm = new PopupMenu(ReplayListActivity.this, anchor);
            pm.getMenuInflater().inflate(R.menu.menu_replay_row, pm.getMenu());
            pm.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_row_play) {
                    playReplay(f);
                    return true;
                }
                if (id == R.id.menu_row_delete) {
                    new AlertDialog.Builder(ReplayListActivity.this)
                            .setTitle(R.string.replay_delete_confirm_title)
                            .setMessage(getString(R.string.replay_delete_confirm_body, 1))
                            .setNegativeButton(R.string.replay_cancel, null)
                            .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) ->
                                    deleteReplays(Collections.singletonList(f), false))
                            .show();
                    return true;
                }
                if (id == R.id.menu_row_share) {
                    shareReplay(f);
                    return true;
                }
                return false;
            });
            pm.show();
        }
    }

    private class ReplayRow extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView duration;
        final ImageButton overflow;

        ReplayRow(@NonNull View itemView) {
            super(itemView);
            title    = itemView.findViewById(R.id.row_title);
            subtitle = itemView.findViewById(R.id.row_subtitle);
            duration = itemView.findViewById(R.id.row_duration);
            overflow = itemView.findViewById(R.id.row_overflow);
        }

        void bind(ReplayItem f, boolean isSelected) {
            title.setText(f.name());
            subtitle.setText(DateUtils.formatDateTime(itemView.getContext(),
                    f.lastModified(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_ABBREV_MONTH));
            duration.setText("");
            itemView.setActivated(isSelected);
            ReplayMetadata m = adapter.metadata.get(f);
            if (m == null) return;
            if (m.isPlayable()) {
                title.setText(m.shortTitle());
                duration.setText(m.durationLabel());
            } else {
                title.setText(getString(R.string.replay_row_unparseable)
                        + " · " + f.name());
            }
        }
    }
}
