package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.Window;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    private final Handler diagnosticsHandler = new Handler(Looper.getMainLooper());
    private boolean heartbeatRunning, frameMetricsRegistered;
    private long heartbeatAt;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!heartbeatRunning) return;
            long now = SystemClock.elapsedRealtime();
            AppLog.mainThreadStall(now - heartbeatAt);
            heartbeatAt = now;
            diagnosticsHandler.postDelayed(this, 1000);
        }
    };
    private final Window.OnFrameMetricsAvailableListener frameMetricsListener = (window, metrics, dropped) ->
            AppLog.frameMetrics(
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                    dropped);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
    }

    @Override protected void onResume() {
        super.onResume();
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        startDiagnostics();
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        stopDiagnostics();
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        else home.reset(true);
    }

    @Override protected void onDestroy() {
        stopDiagnostics();
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            AppLog.delayedInput(Math.max(0, SystemClock.uptimeMillis() - event.getEventTime()));
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void startDiagnostics() {
        heartbeatRunning = true;
        heartbeatAt = SystemClock.elapsedRealtime();
        diagnosticsHandler.removeCallbacks(heartbeat);
        diagnosticsHandler.postDelayed(heartbeat, 1000);
        if (!frameMetricsRegistered && Build.VERSION.SDK_INT >= 24) {
            getWindow().addOnFrameMetricsAvailableListener(frameMetricsListener, diagnosticsHandler);
            frameMetricsRegistered = true;
        }
    }

    private void stopDiagnostics() {
        heartbeatRunning = false;
        diagnosticsHandler.removeCallbacks(heartbeat);
        if (frameMetricsRegistered) {
            getWindow().removeOnFrameMetricsAvailableListener(frameMetricsListener);
            frameMetricsRegistered = false;
        }
    }
}
