package com.abu.ars;

import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC Encryption Helper
 * Format: Base64(IV + Ciphertext)
 * Fully compatible with Node.js crypto (Buffer.concat([iv, encrypted]))
 */
public class EncryptionHelper {

    private static final String TAG = "AES_DEBUG";
    
    // Exact secret key from backend
    private static final String PASS_PHRASE = "sos-backend-secret-key-2026-strong";
    
    // Cipher configuration: AES with CBC mode and PKCS5/7 padding
    private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";

    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return "";

        try {
            // 1. Generate a SHA-256 derived 32-byte key from the pass-phrase
            // Node.js expects exactly 32 bytes for AES-256
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(PASS_PHRASE.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            // 2. Generate a random 16-byte IV (Initialization Vector)
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // 3. Initialize Cipher for AES-256-CBC
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

            // 4. Encrypt the plain text
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 5. Concatenate IV + Encrypted Data (Exact Node.js format)
            byte[] combined = new byte[iv.length + encryptedBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

            // 6. Encode the combined byte array to a single Base64 string
            // Use NO_WRAP to ensure it's one continuous string for JSON
            String finalBase64 = Base64.encodeToString(combined, Base64.NO_WRAP);

            // --- AES_DEBUG LOGS ---
            Log.d(TAG, "Raw Text: " + plainText);
            Log.d(TAG, "IV (Hex): " + bytesToHex(iv));
            Log.d(TAG, "Encrypted (Hex): " + bytesToHex(encryptedBytes));
            Log.d(TAG, "Final Base64 Payload: " + finalBase64);

            return finalBase64;

        } catch (Exception e) {
            Log.e(TAG, "CRITICAL ENCRYPTION ERROR: " + e.getMessage(), e);
            return plainText; // Fallback to avoid app crash
        }
    }

    /**
     * Utility to convert byte array to Hex string for debugging
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
