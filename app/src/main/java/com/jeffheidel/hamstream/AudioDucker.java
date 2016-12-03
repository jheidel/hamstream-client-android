package com.jeffheidel.hamstream;

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
            int result = mAudioManager.requestAudioFocus(afChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i("AudioDucker", "Acquired audio focus");
                Runnable r = new Runnable() {
                    public void run() {
                        Log.i("AudioDucker", "Releasing audio focus");
                        mCancel = null;
                        mAudioManager.abandonAudioFocus(afChangeListener);
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
