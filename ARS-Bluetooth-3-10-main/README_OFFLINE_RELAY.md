# ARS offline SOS prototype

This is an independent copy of `final-ARS-app`. The upstream GitHub repositories were not changed.

## What changed

- Button, voice, and volume triggers start an SOS as before. The app immediately offers **Shout for help?** with explicit Yes/Stay silent choices. The prompt does not hold up delivery. After Yes, Android text-to-speech speaks the short emergency request and available coordinates at raised media volume, then restores the previous setting. Test on each handset: an unavailable TTS voice, Bluetooth headset, or Do Not Disturb can affect audible output.
- `SOSService` builds the same `id` / `encryptedData` / `metadata` backend payload shape, first stores it locally, then attempts upload to the original `/sos` endpoint and sends an envelope over Nearby Connections. An HTTP 2xx marks server acceptance. A peer acknowledgment means **reached another phone**, not **reached a responder**.
- `NearbyRelay` uses P2P_CLUSTER discovery/advertising. Nearby app installations can receive and persist an alert. A volunteer turns on **Enable nearby relay** once and grants Android nearby-device permissions. The app automatically accepts Nearby app connections and forwards each new alert with a hop count. A phone that has not volunteered can send its own alert, but will not store another person's SOS.
- `SosStore` persists envelopes and IDs, rejects duplicates, bounds each packet to 8 KiB, limits forwarding to eight hops, and expires entries after 24 hours. The app retries queued server uploads after a validated internet connection appears while its foreground service is running.
- **Show help in Bluetooth list** is an optional, user-initiated fallback after SOS. It temporarily changes the local Bluetooth name to a compact help message, asks Android to make the phone discoverable for up to five minutes, and restores the original name afterward or on next app launch. It cannot cause an unmodified stranger's phone to display a pairing alert. Bluetooth discovery displays vary by device, and coordinates in the device name are public. If the SIM country is India, it includes 112; elsewhere it leaves the emergency number out.

## Setup and demonstration

1. Open this project in Android Studio on a machine with Android SDK 36 and Google Play services. Sync Gradle. The Nearby Connections dependency was added to `app/build.gradle.kts`.
2. Install it on at least three physical Android phones. Accept the location, microphone, and nearby-device permission requests. Enable Bluetooth, Wi-Fi, and location services on the devices as required by their Android versions.
3. On phone B, tap **Enable nearby relay**. On phone A, disconnect mobile data and internet, but leave Bluetooth/Wi-Fi radios on. Trigger SOS on A; the spoken-help prompt must appear immediately. B should receive the envelope without an approval tap.
4. Disconnect B too and bring it near a volunteered phone C. C should receive the envelope once. Connect C to the internet and inspect the `/sos` server request. The response must be HTTP 2xx before the app says the server accepted it.
5. Repeat with no peer, denied nearby permission, duplicate messages, app restart, and a headset. Verify that no delivery claim is made before an acknowledgment.

## Important limitations before deployment

- The linked `sos-backend` repository has no committed source. This app still targets the original hardcoded ngrok URL. Backend decryption, authentication, storage, emergency dispatch, and end-to-end acknowledgment could not be verified. Configure a stable HTTPS endpoint and make server delivery idempotent by `id`.
- The original client-side AES passphrase is embedded in the APK and AES-CBC does not authenticate data. This copy prevents an encryption exception from silently sending plaintext, but its current wire format is **not a production-grade end-to-end security protocol**. Nearby connections are automatically accepted to meet the requested unattended relay behavior; enroll and authenticate relay devices before public use. Do not deploy this prototype for life-critical emergency service.
- Android may stop or restrict continuous discovery in the background, and foreground services require user-visible notifications. Radio range, battery impact, doze behavior, and restart behavior must be measured on real devices. A nearby phone needs this app installed and permission granted; a phone without the app cannot automatically relay the SOS.
- The old SMS fallback was commented out in the source and remains out of scope here. The app never claims SMS delivery. If all phones lack internet, delivery waits until a relay regains it. If no participating phone is in range, the alert remains on the sender until a route exists or its 24-hour lifetime ends.
- The Bluetooth name experiment is opt-in and only visible to people actively discovering devices. It neither reaches all nearby phones nor sends them a pairing request or notification.
- This workspace lacked an Android SDK and could not fetch the Gradle distribution, so no APK build or on-device radio test was possible here. Treat the steps above as mandatory verification before presenting the feature as operational.
