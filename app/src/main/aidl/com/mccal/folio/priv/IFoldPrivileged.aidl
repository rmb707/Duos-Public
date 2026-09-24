package com.mccal.folio.priv;

import android.content.Intent;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.view.Surface;
import android.view.SurfaceControl;
import com.mccal.folio.priv.IFoldPrivilegedCallback;

/** Fold8Duo: the fold engine, hosted by Shizuku in a process running as the shell user (uid 2000). */
interface IFoldPrivileged {
    /** Shizuku's UserService convention: asks the service process to exit. */
    void destroy() = 16777114;

    /** Starts (or restarts with new settings) the engine, reporting to callback. It stops if the callback's process dies. */
    void start(IFoldPrivilegedCallback callback, boolean earlyLight, int swapDelayMs, int earlyCoverDeg) = 1;

    /** Stops the engine and releases anything it had requested. */
    void stop() = 2;

    /** One line about the service: uid, device-state route, whether it is running. */
    String describe() = 3;

    /**
     * Mirrors the screen (display 0, whichever panel is lit) into surface at width x height, for the fold effect to frost
     * when it is drawn over another app. Returns "" when the mirror is running, else why it is not. It stops by itself
     * after a few seconds, when stopMirror() is called, and when the engine's callback process dies.
     */
    String startMirror(in Surface surface, int width, int height) = 4;

    void stopMirror() = 5;

    /** Keeps a layer out of screenshots and mirrors, so an overlay drawn from the mirror never sees itself. "" = done. */
    String excludeFromCapture(in SurfaceControl layer) = 6;

    /** The system's last picture of an app that is in the background (what Recents shows), or null. The caller closes it. */
    @nullable HardwareBuffer taskSnapshot(String packageName, int userId) = 7;

    /** How the last taskSnapshot() call went, for logs and the probe. */
    String taskSnapshotRoute() = 8;

    /**
     * Starts intent for userId with its window growing out of the icon at from (screen px), corners opening from the
     * icon's to screenRadius: the shell's remote transition animates the real window (WP-53). "" = started; anything
     * else says why not, and nothing was started.
     */
    String launch(in Intent intent, int userId, in Rect from, float screenRadius) = 9;

    /** How the last launch() went, for logs and the probe. */
    String launchRoute() = 10;
    // Fold8Duo (WP-58): the platform's Full screen for one app, switched as the shell user (priv/AppCompatOverrides.kt).
    String setFullScreen(String packageName, boolean on) = 11;
    String fullScreenPackages() = 12;
}
