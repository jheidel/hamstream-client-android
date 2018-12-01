package com.jeffheidel.hamstream;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

/**
 * Created by Jeff on 12/1/2016.
 */

public class AudioDucker {
    private static final int kDuckDelayMs = 3000;

    private AudioManager mAudioManager;
    private Handler mHandler;
    private Runnable mCancel = null;
    private AudioFocusRequest mFocusRequest;

    private final AudioManager.OnAudioFocusChangeListener afChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int focusChange) {
                    // TODO: respsect other audio focus requests? meh
                }
            };

    public AudioDucker(AudioManager m) {
        mAudioManager = m;
        mHandler = new Handler();
    }

    public void tickle() {
        if (mCancel == null) {

            mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(afChangeListener)
                    .build();

            int result = mAudioManager.requestAudioFocus(mFocusRequest);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i("AudioDucker", "Acquired audio focus");
                Runnable r = new Runnable() {
                    public void run() {
                        Log.i("AudioDucker", "Releasing audio focus");
                        mCancel = null;
                        mAudioManager.abandonAudioFocusRequest(mFocusRequest);
                    }
                };
                mCancel = r;
            }
        } else {
            mHandler.removeCallbacks(mCancel);
        }
        mHandler.postDelayed(mCancel, kDuckDelayMs);
    }

    public boolean isActive() {
        return mCancel != null;
    }
}
