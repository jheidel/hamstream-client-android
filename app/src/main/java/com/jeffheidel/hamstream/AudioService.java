package com.jeffheidel.hamstream;

import android.util.Log;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class AudioService extends Service {
    private final String LOG = "AudioService";

    public AudioService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(LOG, "Service started.");
        return START_STICKY;
    }

    public void onDestroy() {
        Log.i(LOG, "Service destroyed.");

    }
}
