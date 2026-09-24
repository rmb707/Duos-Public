package com.mccal.folio.priv;

/** Fold8Duo: what the shell-side fold engine tells the app. One-way: the engine never waits on the app. */
oneway interface IFoldPrivilegedCallback {
    /** The system has decided the phone is opening; the panels swap in about swapInMs (0 = not by us, timing unknown). */
    void onOpening(int swapInMs);
    /** One word of the hinge's true angle, in degrees. */
    void onAngle(int degrees);
    /** The system has committed CLOSED. */
    void onClosed();
    /** A line for the app's log. */
    void onLog(String message);
}
