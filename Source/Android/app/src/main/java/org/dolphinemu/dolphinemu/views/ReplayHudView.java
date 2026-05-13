// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;

/**
 * Bottom-anchored transport HUD shown during .slp playback. Pokes
 * SlippiPlaybackStatus through JNI; the only UI state we keep locally
 * is the play/pause and FFW toggle bits (the native side doesn't
 * expose them as queryable state).
 *
 * Frame numbers are in Slippi's space: GAME_FIRST_FRAME = -123 (the
 * pre-Go pre-roll). The progress bar is shifted into [0, span] for
 * SeekBar's unsigned model.
 */
public class ReplayHudView extends LinearLayout {
    private static final int GAME_FIRST_FRAME = -123;
    private static final int POLL_MS = 33;  // ~30 Hz
    private static final int SECONDS_PER_5S_JUMP = 5;

    private TextView frameLabel;
    private ImageButton playPause;
    private ImageButton ffw;
    private SeekBar seek;

    private boolean isPaused = false;
    private boolean isFastForward = false;
    private boolean userDraggingSeek = false;
    private boolean polling = false;

    private final Runnable pollTick = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refreshFromNative();
            postDelayed(this, POLL_MS);
        }
    };

    public ReplayHudView(Context ctx) { this(ctx, null); }
    public ReplayHudView(Context ctx, AttributeSet a) { this(ctx, a, 0); }
    public ReplayHudView(Context ctx, AttributeSet a, int s) {
        super(ctx, a, s);
        setOrientation(HORIZONTAL);
        LayoutInflater.from(ctx).inflate(R.layout.view_replay_hud, this, true);

        playPause = findViewById(R.id.replay_play_pause);
        ImageButton jumpBack = findViewById(R.id.replay_jump_back);
        ImageButton jumpFwd  = findViewById(R.id.replay_jump_forward);
        ffw = findViewById(R.id.replay_ffw);
        seek = findViewById(R.id.replay_seek);
        frameLabel = findViewById(R.id.replay_frame_label);

        playPause.setOnClickListener(v -> togglePlayPause());
        jumpBack.setOnClickListener(v -> NativeLibrary.SetReplayJump(false));
        jumpFwd.setOnClickListener(v -> NativeLibrary.SetReplayJump(true));
        ffw.setOnClickListener(v -> toggleFfw());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    frameLabel.setText(formatFrames(progress + GAME_FIRST_FRAME,
                            bar.getMax() + GAME_FIRST_FRAME));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { userDraggingSeek = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userDraggingSeek = false;
                NativeLibrary.SetReplayTargetFrame(bar.getProgress() + GAME_FIRST_FRAME);
            }
        });

        refreshPlayPauseIcon();
        refreshFfwIcon();
        frameLabel.setText(R.string.replay_hud_loading);
    }

    public void startPolling() {
        if (polling) return;
        polling = true;
        post(pollTick);
    }

    public void stopPolling() {
        polling = false;
        removeCallbacks(pollTick);
    }

    private void togglePlayPause() {
        if (isPaused) {
            NativeLibrary.UnPauseEmulation();
            isPaused = false;
        } else {
            NativeLibrary.PauseEmulation();
            isPaused = true;
        }
        refreshPlayPauseIcon();
    }

    private void toggleFfw() {
        isFastForward = !isFastForward;
        NativeLibrary.SetReplaySpeedMode(isFastForward ? 1 : 0);
        refreshFfwIcon();
    }

    private void refreshPlayPauseIcon() {
        playPause.setImageResource(isPaused
                ? android.R.drawable.ic_media_play
                : android.R.drawable.ic_media_pause);
        playPause.setContentDescription(getContext().getString(isPaused
                ? R.string.replay_hud_play : R.string.replay_hud_pause));
    }

    private void refreshFfwIcon() {
        ffw.setImageResource(android.R.drawable.ic_media_ff);
        ffw.setSelected(isFastForward);
        ffw.setAlpha(isFastForward ? 1f : 0.6f);
    }

    private void refreshFromNative() {
        int current = NativeLibrary.GetReplayCurrentFrame();
        int latest = NativeLibrary.GetReplayLatestFrame();
        if (current == Integer.MIN_VALUE || latest == Integer.MIN_VALUE) {
            frameLabel.setText(R.string.replay_hud_loading);
            return;
        }
        int span = Math.max(1, latest - GAME_FIRST_FRAME);
        if (seek.getMax() != span) seek.setMax(span);
        if (!userDraggingSeek) {
            int progress = Math.max(0, Math.min(span, current - GAME_FIRST_FRAME));
            seek.setProgress(progress);
        }
        frameLabel.setText(formatFrames(current, latest));
    }

    private static String formatFrames(int current, int latest) {
        // Slippi runs at 60Hz; convert frames-since-start to mm:ss for legibility.
        int curSec = Math.max(0, (current - GAME_FIRST_FRAME)) / 60;
        int totSec = Math.max(0, (latest - GAME_FIRST_FRAME)) / 60;
        return String.format("%d:%02d / %d:%02d",
                curSec / 60, curSec % 60, totSec / 60, totSec % 60);
    }
}
