package com.abu.ars;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/** Only called after the person has explicitly approved spoken help for this SOS. */
final class SpokenHelp {
    private static final String TAG = "ARS_SPOKEN_HELP";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private TextToSpeech tts;
    private int previousVolume;
    private boolean changedVolume;
    private boolean finished;

    SpokenHelp(Context context, String message, double latitude, double longitude) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String words = "Help! Emergency. " + (message == null ? "" : message) +
                (latitude == 0 && longitude == 0 ? ". My location is unavailable."
                        : String.format(Locale.US, ". My coordinates are %.5f, %.5f.", latitude, longitude));
        tts = new TextToSpeech(context.getApplicationContext(), result -> {
            if (result != TextToSpeech.SUCCESS || finished) { finish(); return; }
            tts.setLanguage(Locale.ENGLISH);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(() -> finish()); }
                @Override public void onError(String id) { main.post(() -> finish()); }
            });
            main.post(() -> {
                try {
                    if (audio != null) {
                        previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
                        changedVolume = true;
                    }
                } catch (SecurityException e) { Log.w(TAG, "Volume change not allowed", e); }
                tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "ars-emergency");
                main.postDelayed(this::finish, 30_000);
            });
        });
    }

    private void finish() {
        if (finished) return;
        finished = true;
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (changedVolume && audio != null) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0); }
            catch (SecurityException e) { Log.w(TAG, "Could not restore volume", e); }
        }
    }
}
