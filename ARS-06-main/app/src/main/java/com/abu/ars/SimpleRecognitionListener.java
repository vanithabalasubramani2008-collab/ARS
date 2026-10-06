import android.content.Context;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

public class SimpleRecognitionListener implements RecognitionListener {

    private final Context context;

    public SimpleRecognitionListener(Context context) {
        this.context = context;
    }

    @Override
    public void onReadyForSpeech(Bundle params) {}

    @Override
    public void onBeginningOfSpeech() {}

    @Override
    public void onRmsChanged(float rmsdB) {}

    @Override
    public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {}

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (matches != null && !matches.isEmpty()) {
            String text = matches.get(0).toLowerCase();

            if (text.contains("help") || text.contains("sos")) {
                // 🔥 TRIGGER SOS HERE
                // call your backend or MainActivity
            }
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {}

    @Override
    public void onEvent(int eventType, Bundle params) {}
}