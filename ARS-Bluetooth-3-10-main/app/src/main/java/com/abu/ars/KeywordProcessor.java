package com.abu.ars;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OFFLINE AI COMPRESSION ENGINE
 * Improved version with better keyword extraction and fuzzy matching logic.
 */
public class KeywordProcessor {

    private static final Set<String> EMERGENCY_TOKENS = new HashSet<>(Arrays.asList(
        "help", "sos", "emergency", "danger", "fire", "smoke", "trapped", "stuck", 
        "injured", "hurt", "blood", "bleeding", "attack", "hit", "robbery", "thief", 
        "gun", "weapon", "knife", "accident", "crash", "police", "ambulance", "doctor",
        "hospital", "choking", "breathing", "heart", "pain", "stop", "save", "flood",
        "water", "earthquake", "collapsed", "building", "locked", "kidnap", "people",
        "medical", "security", "threat", "kill", "die", "dying", "unconscious", "save"
    ));

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "a", "an", "the", "is", "are", "was", "were", "am", "i", "me", "my", "we", 
        "our", "you", "your", "he", "she", "it", "they", "them", "and", "but", "or", 
        "if", "because", "as", "until", "while", "of", "at", "by", "for", "with", 
        "about", "against", "between", "into", "through", "during", "before", "after", 
        "above", "below", "to", "from", "up", "down", "in", "out", "on", "off", "over", 
        "under", "again", "further", "then", "once", "here", "there", "when", "where", 
        "why", "how", "all", "any", "both", "each", "few", "more", "most", "other", 
        "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very",
        "please", "someone", "hello", "hi", "just", "want", "think", "going"
    ));

    public static String compressMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "emergency alert";
        }

        // Clean: keep only letters and spaces
        String cleanMessage = message.toLowerCase().replaceAll("[^a-z\\s]", " ");
        String[] words = cleanMessage.split("\\s+");
        
        List<String> resultList = new ArrayList<>();

        // 1. First Pass: Look for high-priority emergency tokens
        for (String word : words) {
            if (word.length() < 2) continue;
            if (EMERGENCY_TOKENS.contains(word)) {
                if (!resultList.contains(word)) {
                    resultList.add(word);
                }
            }
        }

        // 2. Second Pass: If no emergency tokens, fallback to significant non-stop-words
        if (resultList.isEmpty()) {
            for (String word : words) {
                if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                    if (!resultList.contains(word)) {
                        resultList.add(word);
                    }
                }
            }
        }

        // If still empty, use the original message (limited)
        if (resultList.isEmpty()) {
            return message.length() > 50 ? message.substring(0, 50) : message;
        }

        // Join and limit to 6 keywords for transmission
        StringBuilder compressed = new StringBuilder();
        int limit = Math.min(resultList.size(), 6);
        for (int i = 0; i < limit; i++) {
            compressed.append(resultList.get(i)).append(" ");
        }

        return compressed.toString().trim();
    }
}
