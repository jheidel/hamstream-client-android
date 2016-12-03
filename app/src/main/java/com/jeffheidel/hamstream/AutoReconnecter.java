package com.jeffheidel.hamstream;

import android.os.Handler;
import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;

/**
 * Created by Jeff on 12/3/2016.
 */

// TODO: this class is awful crufty.

public class AutoReconnecter {
    public static final String LOG = "AutoReconnecter";

    // Client-provided reconnection routine.
    private Runnable mReconnect;

    // Client to be watched for health.
    private WebSocketClient mClient = null;

    private static final long LIVE_CHECK_MS = 10000;
    private static final long MAX_RECONNECT_DELAY_MS = 5000;
    private static final double RECONNECT_EXP = 1.5;
    private static final long RECONNECT_DELAY_INITIAL = 50;

    private Handler mReconnectHandler;
    private Runnable mReconnecter = new Runnable() {
        @Override
        public void run() {
            if (mClient.getReadyState() == WebSocket.READYSTATE.CLOSED) {
                mReconnect.run();
            }
            mReconnectHandler.postDelayed(this, LIVE_CHECK_MS);
        }
    };

    private long reconnectDelayMs = RECONNECT_DELAY_INITIAL;

    public AutoReconnecter(Runnable reconnect) {
        mReconnect = reconnect;
        mReconnectHandler = new Handler();
    }

    public void setClient(WebSocketClient client) {
        mClient = client;
    }

    public void startWatching() {
        mReconnectHandler.removeCallbacks(mReconnecter);
        mReconnectHandler.postDelayed(mReconnecter, reconnectDelayMs);
    }

    public void signalOk() {
        reconnectDelayMs = RECONNECT_DELAY_INITIAL;
    }

    public void triggerReconnect() {
        Log.i(LOG, String.format("Reconnecting after %d ms", reconnectDelayMs));
        mReconnectHandler.removeCallbacks(mReconnecter);
        mReconnectHandler.postDelayed(mReconnecter, reconnectDelayMs);
        reconnectDelayMs = Math.min(Math.round(RECONNECT_EXP * reconnectDelayMs), MAX_RECONNECT_DELAY_MS);
    }

    public void stop() {
        mReconnectHandler.removeCallbacks(mReconnecter);
    }
}
