package com.jeffheidel.hamstream;


import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;

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


public class MainActivity extends AppCompatActivity {

    // TODO: move some of this to a service to get better behavior?

    private WebSocketClient mAudioSocket, mStatsSocket;
    private int bytesReceived = 0;

    private AudioTrack mAudioTrack;

    private AudioDucker mDucker;

    private Timer mTimerReconnect, mTimerUI, mTimerPinger;

    private long lastUpdate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int byteBuffer = 12000;

        // TODO this might be memory-leaky?
        mDucker = new AudioDucker((AudioManager)getSystemService(Context.AUDIO_SERVICE));

        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, byteBuffer,
                AudioTrack.MODE_STREAM);
        mAudioTrack.play();

        connectAudio();
        connectStats();

        // Reconnections
        // TODO: immediate reconnects; more like exponential backoff.
        mTimerReconnect = new Timer();
        mTimerReconnect.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mAudioSocket.getReadyState() == WebSocket.READYSTATE.CLOSED) {
                    Log.i("Websocket", "Attempting reconnect.");
                    connectAudio();
                }

                if (mStatsSocket.getReadyState() == WebSocket.READYSTATE.CLOSED) {
                    Log.i("WebsocketStats", "Attempting reconnect.");
                    connectStats();
                }
            }
        }, 1000, 2000);

        // Draw UI updates.
        mTimerUI = new Timer();
        mTimerUI.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(
                    new Runnable() {
                              @Override
                              public void run () {
                                  TextView t;

                                  t = (TextView) findViewById(R.id.recvBytes);
                                  t.setText(Formatter.formatFileSize(getApplicationContext(), bytesReceived));

                                  t = (TextView) findViewById(R.id.audioState);
                                  t.setText(mDucker.isActive() ? "Active" : "Silent");
                              }
                          }
                );

            }
        }, 0, 100);

        /*
        mTimerPinger = new Timer();
        mTimerPinger.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mAudioSocket.getReadyState() == WebSocket.READYSTATE.OPEN) {
                    mAudioSocket.send("ping");
                }
                if (mStatsSocket.getReadyState() == WebSocket.READYSTATE.OPEN) {
                    mStatsSocket.send("ping");
                }
            }
        }, 60000, 60000);
        */
    }

    private void connectAudio() {
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
                Log.i("Websocket", "Opened");
                //mAudioSocket.send("Hello from " + Build.MANUFACTURER + " " + Build.MODEL);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView textView = (TextView)findViewById(R.id.status);
                        textView.setText("Connected!");
                    }
                });
            }

            @Override
            public void onMessage(String unused_message) {}

            @Override
            public void onMessage(ByteBuffer bytes) {
                mDucker.tickle();
                bytesReceived += bytes.remaining();
                mAudioTrack.write(bytes, bytes.remaining(), AudioTrack.WRITE_NON_BLOCKING);
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i("Websocket", "Closed " + s);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView textView = (TextView)findViewById(R.id.status);
                        textView.setText("Not connected");
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.i("Websocket", "Error " + e.getMessage());
            }
        };
        mAudioSocket.connect();
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





            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i("WebsocketStats", "Closed " + s);
            }

            @Override
            public void onError(Exception e) {
                Log.i("WebsocketStats", "Error " + e.getMessage());
            }
        };
        mStatsSocket.connect();
    }


}
