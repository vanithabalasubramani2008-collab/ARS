package com.abu.ars;

import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class AesGcmEncryptionHelper {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final byte VERSION_HEADER = 0x01;

    private static final byte[] MASTER_SECRET = "ARS-Secure-2026-AES-GCM-Key!!".getBytes(StandardCharsets.UTF_8);

    private static SecretKey getSecretKey() throws Exception {
        byte[] keyBytes = new byte[32];
        System.arraycopy(MASTER_SECRET, 0, keyBytes, 0, Math.min(MASTER_SECRET.length, keyBytes.length));
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[IV_LENGTH_BYTE];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[1 + IV_LENGTH_BYTE + cipherText.length];
            payload[0] = VERSION_HEADER;
            System.arraycopy(iv, 0, payload, 1, IV_LENGTH_BYTE);
            System.arraycopy(cipherText, 0, payload, 1 + IV_LENGTH_BYTE, cipherText.length);

            return "ARS-GCM:" + Base64.encodeToString(payload, Base64.NO_WRAP);
        } catch (Exception e) {
            return "ENCRYPTION_ERROR:" + e.getMessage();
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

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec);

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "DECRYPTION_ERROR:" + e.getMessage();
        }
    }
}
