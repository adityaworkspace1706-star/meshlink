// ═══════════════════════════════════════════════════
//  MeshLink Electron — Real Bluetooth Discovery
//  PC Desktop App — Windows / Mac / Linux
// ═══════════════════════════════════════════════════
const { app, BrowserWindow, ipcMain, Notification, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const http = require('http');
const WebSocket = require('ws');

// ── Try load Bluetooth (noble for real BLE) ─────────
let noble = null;
try { noble = require('@abandonware/noble'); } catch(e) {
  console.log('noble not available, using mock BT');
}

const MESH_SERVICE_UUID  = 'fe2c';
const MESH_CHAR_TX_UUID  = 'fe2d';
const MESH_CHAR_RX_UUID  = 'fe2e';
const MESH_NAME_PREFIX   = 'ML-';
const WS_PORT            = 9731;

// ── State ────────────────────────────────────────────
let mainWin = null;
let tray = null;
let wsServer = null;
let wsClients = new Map(); // peerId → ws
let peers = new Map();     // peerId → {id, name, address, rssi, online, peripheral}
let myId = '';
let myName = '';
let scanning = false;
let scfQueue = new Map();  // peerId → [packets]

// ── Identity ─────────────────────────────────────────
function loadIdentity() {
  const configPath = path.join(app.getPath('userData'), 'identity.json');
  try {
    if (fs.existsSync(configPath)) {
      return JSON.parse(fs.readFileSync(configPath, 'utf8'));
    }
  } catch(e) {}
  return null;
}

function saveIdentity(id, name) {
  const configPath = path.join(app.getPath('userData'), 'identity.json');
  fs.writeFileSync(configPath, JSON.stringify({ id, name }), 'utf8');
}

function genId() {
  return crypto.randomBytes(4).toString('hex').toUpperCase();
}

// ── Encryption (AES-256-GCM) ──────────────────────────
const MESH_KEY = crypto.scryptSync('meshlink-ble-v3', 'ml3s', 32);

function encrypt(text) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', MESH_KEY, iv);
  const ct = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return {
    c: Buffer.concat([ct, tag]).toString('base64'),
    i: iv.toString('base64')
  };
}

function decrypt(payload) {
  try {
    const iv = Buffer.from(payload.i, 'base64');
    const data = Buffer.from(payload.c, 'base64');
    const tag = data.slice(data.length - 16);
    const ct = data.slice(0, data.length - 16);
    const decipher = crypto.createDecipheriv('aes-256-gcm', MESH_KEY, iv);
    decipher.setAuthTag(tag);
    return decipher.update(ct) + decipher.final('utf8');
  } catch(e) {
    return '[encrypted]';
  }
}

// ── Local WebSocket Server (LAN discovery) ───────────
// Every device runs a WS server on port 9731
// Devices on same LAN discover each other via IP scan
function startWSServer() {
  const server = http.createServer();
  wsServer = new WebSocket.Server({ server });

  wsServer.on('connection', (ws, req) => {
    const remoteIp = req.socket.remoteAddress;
    let peerId = null;

    ws.on('message', (data) => {
      try {
        const pkt = JSON.parse(data.toString());
        if (pkt.type === 'hello') {
          peerId = pkt.id;
          wsClients.set(peerId, ws);
          addOrUpdatePeer(pkt.id, pkt.name, -60, true, 'ws');
          // Send our identity back
          ws.send(JSON.stringify({ type: 'hello', id: myId, name: myName }));
          // Deliver SCF queue
          deliverSCF(peerId);
          // Notify renderer
          mainWin?.webContents.send('peer-found', getPeer(peerId));
        } else {
          handleIncomingPacket(pkt, peerId);
        }
      } catch(e) {}
    });

    ws.on('close', () => {
      if (peerId) {
        wsClients.delete(peerId);
        const p = peers.get(peerId);
        if (p) { p.online = false; peers.set(peerId, p); }
        mainWin?.webContents.send('peer-offline', peerId);
      }
    });

    // Send hello first
    ws.send(JSON.stringify({ type: 'hello', id: myId, name: myName }));
  });

  server.listen(WS_PORT, '0.0.0.0', () => {
    console.log(`MeshLink WS server listening on port ${WS_PORT}`);
  });

  server.on('error', (e) => {
    console.log('WS server error:', e.message);
  });
}

// ── LAN Scan (same WiFi auto-discovery) ──────────────
function scanLAN() {
  const interfaces = os.networkInterfaces();
  const ips = [];

  Object.values(interfaces).forEach(iface => {
    iface.forEach(addr => {
      if (addr.family === 'IPv4' && !addr.internal) {
        // Get subnet — scan .1 to .254
        const parts = addr.address.split('.');
        const subnet = parts.slice(0, 3).join('.');
        for (let i = 1; i <= 254; i++) {
          const ip = `${subnet}.${i}`;
          if (ip !== addr.address) ips.push(ip);
        }
      }
    });
  });

  console.log(`Scanning ${ips.length} LAN IPs for MeshLink...`);

  // Connect to each IP:9731
  let found = 0;
  ips.forEach(ip => {
    const ws = new WebSocket(`ws://${ip}:${WS_PORT}`, { timeout: 800 });
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'hello', id: myId, name: myName }));
    });
    ws.on('message', (data) => {
      try {
        const pkt = JSON.parse(data.toString());
        if (pkt.type === 'hello' && pkt.id !== myId) {
          found++;
          const peerId = pkt.id;
          wsClients.set(peerId, ws);
          addOrUpdatePeer(peerId, pkt.name, -55, true, 'lan');
          deliverSCF(peerId);
          mainWin?.webContents.send('peer-found', getPeer(peerId));

          ws.on('message', (d2) => {
            try { handleIncomingPacket(JSON.parse(d2.toString()), peerId); } catch(e) {}
          });
          ws.on('close', () => {
            wsClients.delete(peerId);
            const p = peers.get(peerId);
            if (p) { p.online = false; peers.set(peerId, p); }
            mainWin?.webContents.send('peer-offline', peerId);
          });
        }
      } catch(e) {}
    });
    ws.on('error', () => {}); // Expected for most IPs
  });
}

// ── Bluetooth (noble) ─────────────────────────────────
function startBluetooth() {
  if (!noble) {
    console.log('Bluetooth not available');
    mainWin?.webContents.send('bt-status', { available: false });
    return;
  }

  noble.on('stateChange', (state) => {
    console.log('BT state:', state);
    mainWin?.webContents.send('bt-status', { available: state === 'poweredOn', state });
    if (state === 'poweredOn' && scanning) {
      noble.startScanning([MESH_SERVICE_UUID], true);
    }
  });

  noble.on('discover', (peripheral) => {
    const name = peripheral.advertisement?.localName || '';
    if (!name.startsWith(MESH_NAME_PREFIX)) return;

    const peerId = peripheral.id;
    const peerName = name.replace(MESH_NAME_PREFIX, '');
    const rssi = peripheral.rssi || -70;

    console.log(`BT: Found ${name} (${rssi} dBm)`);
    addOrUpdatePeer(peerId, peerName, rssi, false, 'ble');
    mainWin?.webContents.send('peer-found', getPeer(peerId));

    // Connect to exchange WebSocket port info
    connectBLEPeer(peripheral, peerId, peerName);
  });
}

function connectBLEPeer(peripheral, peerId, peerName) {
  peripheral.connect((err) => {
    if (err) { console.log('BLE connect error:', err); return; }

    peripheral.discoverSomeServicesAndCharacteristics(
      [MESH_SERVICE_UUID],
      [MESH_CHAR_TX_UUID, MESH_CHAR_RX_UUID],
      (err, services, chars) => {
        if (err || !chars.length) return;

        const txChar = chars.find(c => c.uuid === MESH_CHAR_TX_UUID);
        const rxChar = chars.find(c => c.uuid === MESH_CHAR_RX_UUID);

        if (rxChar) {
          rxChar.subscribe((err) => {
            if (err) return;
            rxChar.on('data', (data) => {
              try {
                const msg = JSON.parse(data.toString());
                // Exchange WS port for full connection
                if (msg.type === 'ws-info') {
                  const wsUrl = `ws://${msg.ip}:${WS_PORT}`;
                  const ws = new WebSocket(wsUrl, { timeout: 3000 });
                  ws.on('open', () => {
                    ws.send(JSON.stringify({ type: 'hello', id: myId, name: myName }));
                    wsClients.set(peerId, ws);
                    addOrUpdatePeer(peerId, peerName, peripheral.rssi, true, 'ble+ws');
                    deliverSCF(peerId);
                    mainWin?.webContents.send('peer-online', getPeer(peerId));
                  });
                  ws.on('message', (d) => {
                    try { handleIncomingPacket(JSON.parse(d.toString()), peerId); } catch(e) {}
                  });
                }
              } catch(e) {}
            });
          });
        }

        // Send our WS info via BLE
        if (txChar) {
          const myIp = getLocalIP();
          const info = JSON.stringify({ type: 'ws-info', ip: myIp, port: WS_PORT, id: myId, name: myName });
          txChar.write(Buffer.from(info), true, (err) => {
            if (!err) {
              addOrUpdatePeer(peerId, peerName, peripheral.rssi, true, 'ble');
              mainWin?.webContents.send('peer-online', getPeer(peerId));
            }
          });
        }
      }
    );

    peripheral.on('disconnect', () => {
      const p = peers.get(peerId);
      if (p) { p.online = false; peers.set(peerId, p); }
      mainWin?.webContents.send('peer-offline', peerId);
    });
  });
}

function getLocalIP() {
  const interfaces = os.networkInterfaces();
  for (const iface of Object.values(interfaces)) {
    for (const addr of iface) {
      if (addr.family === 'IPv4' && !addr.internal) return addr.address;
    }
  }
  return '127.0.0.1';
}

// ── Message Handling ──────────────────────────────────
function handleIncomingPacket(pkt, fromId) {
  if (!pkt || !pkt.type) return;

  if (pkt.type === 'chat') {
    const text = decrypt(pkt.payload || { c: '', i: '' });
    const processedPkt = { ...pkt, plaintext: text };
    mainWin?.webContents.send('message-in', processedPkt);

    // Show notification if window not focused
    if (mainWin && !mainWin.isFocused()) {
      new Notification({
        title: `💬 ${pkt.name}`,
        body: text.slice(0, 80),
        icon: path.join(__dirname, 'icon.png')
      }).show();
    }

    // Relay to other peers (mesh relay)
    if (pkt.hops < (pkt.maxHops || 5)) {
      const relayed = { ...pkt, hops: (pkt.hops || 0) + 1 };
      wsClients.forEach((ws, id) => {
        if (id !== fromId && ws.readyState === WebSocket.OPEN) {
          try { ws.send(JSON.stringify(relayed)); } catch(e) {}
        }
      });
    }
  }

  if (pkt.type === 'location') {
    mainWin?.webContents.send('location-in', pkt);
  }
}

// ── Send ──────────────────────────────────────────────
function sendToePeer(peerId, pkt) {
  const ws = wsClients.get(peerId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    try { ws.send(JSON.stringify(pkt)); return true; } catch(e) {}
  }
  return false;
}

function broadcastToAll(pkt, exceptId) {
  wsClients.forEach((ws, id) => {
    if (id !== exceptId && ws.readyState === WebSocket.OPEN) {
      try { ws.send(JSON.stringify(pkt)); } catch(e) {}
    }
  });
}

// ── SCF (Store-Carry-Forward) ─────────────────────────
function queueSCF(peerId, pkt) {
  if (!scfQueue.has(peerId)) scfQueue.set(peerId, []);
  const q = scfQueue.get(peerId);
  q.push(pkt);
  if (q.length > 100) q.shift();
}

function deliverSCF(peerId) {
  const q = scfQueue.get(peerId) || [];
  q.forEach(pkt => sendToePeer(peerId, pkt));
  scfQueue.set(peerId, []);
}

// ── Peers ─────────────────────────────────────────────
function addOrUpdatePeer(id, name, rssi, online, transport) {
  const existing = peers.get(id) || {};
  peers.set(id, {
    id, name: name || existing.name || `Node-${id.slice(0,4)}`,
    rssi: rssi || existing.rssi || -60,
    online, transport,
    lastSeen: Date.now(),
    msgCount: existing.msgCount || 0,
    lastMsg: existing.lastMsg || ''
  });
}

function getPeer(id) {
  return peers.get(id);
}

// ── IPC from renderer ─────────────────────────────────
ipcMain.handle('get-identity', () => ({ id: myId, name: myName }));
ipcMain.handle('get-peers', () => Array.from(peers.values()));

ipcMain.handle('send-message', async (e, { peerId, text, channel }) => {
  const payload = encrypt(text);
  const pkt = {
    type: 'chat',
    id: myId, name: myName,
    ch: channel || peerId || 'broadcast',
    payload,
    t: Date.now(),
    mid: crypto.randomBytes(4).toString('hex'),
    hops: 0, maxHops: 5
  };
  if (channel === 'broadcast' || !peerId) {
    broadcastToAll(pkt);
  } else {
    if (!sendToePeer(peerId, pkt)) queueSCF(peerId, pkt);
  }
  return pkt;
});

ipcMain.handle('start-scan', async () => {
  scanning = true;
  mainWin?.webContents.send('scan-started');

  // LAN scan
  scanLAN();

  // BLE scan
  if (noble && noble.state === 'poweredOn') {
    noble.startScanning([MESH_SERVICE_UUID], true);
    setTimeout(() => { if (noble) noble.stopScanning(); }, 15000);
  }

  return { ok: true };
});

ipcMain.handle('stop-scan', () => {
  scanning = false;
  if (noble) { try { noble.stopScanning(); } catch(e) {} }
  return { ok: true };
});

ipcMain.handle('set-identity', async (e, { id, name }) => {
  myId = id; myName = name;
  saveIdentity(id, name);
  return { ok: true };
});

ipcMain.handle('share-location', async () => {
  // Location is handled in renderer via navigator.geolocation
  return { ok: true };
});

ipcMain.handle('broadcast-location', async (e, { lat, lng }) => {
  const pkt = { type: 'location', id: myId, name: myName, lat, lng, t: Date.now() };
  broadcastToAll(pkt);
  return { ok: true };
});

// ── Window ────────────────────────────────────────────
function createWindow() {
  mainWin = new BrowserWindow({
    width: 420,
    height: 820,
    minWidth: 360,
    minHeight: 600,
    frame: true,
    backgroundColor: '#070d1a',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    },
    icon: path.join(__dirname, 'icon.png'),
    title: 'MeshLink'
  });

  mainWin.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWin.on('close', (e) => {
    // Minimize to tray instead of closing
    e.preventDefault();
    mainWin.hide();
  });

  // Dev tools in development
  if (process.env.NODE_ENV === 'development') {
    mainWin.webContents.openDevTools();
  }
}

function createTray() {
  const iconPath = path.join(__dirname, 'icon.png');
  if (!fs.existsSync(iconPath)) return;

  tray = new Tray(nativeImage.createFromPath(iconPath).resize({ width: 16 }));
  tray.setToolTip('MeshLink — Mesh Network');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open MeshLink', click: () => { mainWin?.show(); } },
    { label: 'Start Scan', click: () => { ipcMain.emit('start-scan'); } },
    { type: 'separator' },
    { label: 'Quit', click: () => { app.exit(0); } }
  ]));
  tray.on('click', () => mainWin?.show());
}

// ── App lifecycle ─────────────────────────────────────
app.whenReady().then(async () => {
  // Load or create identity
  const saved = loadIdentity();
  if (saved) {
    myId = saved.id;
    myName = saved.name;
  } else {
    myId = genId();
    // myName will be set by user in setup screen
  }

  startWSServer();
  startBluetooth();
  createWindow();
  createTray();

  // Auto-scan on start
  setTimeout(scanLAN, 2000);
});

app.on('window-all-closed', (e) => {
  // Keep running in background
});

app.on('activate', () => {
  mainWin?.show();
});

app.on('before-quit', () => {
  if (noble) { try { noble.stopScanning(); } catch(e) {} }
  wsServer?.close();
});
