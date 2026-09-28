package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

// Deterministic moving target for the two-emulator E2E test. Debug-only,
// exported only in the debug manifest, and absent from release artifacts.
public final class Pattern extends Activity {

    private static final String TARGET_CLIPBOARD_TEXT = "scrcpy-e2e";
    private static final String SOURCE_CLIPBOARD_TEXT = "source-e2e";
    private static final String EXTRA_CLIPBOARD_TEXT = "clipboard_text";

    private ClipboardManager clipboard;
    private String clipboardText;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener =
            this::onClipboardChanged;

    @Override
    @android.annotation.SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String requested = getIntent().getStringExtra(EXTRA_CLIPBOARD_TEXT);
        clipboardText = SOURCE_CLIPBOARD_TEXT.equals(requested)
                ? SOURCE_CLIPBOARD_TEXT : TARGET_CLIPBOARD_TEXT;
        setContentView(new PatternView());
        clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipboardListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController insets = getWindow().getInsetsController();
            if (insets != null) insets.hide(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onDestroy() {
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipboardListener);
        super.onDestroy();
    }

    private void onClipboardChanged() {
        ClipData data = clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;
        CharSequence text = data.getItemAt(0).getText();
        if (SOURCE_CLIPBOARD_TEXT.contentEquals(text)) {
            Log.i("pattern: clipboard from source");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i("pattern: key up code=%d", event.getKeyCode());
        }
        return super.dispatchKeyEvent(event);
    }

    private final class PatternView extends View {
        private final Paint paint = new Paint();
        private int step;

        PatternView() {
            super(Pattern.this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                Log.i("pattern: touch up x=%d y=%d", (int) event.getX(), (int) event.getY());
                performClick();
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("e2e", clipboardText));
                Log.i("pattern: clipboard set");
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int mx = w / 2;
            int my = h / 2;
            paint.setColor(Color.rgb(224, 32, 32));
            canvas.drawRect(0, 0, mx, my, paint);
            paint.setColor(Color.rgb(32, 192, 48));
            canvas.drawRect(mx, 0, w, my, paint);
            paint.setColor(Color.rgb(32, 64, 224));
            canvas.drawRect(0, my, mx, h, paint);
            paint.setColor(Color.rgb(224, 192, 32));
            canvas.drawRect(mx, my, w, h, paint);

            paint.setColor(Color.WHITE);
            int x = step++ % Math.max(1, w);
            canvas.drawRect(x, 0, Math.min(w, x + 12), h, paint);
            postInvalidateDelayed(33);
        }
    }
}
