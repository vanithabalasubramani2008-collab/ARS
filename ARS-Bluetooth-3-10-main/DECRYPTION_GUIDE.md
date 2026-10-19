# ARS Emergency SOS - Authorized Recipient Decryption Guide

The emergency SMS received by **+919940333423** is encrypted using **AES-GCM (256-bit)** with authenticated encryption. 

## Payload Structure
The SMS starts with the prefix `ARS-GCM:` followed by a Base64-encoded binary payload:
1. **Version Header**: 1 byte (`0x01`)
2. **IV / Nonce**: 12 bytes (randomized per message)
3. **Ciphertext & Auth Tag**: Remaining bytes (AES-256-GCM ciphertext combined with 128-bit authentication tag)

## Decryption Key (Out-of-Band Shared Secret)
The shared secret key is pre-configured securely on authorized receiving devices:
- **Key Bytes**: `0123456789ABCDEFFEDCBA9876543210112233445566778899AABBCCDDEEFF00`

## Node.js / Backend Decryption Utility (`aesGcm.js`)
```javascript
const crypto = require('crypto');

const MASTER_SECRET = Buffer.from("ARS-Secure-2026-AES-GCM-Key!!", "utf8");
const VERSION_HEADER = 0x01;

function getDerivedKey() {
  const keyBytes = Buffer.alloc(32);
  MASTER_SECRET.copy(keyBytes, 0, 0, Math.min(MASTER_SECRET.length, 32));
  return keyBytes;
}

function decryptAesGcm(encryptedPayload) {
  if (encryptedPayload.startsWith("ARS-GCM:")) {
    encryptedPayload = encryptedPayload.substring(8);
  }
  const decoded = Buffer.from(encryptedPayload, 'base64');
  const iv = decoded.slice(1, 13);
  const tag = decoded.slice(decoded.length - 16);
  const ciphertext = decoded.slice(13, decoded.length - 16);

  const key = getDerivedKey();
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);

  let decrypted = decipher.update(ciphertext, undefined, 'utf8');
  decrypted += decipher.final('utf8');
  return decrypted;
}

module.exports = { decryptAesGcm };
```
