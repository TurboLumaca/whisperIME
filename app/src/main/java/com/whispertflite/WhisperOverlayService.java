package com.whispertflite;

import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.overlay.WaveformView;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating dictation button shown while a text field is being edited.
 * Implemented as an AccessibilityService because that is what allows it to
 * - draw an overlay (TYPE_ACCESSIBILITY_OVERLAY) without SYSTEM_ALERT_WINDOW,
 * - use the microphone while another app is in the foreground,
 * - insert the transcription into the focused field of another app without switching keyboard.
 */
public class WhisperOverlayService extends AccessibilityService {
    private static final String TAG = "WhisperOverlayService";
    private static final long MODEL_UNLOAD_DELAY_MS = 2 * 60 * 1000;
    private static final long VISIBILITY_CHECK_DELAY_MS = 150;
    private static final String PREF_BUBBLE_X = "overlayBubbleX";
    private static final String PREF_BUBBLE_Y = "overlayBubbleY";
    // Time the target app gets to read the temporary clip before the previous clipboard is restored
    private static final long CLIPBOARD_RESTORE_DELAY_MS = 500;

    private enum State { IDLE, RECORDING, TRANSCRIBING }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;
    private SharedPreferences sp;

    private View bubbleRoot;
    private WindowManager.LayoutParams bubbleParams;
    private boolean bubbleShown = false;

    private View panelRoot;
    private View panelCard;
    private WindowManager.LayoutParams panelParams;
    private boolean panelShown = false;
    private TextView tvStatus;
    private TextView tvText;
    private View statusDot;
    private WaveformView waveform;

    private Recorder mRecorder;
    private Whisper mWhisper;
    private boolean modelReady = false;
    private boolean modelLoading = false;
    private boolean transcriptionPending = false;

    private State state = State.IDLE;
    private boolean insertWhenDone = false;
    private int session = 0;               // incremented whenever the panel closes, stale results are ignored
    private int transcriptionSession = -1;
    private long recordingStartedAt;
    private final StringBuilder transcript = new StringBuilder();

    private final Runnable visibilityCheck = this::updateBubbleVisibility;
    private final Runnable unloadModel = this::unloadModelIfIdle;
    private final Runnable recordingTicker = new Runnable() {
        @Override
        public void run() {
            if (state != State.RECORDING) return;
            updateUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = getSystemService(WindowManager.class);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        createBubble();
        createPanel();

        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                handler.post(() -> onRecorderUpdate(message));
            }

            @Override
            public void onAmplitudeReceived(float level) {
                waveform.pushLevel(level);
            }
        });
        scheduleVisibilityCheck();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Our own bubble/panel windows cause events too, they never change the focused field
        if (event.getPackageName() != null && getPackageName().contentEquals(event.getPackageName())
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        scheduleVisibilityCheck();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubbleShown) {
            clampBubblePosition();
            windowManager.updateViewLayout(bubbleRoot, bubbleParams);
        }
        if (panelShown) {
            panelParams.width = panelWidth();
            windowManager.updateViewLayout(panelRoot, panelParams);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        closePanel();
        setBubbleVisible(false);
        handler.removeCallbacksAndMessages(null);
        if (mWhisper != null) {
            Whisper whisper = mWhisper;
            modelExecutor.execute(whisper::unloadModel);
        }
        modelExecutor.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void createBubble() {
        bubbleRoot = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleParams.x = sp.getInt(PREF_BUBBLE_X, dm.widthPixels);
        bubbleParams.y = sp.getInt(PREF_BUBBLE_Y, (int) (dm.heightPixels * 0.3f));

        View bubble = bubbleRoot.findViewById(R.id.overlay_bubble);
        bubble.setOnClickListener(v -> openPanel());
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private final int touchSlop = ViewConfiguration.get(WhisperOverlayService.this).getScaledTouchSlop();
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        dragging = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                            v.setPressed(false);
                        }
                        if (dragging && bubbleShown) {
                            bubbleParams.x = startX + (int) dx;
                            bubbleParams.y = startY + (int) dy;
                            windowManager.updateViewLayout(bubbleRoot, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (dragging) snapBubbleToEdge();
                        else v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (dragging) snapBubbleToEdge();
                        return true;
                }
                return false;
            }
        });
    }

    private void setBubbleVisible(boolean visible) {
        if (visible == bubbleShown || bubbleRoot == null) return;
        if (visible) {
            clampBubblePosition();
            windowManager.addView(bubbleRoot, bubbleParams);
            preloadModel();
        } else {
            windowManager.removeView(bubbleRoot);
            if (!panelShown) {
                handler.removeCallbacks(unloadModel);
                handler.postDelayed(unloadModel, MODEL_UNLOAD_DELAY_MS);
            }
        }
        bubbleShown = visible;
    }

    private void clampBubblePosition() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = bubbleSize();
        bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, dm.widthPixels - size));
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - size));
    }

    private int bubbleSize() {
        int measured = bubbleRoot.getWidth();
        return measured > 0 ? measured : dp(72);
    }

    private void snapBubbleToEdge() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = bubbleSize();
        int targetX = bubbleParams.x + size / 2 < dm.widthPixels / 2 ? 0 : dm.widthPixels - size;
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - size));
        ValueAnimator animator = ValueAnimator.ofInt(bubbleParams.x, targetX);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            if (!bubbleShown) return;
            bubbleParams.x = (int) a.getAnimatedValue();
            windowManager.updateViewLayout(bubbleRoot, bubbleParams);
        });
        animator.start();
        sp.edit().putInt(PREF_BUBBLE_X, targetX).putInt(PREF_BUBBLE_Y, bubbleParams.y).apply();
    }

    private void scheduleVisibilityCheck() {
        handler.removeCallbacks(visibilityCheck);
        handler.postDelayed(visibilityCheck, VISIBILITY_CHECK_DELAY_MS);
    }

    private void updateBubbleVisibility() {
        setBubbleVisible(!panelShown && isTextInputActive());
    }

    // True while a keyboard is shown or an editable field has input focus.
    // Hidden for password fields and while our own IME is active.
    private boolean isTextInputActive() {
        try {
            AccessibilityNodeInfo focus = findFocusedEditable();
            if (focus != null && focus.isPassword()) return false;
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    AccessibilityNodeInfo root = window.getRoot();
                    return root == null || root.getPackageName() == null
                            || !getPackageName().contentEquals(root.getPackageName());
                }
            }
            return focus != null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot inspect windows", e);
            return false;
        }
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) return focus;
        }
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo windowRoot = window.getRoot();
            if (windowRoot == null) continue;
            AccessibilityNodeInfo focus = windowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) return focus;
        }
        return null;
    }

    // ---------------------------------------------------------------- panel

    @SuppressLint("InflateParams")
    private void createPanel() {
        panelRoot = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        panelParams.y = dp(16);

        panelCard = panelRoot.findViewById(R.id.overlay_card);
        tvStatus = panelRoot.findViewById(R.id.overlay_status);
        tvText = panelRoot.findViewById(R.id.overlay_text);
        tvText.setMovementMethod(new ScrollingMovementMethod());
        statusDot = panelRoot.findViewById(R.id.overlay_status_dot);
        waveform = panelRoot.findViewById(R.id.overlay_waveform);

        panelRoot.findViewById(R.id.overlay_cancel).setOnClickListener(v -> closePanel());
        panelRoot.findViewById(R.id.overlay_wave_button).setOnClickListener(v -> toggleRecording());
        panelRoot.findViewById(R.id.overlay_done).setOnClickListener(v -> finishDictation());
    }

    private int panelWidth() {
        return Math.min(getResources().getDisplayMetrics().widthPixels, dp(480));
    }

    private void openPanel() {
        if (panelShown) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!modelFile().exists()) {
            Toast.makeText(this, R.string.overlay_no_model, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }

        setBubbleVisible(false);
        transcript.setLength(0);
        insertWhenDone = false;
        panelParams.width = panelWidth();
        windowManager.addView(panelRoot, panelParams);
        panelShown = true;

        panelCard.setAlpha(0f);
        panelCard.setTranslationY(dp(24));
        panelCard.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();

        handler.removeCallbacks(unloadModel);
        ensureModelLoaded();
        startRecording();
    }

    private void closePanel() {
        if (!panelShown) return;
        if (state == State.RECORDING) {
            state = State.IDLE;
            mRecorder.stop();
        } else if (state == State.TRANSCRIBING && mWhisper != null) {
            mWhisper.stop();  // abort the inference instead of letting it run for nothing
        }
        session++;
        state = State.IDLE;
        insertWhenDone = false;
        transcriptionPending = false;
        handler.removeCallbacks(recordingTicker);
        windowManager.removeView(panelRoot);
        panelShown = false;
        handler.postDelayed(unloadModel, MODEL_UNLOAD_DELAY_MS);
        scheduleVisibilityCheck();
    }

    private void toggleRecording() {
        if (state == State.RECORDING) mRecorder.stop();
        else if (state == State.IDLE) startRecording();
    }

    // Check button: stop recording if needed, wait for the transcription, then insert and close
    private void finishDictation() {
        if (state == State.RECORDING) {
            insertWhenDone = true;
            mRecorder.stop();
            return;
        }
        if (state == State.TRANSCRIBING) {
            insertWhenDone = true;
            return;
        }
        String text = transcript.toString().trim();
        if (!text.isEmpty()) insertText(text);
        closePanel();
    }

    private void startRecording() {
        if (mRecorder.isInProgress()) return;
        state = State.RECORDING;
        recordingStartedAt = SystemClock.elapsedRealtime();
        HapticFeedback.vibrate(this);
        mRecorder.start();
        updateUi();
        handler.removeCallbacks(recordingTicker);
        handler.post(recordingTicker);
    }

    private void onRecorderUpdate(String message) {
        if (message.equals(Recorder.MSG_RECORDING)) return;
        if (state != State.RECORDING) return;  // cancelled
        handler.removeCallbacks(recordingTicker);
        if (message.equals(Recorder.MSG_RECORDING_DONE)) {
            HapticFeedback.vibrate(this);
            state = State.TRANSCRIBING;
            transcriptionSession = session;
            if (modelReady) startTranscription();
            else transcriptionPending = true;
            updateUi();
        } else {
            // MSG_RECORDING_ERROR (too short) or an error text
            state = State.IDLE;
            if (insertWhenDone) {
                finishDictation();
                return;
            }
            updateUi();
            tvStatus.setText(message.equals(Recorder.MSG_RECORDING_ERROR) ? getString(R.string.error_no_input) : message);
        }
    }

    private void startTranscription() {
        transcriptionPending = false;
        if (state != State.TRANSCRIBING || transcriptionSession != session) return;
        if (mWhisper.isInProgress()) {
            // A cancelled transcription is still running, wait for it
            handler.postDelayed(this::startTranscription, 200);
            return;
        }
        mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
        String langCode = sp.getString("language", "auto");
        mWhisper.setLanguage(InputLang.getIdForLanguage(InputLang.getLangList(), langCode));
        mWhisper.start(mRecorder.getRecordedAudio());
    }

    private void onTranscriptionResult(WhisperResult whisperResult) {
        if (transcriptionSession != session || state != State.TRANSCRIBING) return;
        state = State.IDLE;
        String result = whisperResult.getResult();
        if ("zh".equals(whisperResult.getLanguage())) {
            boolean simpleChinese = sp.getBoolean("simpleChinese", false);
            result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
        }
        result = result.trim();
        if (!result.isEmpty()) {
            if (transcript.length() > 0) transcript.append(' ');
            transcript.append(result);
        }
        if (insertWhenDone) {
            finishDictation();
            return;
        }
        updateUi();
    }

    private void onTranscriptionFailed(String message) {
        if (transcriptionSession != session || state != State.TRANSCRIBING) return;
        state = State.IDLE;
        insertWhenDone = false;
        updateUi();
        tvStatus.setText(message);
    }

    private void updateUi() {
        int dotColor;
        switch (state) {
            case RECORDING:
                long elapsed = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000;
                tvStatus.setText(getString(R.string.overlay_listening,
                        String.format(Locale.ROOT, "%d:%02d", elapsed / 60, elapsed % 60)));
                dotColor = R.color.overlayRecording;
                waveform.setMode(WaveformView.Mode.RECORDING);
                break;
            case TRANSCRIBING:
                tvStatus.setText(modelReady ? R.string.overlay_transcribing : R.string.overlay_loading_model);
                dotColor = R.color.overlayLilac;
                waveform.setMode(WaveformView.Mode.PROCESSING);
                break;
            default:
                tvStatus.setText(R.string.overlay_ready);
                dotColor = R.color.overlayCreamDim;
                waveform.setMode(WaveformView.Mode.IDLE);
                break;
        }
        statusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, dotColor)));
        // "Speak now" only makes sense while recording or waiting, not while the audio is being transcribed
        tvText.setHint(state == State.TRANSCRIBING ? "" : getString(R.string.overlay_hint_speak));
        if (!TextUtils.equals(tvText.getText(), transcript)) {
            tvText.setText(transcript.toString());
            // Keep the latest text visible
            tvText.post(() -> {
                if (tvText.getLayout() == null) return;
                int bottom = tvText.getLayout().getLineTop(tvText.getLineCount())
                        - tvText.getHeight() + tvText.getPaddingTop() + tvText.getPaddingBottom();
                tvText.scrollTo(0, Math.max(0, bottom));
            });
        }
    }

    // ---------------------------------------------------------------- text insertion

    private void insertText(String text) {
        AccessibilityNodeInfo node = findFocusedEditable();
        if (node != null && node.isPassword()) return;  // never put dictated text into password fields or the clipboard
        if (node != null) {
            CharSequence current = fieldText(node);
            int len = current.length();
            int start = node.getTextSelectionStart();
            int end = node.getTextSelectionEnd();
            if (start < 0 || end < 0 || start > len || end > len) {
                start = len;
                end = len;
            }
            if (start > end) {
                int tmp = start;
                start = end;
                end = tmp;
            }
            String insert = text;
            if (start > 0 && !Character.isWhitespace(current.charAt(start - 1))) insert = " " + insert;
            if (end < len && !Character.isWhitespace(current.charAt(end))) insert = insert + " ";

            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    TextUtils.concat(current.subSequence(0, start), insert, current.subSequence(end, len)));
            // Some apps (WebView, some Compose screens) report success but ignore SET_TEXT:
            // only fall back to pasting if the field content did not change at all (avoids inserting twice)
            boolean setText = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (setText && node.refresh() && !TextUtils.equals(fieldText(node), current)) {
                int cursor = start + insert.length();
                Bundle selection = new Bundle();
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
                return;
            }
            if (pasteText(node, text)) return;
        }
        // No usable field: leave the text in the clipboard so the user can paste it
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.model_output), text));
        Toast.makeText(this, R.string.overlay_copied, Toast.LENGTH_LONG).show();
    }

    // Text of an editable node, "" while it only shows its hint
    private static CharSequence fieldText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        return text == null || node.isShowingHintText() ? "" : text;
    }

    // Pastes via a temporary clip, then restores the user's previous clipboard content
    private boolean pasteText(AccessibilityNodeInfo node, String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.getPrimaryClip();
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.model_output), text));
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false;
        handler.postDelayed(() -> {
            if (previous != null) clipboard.setPrimaryClip(previous);
            else clipboard.clearPrimaryClip();
        }, CLIPBOARD_RESTORE_DELAY_MS);
        return true;
    }

    // ---------------------------------------------------------------- model

    private File modelFile() {
        return new File(getExternalFilesDir(null), sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
    }

    private void ensureModelLoaded() {
        File modelFile = modelFile();
        if (mWhisper == null) {
            mWhisper = new Whisper(this);
            mWhisper.setListener(new Whisper.WhisperListener() {
                @Override
                public void onUpdateReceived(String message) {
                    if (message.startsWith("Transcription failed") || message.startsWith("Engine not initialized")) {
                        handler.post(() -> onTranscriptionFailed(message));
                    }
                }

                @Override
                public void onResultReceived(WhisperResult result) {
                    handler.post(() -> onTranscriptionResult(result));
                }

                @Override
                public void onProgress(int chunk, int total) {
                    handler.post(() -> {
                        if (state == State.TRANSCRIBING && total > 1) {
                            tvStatus.setText(getString(R.string.overlay_transcribing_progress, chunk + 1, total));
                        }
                    });
                }
            });
        }
        if (modelLoading) return;
        if (modelReady && mWhisper.getCurrentModelPath().equals(modelFile.getAbsolutePath())) return;

        modelReady = false;
        modelLoading = true;
        Whisper whisper = mWhisper;
        modelExecutor.execute(() -> {
            if (!whisper.getCurrentModelPath().isEmpty()) whisper.unloadModel();
            boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
            File vocabFile = new File(getExternalFilesDir(null), isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE);
            whisper.loadModel(modelFile, vocabFile, isMultilingualModel);
            Log.d(TAG, "Initialized: " + modelFile.getName());
            handler.post(() -> {
                modelLoading = false;
                modelReady = true;
                if (panelShown) updateUi();
                if (transcriptionPending) startTranscription();
            });
        });
    }

    // Load the model while the bubble is visible, so the first dictation does not wait for it
    private void preloadModel() {
        handler.removeCallbacks(unloadModel);
        if (modelFile().exists()) ensureModelLoaded();
    }

    private void unloadModelIfIdle() {
        if (panelShown || bubbleShown || mWhisper == null || !modelReady) return;
        if (mWhisper.isInProgress()) {
            handler.postDelayed(unloadModel, MODEL_UNLOAD_DELAY_MS);
            return;
        }
        modelReady = false;
        Whisper whisper = mWhisper;
        modelExecutor.execute(whisper::unloadModel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
