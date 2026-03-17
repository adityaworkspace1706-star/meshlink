package com.meshlink.app;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.webkit.*;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.*;
import android.util.Base64;

public class MainActivity extends AppCompatActivity {

    // ── BLE Constants ─────────────────────────────────
    private static final String MESH_SERVICE_UUID  = "0000fe2c-0000-1000-8000-00805f9b34fb";
    private static final String MESH_CHAR_TX_UUID  = "0000fe2d-0000-1000-8000-00805f9b34fb";
    private static final String MESH_CHAR_RX_UUID  = "0000fe2e-0000-1000-8000-00805f9b34fb";
    private static final String MESH_NAME_PREFIX   = "ML-";
    private static final String PREFS_NAME         = "MeshLinkPrefs";

    // ── State ──────────────────────────────────────────
    private WebView webView;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothLeAdvertiser bleAdvertiser;
    private boolean scanning = false;
    private boolean advertising = false;
    private String myId = "";
    private String myName = "";
    private SecretKey aesKey;

    private Map<String, BluetoothGatt> connectedGatts = new ConcurrentHashMap<>();
    private Map<String, BluetoothGattCharacteristic> txChars = new ConcurrentHashMap<>();
    private Map<String, String> peerNames = new ConcurrentHashMap<>();

    // ── Lifecycle ──────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load identity
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        myId   = prefs.getString("nodeId", "");
        myName = prefs.getString("nodeName", "");

        // Init crypto
        try { initCrypto(); } catch (Exception e) { e.printStackTrace(); }

        // Setup WebView
        setupWebView();

        // Check permissions
        requestBTPermissions();
    }

    // ── WebView Setup ──────────────────────────────────
    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Inject Android bridge
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        // Enable Chrome DevTools
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Restore identity if set
                if (!myId.isEmpty() && !myName.isEmpty()) {
                    String js = "window.AndroidBridge && window.dispatchAndroidIdentity('" 
                                + myId + "','" + myName + "')";
                    webView.evaluateJavascript(js, null);
                }
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // ── Android ↔ WebView Bridge ───────────────────────
    public class AndroidBridge {

        @JavascriptInterface
        public String getIdentity() {
            JSONObject obj = new JSONObject();
            try { obj.put("id", myId); obj.put("name", myName); } catch (Exception e) {}
            return obj.toString();
        }

        @JavascriptInterface
        public void setIdentity(String id, String name) {
            myId = id;
            myName = name;
            SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            ed.putString("nodeId", id);
            ed.putString("nodeName", name);
            ed.apply();
            // Start advertising with new name
            startBLEAdvertising();
        }

        @JavascriptInterface
        public void startScan() {
            runOnUiThread(() -> {
                startBLEScan();
                sendToWebView("scan-started", "{}");
            });
        }

        @JavascriptInterface
        public void stopScan() {
            runOnUiThread(() -> stopBLEScan());
        }

        @JavascriptInterface
        public void sendMessage(String peerId, String payloadJson, String channel, String mid, long ts) {
            try {
                JSONObject payload = new JSONObject(payloadJson);
                JSONObject pkt = new JSONObject();
                pkt.put("type", "chat");
                pkt.put("id", myId);
                pkt.put("name", myName);
                pkt.put("ch", channel);
                pkt.put("payload", payload);
                pkt.put("t", ts);
                pkt.put("mid", mid);
                pkt.put("hops", 0);
                pkt.put("maxHops", 5);

                String pktStr = pkt.toString();
                if (peerId == null || peerId.isEmpty() || channel.equals("broadcast")) {
                    broadcastBLE(pktStr);
                } else {
                    sendToPeer(peerId, pktStr);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        @JavascriptInterface
        public String encrypt(String text) {
            try {
                JSONObject result = encryptAES(text);
                return result.toString();
            } catch (Exception e) {
                try {
                    JSONObject r = new JSONObject();
                    r.put("c", Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT));
                    r.put("i", ""); r.put("r", 1);
                    return r.toString();
                } catch (Exception ex) { return "{}"; }
            }
        }

        @JavascriptInterface
        public String decrypt(String payloadJson) {
            try {
                JSONObject payload = new JSONObject(payloadJson);
                return decryptAES(payload);
            } catch (Exception e) { return "[encrypted]"; }
        }

        @JavascriptInterface
        public void broadcastLocation(double lat, double lng) {
            try {
                JSONObject pkt = new JSONObject();
                pkt.put("type", "location");
                pkt.put("id", myId); pkt.put("name", myName);
                pkt.put("lat", lat); pkt.put("lng", lng);
                pkt.put("t", System.currentTimeMillis());
                broadcastBLE(pkt.toString());
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // ── Send data to WebView ───────────────────────────
    public void sendToWebView(String event, String dataJson) {
        runOnUiThread(() -> {
            String js = "window.onAndroidEvent && window.onAndroidEvent('" + event + "'," + dataJson + ")";
            webView.evaluateJavascript(js, null);
        });
    }

    // ── BLE Permissions ────────────────────────────────
    private void requestBTPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 1001);
        } else {
            initBluetooth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1001) {
            initBluetooth();
        }
    }

    // ── BLE Init ───────────────────────────────────────
    private void initBluetooth() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager == null) {
            sendToWebView("bt-status", "{\"available\":false,\"reason\":\"No Bluetooth manager\"}");
            return;
        }
        btAdapter = btManager.getAdapter();
        if (btAdapter == null || !btAdapter.isEnabled()) {
            sendToWebView("bt-status", "{\"available\":false,\"reason\":\"Bluetooth disabled\"}");
            Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBt);
            return;
        }
        bleScanner   = btAdapter.getBluetoothLeScanner();
        bleAdvertiser = btAdapter.getBluetoothLeAdvertiser();
        sendToWebView("bt-status", "{\"available\":true}");

        // Start advertising immediately
        startBLEAdvertising();
        // Auto scan
        new Handler(Looper.getMainLooper()).postDelayed(this::startBLEScan, 1500);
    }

    // ── BLE Advertising (makes device visible to others) ──
    private void startBLEAdvertising() {
        if (bleAdvertiser == null || myName.isEmpty()) return;
        if (advertising) { bleAdvertiser.stopAdvertising(advCallback); }

        String advName = MESH_NAME_PREFIX + myName.toUpperCase().replace(" ", "").substring(0, Math.min(myName.length(), 8));

        try { btAdapter.setName(advName); } catch (Exception e) {}

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build();

        AdvertiseData data = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(new ParcelUuid(UUID.fromString(MESH_SERVICE_UUID)))
            .build();

        bleAdvertiser.startAdvertising(settings, data, advCallback);
        advertising = true;
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            sendToWebView("bt-status", "{\"available\":true,\"advertising\":true}");
        }
        @Override
        public void onStartFailure(int errorCode) {
            // Some devices don't support advertising — scan still works
        }
    };

    // ── BLE Scanning ──────────────────────────────────
    private void startBLEScan() {
        if (bleScanner == null || scanning) return;
        scanning = true;

        ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(new ParcelUuid(UUID.fromString(MESH_SERVICE_UUID)))
            .build();

        // Also scan by name prefix
        ScanFilter nameFilter = new ScanFilter.Builder()
            .setDeviceName(MESH_NAME_PREFIX)
            .build();

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build();

        bleScanner.startScan(Arrays.asList(filter), settings, scanCallback);

        // Stop after 15 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopBLEScan();
            sendToWebView("scan-done", "{\"found\":" + connectedGatts.size() + "}");
        }, 15000);
    }

    private void stopBLEScan() {
        if (bleScanner != null && scanning) {
            bleScanner.stopScan(scanCallback);
            scanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            if (name == null) name = device.getName();
            if (name == null || !name.startsWith(MESH_NAME_PREFIX)) return;

            final String devName = name.replace(MESH_NAME_PREFIX, "");
            final String devAddr = device.getAddress();
            final int rssi = result.getRssi();

            if (!connectedGatts.containsKey(devAddr)) {
                // Notify WebView immediately
                try {
                    JSONObject peer = new JSONObject();
                    peer.put("id", devAddr.replace(":", ""));
                    peer.put("name", devName);
                    peer.put("rssi", rssi);
                    peer.put("online", false);
                    peer.put("transport", "ble");
                    sendToWebView("peer-found", peer.toString());
                } catch (Exception e) {}

                // Connect in background
                device.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            sendToWebView("bt-status", "{\"available\":true,\"scanError\":" + errorCode + "}");
        }
    };

    // ── GATT Client (connect to discovered device) ────
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String addr = gatt.getDevice().getAddress();
            String peerId = addr.replace(":", "");
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedGatts.put(addr, gatt);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedGatts.remove(addr);
                txChars.remove(addr);
                sendToWebView("peer-offline", "\"" + peerId + "\"");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            String addr = gatt.getDevice().getAddress();
            String peerId = addr.replace(":", "");

            BluetoothGattService service = gatt.getService(UUID.fromString(MESH_SERVICE_UUID));
            if (service == null) return;

            BluetoothGattCharacteristic txChar = service.getCharacteristic(UUID.fromString(MESH_CHAR_TX_UUID));
            BluetoothGattCharacteristic rxChar = service.getCharacteristic(UUID.fromString(MESH_CHAR_RX_UUID));

            if (txChar != null) txChars.put(addr, txChar);

            // Enable notifications on RX
            if (rxChar != null) {
                gatt.setCharacteristicNotification(rxChar, true);
                BluetoothGattDescriptor desc = rxChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(desc);
                }
            }

            // Send hello
            String peerName = peerNames.getOrDefault(addr, "Unknown");
            sendHello(addr);

            // Notify WebView
            try {
                JSONObject peer = new JSONObject();
                peer.put("id", peerId);
                peer.put("name", peerName);
                peer.put("rssi", -55);
                peer.put("online", true);
                peer.put("transport", "ble");
                sendToWebView("peer-online", peer.toString());
            } catch (Exception e) {}
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic char_) {
            String addr = gatt.getDevice().getAddress();
            String peerId = addr.replace(":", "");
            String data = new String(char_.getValue(), StandardCharsets.UTF_8);
            handleIncoming(data, peerId);
        }
    };

    // ── Send hello packet ──────────────────────────────
    private void sendHello(String deviceAddr) {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("id", myId);
            hello.put("name", myName);
            writeToPeer(deviceAddr, hello.toString());
        } catch (Exception e) {}
    }

    // ── Write to BLE peer ──────────────────────────────
    private void writeToPeer(String deviceAddr, String data) {
        BluetoothGattCharacteristic txChar = txChars.get(deviceAddr);
        BluetoothGatt gatt = connectedGatts.get(deviceAddr);
        if (txChar == null || gatt == null) return;

        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        // BLE MTU ~20 bytes default, 512 with negotiation
        // Chunk if needed
        int mtu = 500;
        for (int i = 0; i < bytes.length; i += mtu) {
            byte[] chunk = Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + mtu));
            txChar.setValue(chunk);
            gatt.writeCharacteristic(txChar);
        }
    }

    private void sendToPeer(String peerId, String data) {
        // Find device addr from peerId
        for (String addr : connectedGatts.keySet()) {
            if (addr.replace(":", "").equals(peerId)) {
                writeToPeer(addr, data);
                return;
            }
        }
    }

    private void broadcastBLE(String data) {
        for (String addr : connectedGatts.keySet()) {
            writeToPeer(addr, data);
        }
    }

    // ── Handle incoming BLE data ───────────────────────
    private void handleIncoming(String data, String fromId) {
        try {
            JSONObject pkt = new JSONObject(data);
            String type = pkt.optString("type");

            if ("hello".equals(type)) {
                String name = pkt.optString("name");
                peerNames.put(fromId, name);
                // Peer is now online
                JSONObject peer = new JSONObject();
                peer.put("id", fromId);
                peer.put("name", name);
                peer.put("rssi", -55);
                peer.put("online", true);
                peer.put("transport", "ble");
                sendToWebView("peer-online", peer.toString());
            } else if ("chat".equals(type)) {
                // Decrypt
                JSONObject payload = pkt.getJSONObject("payload");
                String text = decryptAES(payload);
                pkt.put("plaintext", text);
                sendToWebView("message-in", pkt.toString());
                // Show notification
                showNotification(pkt.optString("name"), text);
            } else if ("location".equals(type)) {
                sendToWebView("location-in", pkt.toString());
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Notifications ──────────────────────────────────
    private void showNotification(String from, String text) {
        runOnUiThread(() ->
            Toast.makeText(this, "💬 " + from + ": " + text, Toast.LENGTH_SHORT).show()
        );
    }

    // ── AES-256-GCM ───────────────────────────────────
    private void initCrypto() throws Exception {
        byte[] salt = "ml3s".getBytes(StandardCharsets.UTF_8);
        byte[] pass = "meshlink-ble-v3".getBytes(StandardCharsets.UTF_8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(new String(pass, StandardCharsets.UTF_8).toCharArray(), salt, 50000, 256);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        aesKey = new SecretKeySpec(keyBytes, "AES");
    }

    private JSONObject encryptAES(String text) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        JSONObject result = new JSONObject();
        result.put("c", Base64.encodeToString(ct, Base64.NO_WRAP));
        result.put("i", Base64.encodeToString(iv, Base64.NO_WRAP));
        return result;
    }

    private String decryptAES(JSONObject payload) throws Exception {
        if (payload.optInt("r", 0) == 1) {
            return new String(Base64.decode(payload.optString("c"), Base64.DEFAULT), StandardCharsets.UTF_8);
        }
        byte[] ct = Base64.decode(payload.optString("c"), Base64.DEFAULT);
        byte[] iv = Base64.decode(payload.optString("i"), Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBLEScan();
        if (bleAdvertiser != null) bleAdvertiser.stopAdvertising(advCallback);
        for (BluetoothGatt gatt : connectedGatts.values()) {
            gatt.close();
        }
    }
}
