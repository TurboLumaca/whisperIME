package com.whispertflite.utils;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Record button behaviour:
 * - press and hold: records while held, stops on release (push-to-talk)
 * - short tap: starts recording and keeps going, a second tap stops it
 */
public class TapOrHoldRecordListener implements View.OnTouchListener {

    public interface Callback {
        // Returns true if recording was actually started
        boolean onStartRecording();
        void onStopRecording();
        boolean isRecording();
    }

    private static final long HOLD_THRESHOLD_MS = 400;

    private final Callback callback;
    private boolean latched = false;           // recording continues after a short tap
    private boolean startedByThisPress = false;
    private long downTime;

    public TapOrHoldRecordListener(Callback callback) {
        this.callback = callback;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startedByThisPress = false;
            if (latched && callback.isRecording()) {
                latched = false;
                callback.onStopRecording();
            } else {
                latched = false;
                downTime = event.getEventTime();
                startedByThisPress = callback.onStartRecording();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!startedByThisPress) return true;
            startedByThisPress = false;
            boolean shortTap = action == MotionEvent.ACTION_UP && event.getEventTime() - downTime < HOLD_THRESHOLD_MS;
            if (shortTap && callback.isRecording()) {
                latched = true;
                v.performClick();
            } else {
                callback.onStopRecording();
            }
        }
        return true;
    }
}
