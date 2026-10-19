package com.abu.ars;

import android.util.Base64;
import android.util.Log;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class AesGcmEncryptionHelper {
    private static final String TAG = "AES_GCM_HELPER";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final byte VERSION_HEADER = 0x01;

    // 256-bit AES Master Secret key (32 bytes). Keep secret and share securely with authorized decryptor.
    private static final byte[] MASTER_KEY_BYTES = new byte[] {
        (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF,
        (byte)0xFE, (byte)0xDC, (byte)0xBA, (byte)0x98, (byte)0x76, (byte)0x54, (byte)0x32, (byte)0x10,
        (byte)0x11, (byte)0x22, (byte)0x33, (byte)0x44, (byte)0x55, (byte)0x66, (byte)0x77, (byte)0x88,
        (byte)0x99, (byte)0xAA, (byte)0xBB, (byte)0xCC, (byte)0xDD, (byte)0xEE, (byte)0xFF, (byte)0x00
    };

    public static String encrypt(String plainText) {
        try {
            if (plainText == null) plainText = "";
            SecretKeySpec keySpec = new SecretKeySpec(MASTER_KEY_BYTES, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[IV_LENGTH_BYTE];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Format: [Version(1 byte)] + [IV(12 bytes)] + [Ciphertext + Auth Tag]
            byte[] payload = new byte[1 + IV_LENGTH_BYTE + cipherText.length];
            payload[0] = VERSION_HEADER;
            System.arraycopy(iv, 0, payload, 1, IV_LENGTH_BYTE);
            System.arraycopy(cipherText, 0, payload, 1 + IV_LENGTH_BYTE, cipherText.length);

            String encoded = Base64.encodeToString(payload, Base64.NO_WRAP);
            Log.i(TAG, "[Encryption] AES-GCM encryption success. Output length: " + encoded.length());
            return "ARS-GCM:" + encoded;
        } catch (Exception e) {
            Log.e(TAG, "[Encryption] AES-GCM encryption failed", e);
            throw new RuntimeException("AES-GCM Encryption failed: " + e.getMessage(), e);
        }
    }

    public static String decrypt(String encryptedPayload) {
        try {
            if (encryptedPayload.startsWith("ARS-GCM:")) {
                encryptedPayload = encryptedPayload.substring(8);
            }
            byte[] decoded = Base64.decode(encryptedPayload, Base64.DEFAULT);
            if (decoded.length < 1 + IV_LENGTH_BYTE) {
                throw new IllegalArgumentException("Invalid encrypted payload length");
            }
            byte version = decoded[0];
            if (version != VERSION_HEADER) {
                throw new IllegalArgumentException("Unsupported encryption version: " + version);
            }

            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(decoded, 1, iv, 0, IV_LENGTH_BYTE);

            int cipherTextLen = decoded.length - 1 - IV_LENGTH_BYTE;
            byte[] cipherText = new byte[cipherTextLen];
            System.arraycopy(decoded, 1 + IV_LENGTH_BYTE, cipherText, 0, cipherTextLen);

            SecretKeySpec keySpec = new SecretKeySpec(MASTER_KEY_BYTES, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "[Decryption] AES-GCM decryption failed", e);
            return "DECRYPTION_ERROR: " + e.getMessage();
        }
    }
}
