package org.dolphinemu.dolphinemu.activities;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import org.dolphinemu.dolphinemu.views.TouchControlOverlayView;

public class TouchOverlayActivity extends AppCompatActivity
        implements TouchControlOverlayView.Listener {
    private TouchControlOverlayView overlay;
    private boolean launchedEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        overlay = new TouchControlOverlayView(this);
        overlay.setListener(this);
        overlay.setControlsEnabled(true);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        overlay.post(() -> {
            launchedEditor = true;
            overlay.enterEditMode();
        });
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) {
            overlay.exitEditModeCanceling();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onOverlayButton(int gcBit, boolean pressed) {
    }

    @Override
    public void onOverlayStick(String stickId, float x, float y) {
    }

    @Override
    public void onOverlayDpad(boolean up, boolean down, boolean left, boolean right) {
    }

    @Override
    public void onOverlayEditModeChanged(boolean editing) {
        if (launchedEditor && !editing) {
            finish();
        }
    }
}
