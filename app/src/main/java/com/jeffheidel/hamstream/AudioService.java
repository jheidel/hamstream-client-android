package com.jeffheidel.hamstream;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.util.Log;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import java.util.Arrays;
import java.util.List;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.drafts.Draft_6455;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import android.app.Notification;
import android.os.Binder;
import 	android.app.NotificationManager;
import java.lang.Math;
import android.net.NetworkInfo;

public class AudioService extends Service {
    public static final String LOG = "AudioService";

    public static final int ONGOING_NOTIFICATION_ID = 1;

    // Binder given to clients
    private final IBinder mBinder = new AudioServiceBinder();

    private AudioTrack mAudioTrack;
    private AudioDucker mDucker;

    private WebSocketClient mAudioSocket;
    private AudioManager mAudioManager;

    private AutoReconnecter mReconnecter;

    // stats
    private int bytesReceived = 0;

    public class AudioServiceBinder extends Binder {
        public int getBytesReceived() {
            return bytesReceived;
        }

        public Boolean isAudioActive() {
            return mDucker.isActive();
        }

        public Boolean isConnected() {
            return mAudioSocket.getReadyState() == WebSocket.READYSTATE.OPEN;
        }

        public int getVolume() {
            return mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        }

        public int getMaxVolume() {
            return mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        }

        void setVolume(int volume) {
            if (volume < 0 || volume > getMaxVolume()) {
                return;
            }
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0);
            Log.i(LOG, String.format("Volume set to %d/%d", getVolume(), getMaxVolume()));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(LOG, "Bound to service");
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(LOG, "Received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    public Notification buildPersistentNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this)
                .setContentTitle("HamStream active")
                .setContentText("Currently streaming live audio")
                .setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_stat_walkie))
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
        return notification;
    }


    @Override
    public void onCreate() {
        Log.i(LOG, "*** Audio service created.");

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        Notification notification = buildPersistentNotification();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(ONGOING_NOTIFICATION_ID, notification);
        startForeground(ONGOING_NOTIFICATION_ID, notification);

        mReconnecter = new AutoReconnecter(new Runnable() {
            @Override
            public void run() {
                Log.i(LOG, "Reconnecting to ws:audio");
                connectAudio();
            }
        });

        initAudio();
        connectAudio();

        mReconnecter.startWatching();


        // TODO experimenting with wifi state here.
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo wifiNetworkInfo = connectivity.getActiveNetworkInfo();

        Log.i(LOG, "CURRENT NETWORK INFO: " + wifiNetworkInfo.toString());


        // TODO move this to a periodic thread.


        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);


        List<WifiConfiguration> conf = wifiManager.getConfiguredNetworks();

        // TODO handle not found.
        WifiConfiguration hamNet = conf.stream().filter(x -> x.SSID.equals("\"JH M HAM\"")).findFirst().get();

        Log.i(LOG, "Found target ham network: " + hamNet.toString());

        Log.i(LOG, "Current connection: " + wifiManager.getConnectionInfo().getSSID());

        /*
        if (!wifiManager.getConnectionInfo().getSSID().equals(hamNet.SSID)) {
            Log.i(LOG, "Not connected to target. Reconnecting!");

            wifiManager.disconnect();
            wifiManager.enableNetwork(hamNet.networkId, true);
            wifiManager.reconnect();

        }
        */

        // TODO make better.





    }

    public void onDestroy() {
        try {
            mReconnecter.stop();
            mAudioSocket.closeBlocking();
            mAudioTrack.release();
        } catch (InterruptedException e) {
            Log.e(LOG, e.getMessage());
        }
        Log.i(LOG, "*** Audio service destroyed.");
    }

    private void initAudio() {

        // TODO make constant
        int byteBuffer = 24000;

        // TODO this might be memory-leaky?


        mDucker = new AudioDucker(mAudioManager);

        // Using notification stream to allow separate music mixing.
        mAudioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, 48000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, byteBuffer,
                AudioTrack.MODE_STREAM);
        mAudioTrack.play();

    }

    // TODO reconnections
    private void connectAudio() {
        if (mAudioSocket != null) {
            Log.i(LOG, "Disconnecting in order to reconnect.");
            try {
                mAudioSocket.closeBlocking();
            } catch(InterruptedException e) {
                Log.e(LOG, e.getMessage());
            }
        }

        URI uri;
        try {
            // TODO: more generic.
            uri = new URI("ws://192.168.42.1:8080/audio");
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        mAudioSocket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Log.i(LOG, "Opened");
                //mAudioSocket.send("Hello from " + Build.MANUFACTURER + " " + Build.MODEL);
            }

            @Override
            public void onMessage(String unused_message) {}

            @Override
            public void onMessage(ByteBuffer bytes) {
                mDucker.tickle();
                bytesReceived += bytes.remaining();
                //Log.i(LOG, String.format("Received %d bytes", bytes.remaining()));
                mAudioTrack.write(bytes, bytes.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                mReconnecter.signalOk();
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i(LOG, "Closed " + s);
                mReconnecter.triggerReconnect();
            }

            @Override
            public void onError(Exception e) {
                Log.i(LOG, "Error " + e.getMessage());
            }
        };
        mReconnecter.setClient(mAudioSocket);

        Log.i(LOG, "Starting blocking connect");
        mAudioSocket.connect();
        /*
        try {
            mAudioSocket.connectBlocking();
        } catch(InterruptedException e) {
            Log.e(LOG, "Connect error: " + e.getMessage());
        }*/
        Log.i(LOG, "Connected!");
    }

}
