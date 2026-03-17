# 📡 MeshLink — Offline Bluetooth Mesh Network

> Every device is its own server. No internet. No account. No central server.  
> Devices auto-discover each other via Bluetooth and chat with end-to-end encryption.

---

## ⬇️ Download Latest Build

👉 **Go to [Releases](../../releases/latest)** to download:

| Platform | File |
|----------|------|
| 📱 Android | `MeshLink-Android.apk` |
| 🪟 Windows | `MeshLink-Setup.exe` |
| 🍎 Mac | `MeshLink.dmg` |

---

## 🚀 How to Install

### Android
1. Download `MeshLink-Android.apk` from Releases
2. Open the APK on your phone
3. If prompted: Settings → Security → **Allow unknown sources**
4. Tap Install

### Windows
1. Download `MeshLink-Setup.exe` from Releases
2. Run the installer
3. Launch MeshLink from desktop shortcut

### Mac
1. Download `MeshLink.dmg` from Releases
2. Open DMG → drag MeshLink to Applications
3. Right-click → Open (first time, to bypass Gatekeeper)

---

## 📡 How Discovery Works

```
You open MeshLink on your PC
         ↓
PC starts advertising as "ML-YOURNAME" via Bluetooth
PC scans for other "ML-*" devices simultaneously
         ↓
Phone opens MeshLink nearby
         ↓
Both devices appear on each other's RADAR automatically
No pairing · No code · No manual steps
         ↓
Tap the device → Encrypted chat opens instantly
```

---

## 🔐 Security

- **Encryption**: AES-256-GCM on every message
- **Key derivation**: PBKDF2-HMAC-SHA256 (50,000 iterations)
- **Transport**: Bluetooth BLE GATT + WebSocket LAN fallback
- **Store-carry-forward**: Messages queued and delivered on reconnect

---

## 🛠️ Build from Source

### Prerequisites
- Node.js 20+ (for Electron)
- Java 17 + Android Studio (for Android APK)

### Run Electron locally
```bash
cd electron
npm install
npm start
```

### Build releases
Releases are built automatically by GitHub Actions on every push to `main`.  
See `.github/workflows/build-release.yml`

---

## 📁 Project Structure

```
meshlink/
├── .github/workflows/
│   └── build-release.yml    ← Auto-builds APK + EXE + DMG
├── electron/
│   ├── main.js              ← Desktop app (BT + LAN scanning)
│   ├── preload.js           ← IPC bridge
│   ├── renderer/index.html  ← UI
│   └── package.json
└── android/
    └── app/src/main/
        ├── java/com/meshlink/app/MainActivity.java
        ├── assets/www/index.html   ← UI (same as Electron)
        └── AndroidManifest.xml
```
