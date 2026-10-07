package com.droiddeck.launcher.core;

import android.content.Context;

/**
 * One thing a running session depends on beside the guest process itself: the audio daemon, the
 * rumble listener, the battery the client reads, the network-link file. The session starts its
 * parts in order before the guest and stops them in reverse after it. A part is handed the
 * application context, never an activity's, so nothing here can keep a screen alive.
 */
public abstract class SessionPart {
    private Context app;

    public final void attach(Context context) {
        this.app = context.getApplicationContext();
    }

    /** The application context, valid after {@link #attach}. */
    protected final Context app() {
        return app;
    }

    public abstract void start();

    public abstract void stop();

    public int suspendPid() {
        return -1;
    }
}
