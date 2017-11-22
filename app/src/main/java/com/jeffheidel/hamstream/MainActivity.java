package com.jeffheidel.hamstream;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import org.java_websocket.drafts.Draft_6455;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private WebSocketClient mStatsSocket;
    private AutoReconnecter mReconnecter;

    private Timer mTimerUI;
    
    private AudioService.AudioServiceBinder mAudioServiceBinder;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            mAudioServiceBinder = (AudioService.AudioServiceBinder) service;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SeekBar sb = (SeekBar) findViewById(R.id.volumeBar);
                    sb.setMax(mAudioServiceBinder.getMaxVolume());
                    sb.setProgress(mAudioServiceBinder.getVolume());
                }
            });

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

        final Object networkReady = new Object();

        Log.i("main", "waiting for network...");

        final ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder req = new NetworkRequest.Builder();
        req.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        cm.requestNetwork(req.build(), new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i("Main", String.format("Acquired WIFI transport capability on %s", network.toString()));
                cm.bindProcessToNetwork(network);
                synchronized (networkReady) {
                    networkReady.notifyAll();
                }
            }
        });

        synchronized (networkReady) {
            try {
                networkReady.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }
        Log.i("main", "network ready, continuing");

        // Start audio streaming service and bind to it.
        Intent intent = new Intent(this, AudioService.class);
        startService(intent);
        bindService(intent, mConnection, 0);

        final Button stopbutton = (Button) findViewById(R.id.stopbutton);
        stopbutton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i("Main", "Stopping everything.");
                Intent intent = new Intent(MainActivity.this, AudioService.class);
                stopService(intent);

                finishAndRemoveTask();

                // TODO: Enable once sure all cleanup happens nicely.
                Log.i("main", "Force exit.");
                System.exit(0);
            }
        });

        final Context myapp = this;
        final Button powerbutton = (Button) findViewById(R.id.powerbutton);
        powerbutton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(myapp)
                        .setMessage("Are you sure you want to shut down the pi?")
                        .setCancelable(false)
                        .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Log.i("Main", "Sending shutdown command to the pi.");
                                mStatsSocket.send("shutdown");
                            }
                        })
                        .setNegativeButton("No", null)
                        .show();
            }
        });

        final Button wifiswitchbutton = (Button) findViewById(R.id.wifiswitchbutton);
        wifiswitchbutton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(myapp)
                        .setMessage("Are you sure you want to switch the pi's WiFi network?")
                        .setCancelable(false)
                        .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Log.i("Main", "Sending wifiswitch command to the pi.");
                                mStatsSocket.send("wifiswitch");
                            }
                        })
                        .setNegativeButton("No", null)
                        .show();
            }
        });

        final SeekBar volbar = (SeekBar) findViewById(R.id.volumeBar);
        volbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                                              @Override
                                              public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                                                  if (mAudioServiceBinder == null) {
                                                      return;
                                                  }
                                                  mAudioServiceBinder.setVolume(progress);
                                              }

                                              @Override
                                              public void onStartTrackingTouch(SeekBar seekBar) {
                                              }

                                              @Override
                                              public void onStopTrackingTouch(SeekBar seekBar) {
                                              }
                                          }
        );

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
                            public void run() {
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

                                t = (TextView) findViewById(R.id.volumeLabel);
                                t.setText(String.format("%d", mAudioServiceBinder.getVolume()));

                                SeekBar sb = (SeekBar) findViewById(R.id.volumeBar);
                                sb.setMax(mAudioServiceBinder.getMaxVolume());
                            }
                        }
                );

            }
        }, 0, 100);

    }

    @Override
    protected void onDestroy() {
        mReconnecter.stop();
        unbindService(mConnection);
        try {
            mStatsSocket.closeBlocking();
        } catch (InterruptedException e) {
            Log.e("Main", e.getMessage());
        }
        super.onDestroy();
    }

    private long lastStatsTime = 0;

    private void connectStats() {
        if (mStatsSocket != null) {
            try {
                mStatsSocket.closeBlocking();
            } catch(InterruptedException e) {
                Log.e("WebsocketStats", e.getMessage());
            }
        }

        Log.i("main", "connectStats");
        URI uri;
        try {
            // TODO: more generic.
            uri = new URI("ws://192.168.42.1:8080/stats");
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        mStatsSocket = new WebSocketClient(uri, new Draft_6455(), null, 1000) {
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
                            public void run() {
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
                Log.e("WebsocketStats", "Error " + e.getMessage());
            }
        };
        mReconnecter.setClient(mStatsSocket);

        // TODO timeout for connect so it works correctly...
        mStatsSocket.connect();
        /*
        try {
            //mStatsSocket.connectBlocking();
            mStatsSocket.connect();
        } catch(InterruptedException e) {
            Log.e("WebsocketStats", e.getMessage());
        }*/
        Log.i("main", "just connected to stats client.");
    }


}
