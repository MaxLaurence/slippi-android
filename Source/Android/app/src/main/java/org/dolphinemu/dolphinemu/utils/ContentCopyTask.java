package org.dolphinemu.dolphinemu.utils;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import org.dolphinemu.dolphinemu.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ContentCopyTask {
    private static final int BUFFER_SIZE = 1 << 20;
    private static final long UI_UPDATE_INTERVAL_MS = 120L;

    public interface Callback {
        void onCopied(File file);
        void onFailed(Exception exception);
        void onCancelled();
    }

    private ContentCopyTask() {
    }

    public static void copy(Activity activity, Uri source, File destination,
                            @StringRes int titleResId, Callback callback) {
        SourceInfo sourceInfo = querySourceInfo(activity, source);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, 0);

        TextView status = new TextView(activity);
        status.setText(activity.getString(R.string.iso_import_progress_starting,
                sourceInfo.displayName));
        body.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progress = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(sourceInfo.sizeBytes <= 0);
        progress.setMax(1000);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = pad / 2;
        body.addView(progress, progressParams);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(titleResId)
                .setView(body)
                .setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.set(true))
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> cancelled.set(true));
        dialog.show();

        Thread worker = new Thread(() -> {
            File tmp = new File(destination.getParentFile(), destination.getName() + ".tmp");
            try {
                copyToTempFile(activity, source, tmp, sourceInfo, cancelled, dialog, status, progress);
                if (cancelled.get()) {
                    throw new CancellationException();
                }
                File parent = destination.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent);
                }
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("Could not replace " + destination);
                }
                if (!tmp.renameTo(destination)) {
                    throw new IOException("Could not move imported ISO into place");
                }
                runCompletion(activity, dialog, () -> callback.onCopied(destination));
            } catch (CancellationException exception) {
                if (tmp.exists() && !tmp.delete()) {
                    android.util.Log.w("ContentCopyTask", "Could not delete cancelled import " + tmp);
                }
                runCompletion(activity, dialog, callback::onCancelled);
            } catch (Exception exception) {
                if (tmp.exists() && !tmp.delete()) {
                    android.util.Log.w("ContentCopyTask", "Could not delete failed import " + tmp);
                }
                runCompletion(activity, dialog, () -> callback.onFailed(exception));
            }
        }, "ContentCopyTask");
        worker.start();
    }

    private static void copyToTempFile(Activity activity, Uri source, File tmp,
                                       SourceInfo sourceInfo, AtomicBoolean cancelled,
                                       AlertDialog dialog, TextView status,
                                       ProgressBar progress) throws IOException {
        File parent = tmp.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        if (tmp.exists() && !tmp.delete()) {
            throw new IOException("Could not replace " + tmp);
        }

        try (InputStream in = activity.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                throw new IOException("Could not open selected file");
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            long copied = 0L;
            long lastUiUpdateMs = 0L;
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (cancelled.get()) {
                    throw new CancellationException();
                }
                out.write(buffer, 0, read);
                copied += read;

                long now = SystemClock.uptimeMillis();
                if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
                    lastUiUpdateMs = now;
                    postProgress(activity, dialog, status, progress, sourceInfo, copied);
                }
            }
            postProgress(activity, dialog, status, progress, sourceInfo, copied);
        }
    }

    private static void postProgress(Activity activity, AlertDialog dialog, TextView status,
                                     ProgressBar progress, SourceInfo sourceInfo, long copied) {
        activity.runOnUiThread(() -> {
            if (!dialog.isShowing()) {
                return;
            }
            if (sourceInfo.sizeBytes > 0) {
                progress.setIndeterminate(false);
                progress.setProgress((int) Math.min(1000L, copied * 1000L / sourceInfo.sizeBytes));
                status.setText(activity.getString(R.string.iso_import_progress_known,
                        sourceInfo.displayName, formatBytes(copied), formatBytes(sourceInfo.sizeBytes)));
            } else {
                progress.setIndeterminate(true);
                status.setText(activity.getString(R.string.iso_import_progress_unknown,
                        sourceInfo.displayName, formatBytes(copied)));
            }
        });
    }

    private static void runCompletion(Activity activity, AlertDialog dialog, Runnable completion) {
        activity.runOnUiThread(() -> {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            completion.run();
        });
    }

    private static SourceInfo querySourceInfo(Activity activity, Uri source) {
        String displayName = "ISO";
        long sizeBytes = -1L;
        try (Cursor cursor = activity.getContentResolver().query(
                source, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int displayNameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (displayNameColumn >= 0) {
                    String candidate = cursor.getString(displayNameColumn);
                    if (candidate != null && !candidate.isEmpty()) {
                        displayName = candidate;
                    }
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    sizeBytes = cursor.getLong(sizeColumn);
                }
            }
        } catch (Exception ignored) {
        }
        return new SourceInfo(displayName, sizeBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GiB", bytes / (1024f * 1024f * 1024f));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.0f MiB", bytes / (1024f * 1024f));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KiB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private static final class SourceInfo {
        final String displayName;
        final long sizeBytes;

        SourceInfo(String displayName, long sizeBytes) {
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }
}
