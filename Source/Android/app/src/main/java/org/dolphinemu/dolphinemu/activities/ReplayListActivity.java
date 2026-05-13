// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.activities;

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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.replay.ReplayMetadata;
import org.dolphinemu.dolphinemu.replay.ReplayStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists every .slp under the managed Replays directory. Tap to play,
 * long-press to enter multi-select, overflow for bulk delete /
 * date-bucket purge. Metadata parses on a single background thread so
 * scrolling doesn't stall on cold cache.
 */
public class ReplayListActivity extends AppCompatActivity {
    private static final String TAG = "ReplayListActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";

    private ReplayStore store;
    private ReplayAdapter adapter;
    private MaterialToolbar toolbar;
    private TextView emptyView;
    private final ExecutorService metaExecutor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                File dst = importFromUri(uri);
                if (dst == null) {
                    toast(getString(R.string.replay_row_unparseable));
                } else {
                    refresh();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay_list);

        toolbar = findViewById(R.id.replay_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(this::onTopMenuItem);

        emptyView = findViewById(R.id.replay_empty);

        store = new ReplayStore(this);
        adapter = new ReplayAdapter();
        RecyclerView rv = findViewById(R.id.replay_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.replay_fab);
        fab.setOnClickListener(v -> importLauncher.launch(new String[]{"*/*"}));

        // "Save netplay replays" — INI-backed. Read the C++ default so
        // the visible state matches what BootCore will see.
        MaterialSwitch saveSwitch = findViewById(R.id.save_replays_switch);
        String saved = NativeLibrary.GetConfig(
                "Dolphin.ini", "Core", "SlippiSaveReplays", "True");
        saveSwitch.setChecked("True".equalsIgnoreCase(saved));
        saveSwitch.setOnCheckedChangeListener((b, isChecked) ->
                NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiSaveReplays",
                        isChecked ? "True" : "False"));

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        metaExecutor.shutdownNow();
    }

    private void refresh() {
        List<File> files = store.list();
        adapter.setItems(files);
        toolbar.setTitle(getString(R.string.replay_list_header,
                files.size(), ReplayStore.humanSize(store.totalSize())));
        boolean noIso = !hasIso();
        if (files.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(noIso
                    ? R.string.replay_list_empty_no_iso
                    : R.string.replay_list_empty);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    /** Reset the toolbar to the static "Replays · N · size" + top-bar menu. */
    private void resetToolbar() {
        toolbar.getMenu().clear();
        toolbar.inflateMenu(R.menu.menu_replay_list);
        toolbar.setOnMenuItemClickListener(this::onTopMenuItem);
    }

    private boolean onTopMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_replay_import) {
            importLauncher.launch(new String[]{"*/*"});
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

    private void confirmDeleteOlder(int days) {
        long ageMs = (long) days * 24L * 60L * 60L * 1000L;
        long cutoff = System.currentTimeMillis() - ageMs;
        int matched = 0;
        for (File f : store.list()) if (f.lastModified() < cutoff) matched++;
        if (matched == 0) {
            toast("Nothing older than " + days + " days");
            return;
        }
        final int n = matched;
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_delete_confirm_title)
                .setMessage(getString(R.string.replay_delete_confirm_body, n))
                .setNegativeButton(R.string.replay_cancel, null)
                .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) -> {
                    int deleted = store.deleteOlderThan(ageMs);
                    toast("Deleted " + deleted);
                    refresh();
                })
                .show();
    }

    private void confirmDeleteAll() {
        int n = store.count();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_delete_confirm_title)
                .setMessage(getString(R.string.replay_delete_confirm_body, n))
                .setNegativeButton(R.string.replay_cancel, null)
                .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) -> {
                    int deleted = store.deleteAll();
                    toast("Deleted " + deleted);
                    refresh();
                })
                .show();
    }

    private void confirmDeleteSelected(List<File> selected) {
        int n = selected.size();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_delete_confirm_title)
                .setMessage(getString(R.string.replay_delete_confirm_body, n))
                .setNegativeButton(R.string.replay_cancel, null)
                .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) -> {
                    store.deleteMany(selected);
                    adapter.clearSelection();
                    refresh();
                })
                .show();
    }

    private boolean hasIso() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String iso = p.getString(PREF_KEY_ISO_URI, null);
        return !TextUtils.isEmpty(iso) && new File(iso).exists();
    }

    private void playReplay(File slp) {
        if (!hasIso()) {
            toast(getString(R.string.replay_list_empty_no_iso));
            return;
        }
        ReplayMetadata meta = ReplayMetadata.parse(slp);
        if (!meta.isPlayable()) {
            toast(getString(R.string.replay_unparseable_toast));
            return;
        }
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String iso = p.getString(PREF_KEY_ISO_URI, null);
        Intent it = new Intent(this, EmulationActivity.class);
        it.putExtra(EmulationActivity.EXTRA_ISO_PATH, iso);
        it.putExtra(EmulationActivity.EXTRA_REPLAY_PATH, slp.getAbsolutePath());
        // Replay mode never uses adapter input; leave the default (false).
        startActivity(it);
    }

    private void shareReplay(File slp) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".replays", slp);
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("application/octet-stream");
            it.putExtra(Intent.EXTRA_STREAM, uri);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, getString(R.string.replay_share_chooser)));
        } catch (Throwable t) {
            Log.w(TAG, "share " + slp + ": " + t);
            toast("Couldn't share");
        }
    }

    private File importFromUri(Uri uri) {
        File dir = ReplayConfig.replaysDir(this);
        if (!dir.exists() && !dir.mkdirs()) return null;
        String name = displayNameOf(uri);
        if (name == null) name = "imported-" + System.currentTimeMillis() + ".slp";
        File dst = uniqueFile(dir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return null;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst;
        } catch (IOException e) {
            Log.e(TAG, "import " + uri + ": " + e);
            return null;
        }
    }

    private String displayNameOf(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }


    // --- adapter ----------------------------------------------------------

    private class ReplayAdapter extends RecyclerView.Adapter<ReplayRow> {
        private final List<File> items = new ArrayList<>();
        private final Set<File> selected = new HashSet<>();

        void setItems(List<File> next) {
            items.clear();
            items.addAll(next);
            selected.retainAll(items);
            notifyDataSetChanged();
        }

        void clearSelection() {
            selected.clear();
            notifyDataSetChanged();
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
            File f = items.get(position);
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

        private void toggleSelection(File f, int position) {
            if (selected.contains(f)) selected.remove(f);
            else selected.add(f);
            if (position >= 0) notifyItemChanged(position);
            updateActionMode();
        }

        private void updateActionMode() {
            if (selected.isEmpty()) {
                resetToolbar();
                toolbar.setTitle(getString(R.string.replay_list_header,
                        items.size(), ReplayStore.humanSize(store.totalSize())));
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

        private void showRowMenu(View anchor, File f) {
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
                            .setPositiveButton(R.string.replay_delete_confirm_cta, (d, w) -> {
                                store.delete(f);
                                refresh();
                            }).show();
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
        File current;

        ReplayRow(@NonNull View itemView) {
            super(itemView);
            title    = itemView.findViewById(R.id.row_title);
            subtitle = itemView.findViewById(R.id.row_subtitle);
            duration = itemView.findViewById(R.id.row_duration);
            overflow = itemView.findViewById(R.id.row_overflow);
        }

        void bind(File f, boolean isSelected) {
            current = f;
            title.setText(f.getName());
            subtitle.setText(DateUtils.formatDateTime(itemView.getContext(),
                    f.lastModified(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_ABBREV_MONTH));
            duration.setText("");
            itemView.setActivated(isSelected);
            metaExecutor.execute(() -> {
                ReplayMetadata m = ReplayMetadata.parse(f);
                ui.post(() -> {
                    if (current != f) return;  // row was recycled
                    if (m.isPlayable()) {
                        title.setText(m.shortTitle());
                        duration.setText(m.durationLabel());
                    } else {
                        title.setText(getString(R.string.replay_row_unparseable)
                                + " · " + f.getName());
                    }
                });
            });
        }
    }
}
