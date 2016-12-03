package com.jeffheidel.hamstream;


import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;

import android.content.ComponentName;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.os.Build;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.nio.ByteBuffer;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.content.Context;
import java.util.Timer;
import java.util.TimerTask;
import android.text.format.Formatter;
import android.content.Intent;
import android.content.ServiceConnection;
import android.widget.Button;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    // TODO: move some of this to a service to get better behavior?

    private WebSocketClient mStatsSocket;
    private AutoReconnecter mReconnecter;

    private Timer mTimerUI;


    private AudioService.AudioServiceBinder mAudioServiceBinder;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            mAudioServiceBinder = (AudioService.AudioServiceBinder) service;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mAudioServiceBinder = null;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start audio streaming service and bind to it.
        Intent intent = new Intent(this, AudioService.class);
        startService(intent);
        bindService(intent, mConnection, 0);

        final Button button = (Button) findViewById(R.id.stopbutton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i("Main", "Stopping everything.");
                Intent intent = new Intent(MainActivity.this, AudioService.class);
                stopService(intent);

                // TODO
                finish();

                // TODO: Enable once sure all cleanup happens nicely.
                // System.exit(0);
            }
        });


        mReconnecter = new AutoReconnecter(new Runnable() {
            @Override
            public void run() {
                Log.i("Main", "Reconnecting to ws:stats");
                connectStats();
            }
        });

        // Connect stats service.
        connectStats();

        mReconnecter.startWatching();

        // Draw UI updates.
        mTimerUI = new Timer();
        mTimerUI.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(
                    new Runnable() {
                              @Override
                              public void run () {
                              if (mAudioServiceBinder == null) {
                                  return;
                              }

                              TextView t;

                              t = (TextView) findViewById(R.id.recvBytes);
                              t.setText(Formatter.formatFileSize(getApplicationContext(), mAudioServiceBinder.getBytesReceived()));

                              t = (TextView) findViewById(R.id.audioState);
                              t.setText(mAudioServiceBinder.isAudioActive() ? "Active" : "Silent");

                              t = (TextView) findViewById(R.id.status);
                              t.setText(mAudioServiceBinder.isConnected() ? "Connected" : "Not Connected");
                              }
                          }
                );

            }
        }, 0, 100);

    }

    @Override
    protected void onDestroy() {
        unbindService(mConnection);
        super.onDestroy();
    }

    private long lastStatsTime = 0;

    private void connectStats() {
        URI uri;
        try {
            // TODO: more generic.
            uri = new URI("ws://192.168.42.1:8080/stats");
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        mStatsSocket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Log.i("WebsocketStats", "Opened");
            }

            @Override
            public void onMessage(String message) {
                JSONObject stats;
                final double gain, level;
                final int audio_errors;
                try {
                    stats = new JSONObject(message);
                    gain = stats.getDouble("gain");
                    level = stats.getDouble("level") * 100;
                    audio_errors = stats.getInt("aerrors");
                } catch (JSONException e) {
                    Log.i("WebsocketStats", e.getMessage());
                    return;
                }

                // Log.i("WebsocketStats", String.format("gain: %.3f, level: %.3f", gain, level));

                // TODO: only run UI updates if the UI is visible?
                runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run () {
                                TextView t;

                                // TODO: limit text updates.

                                if (System.currentTimeMillis() - lastStatsTime > 200) {
                                   lastStatsTime = System.currentTimeMillis();

                                    t = (TextView) findViewById(R.id.level);
                                    t.setText(String.format("%.1f%%", level));

                                    t = (TextView) findViewById(R.id.gain);
                                    t.setText(String.format("%.2f x", gain));

                                    t = (TextView) findViewById(R.id.aerrors);
                                    t.setText(String.format("%d", audio_errors));
                                }

                                ProgressBar b;

                                b = (ProgressBar) findViewById(R.id.meter);
                                b.setProgress((int) level);
                            }
                        }
                );
                mReconnecter.signalOk();
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i("WebsocketStats", "Closed " + s);
                mReconnecter.triggerReconnect();
            }

            @Override
            public void onError(Exception e) {
                Log.i("WebsocketStats", "Error " + e.getMessage());
            }
        };
        mReconnecter.setClient(mStatsSocket);
        mStatsSocket.connect();
    }


}
