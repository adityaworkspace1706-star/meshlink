// preload.js — Secure IPC bridge
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('MeshBridge', {
  // Identity
  getIdentity: () => ipcRenderer.invoke('get-identity'),
  setIdentity: (data) => ipcRenderer.invoke('set-identity', data),

  // Peers
  getPeers: () => ipcRenderer.invoke('get-peers'),

  // Messaging
  sendMessage: (data) => ipcRenderer.invoke('send-message', data),

  // Scanning
  startScan: () => ipcRenderer.invoke('start-scan'),
  stopScan: () => ipcRenderer.invoke('stop-scan'),

  // Location
  broadcastLocation: (data) => ipcRenderer.invoke('broadcast-location', data),

  // Events from main → renderer
  on: (channel, cb) => {
    const allowed = [
      'peer-found', 'peer-online', 'peer-offline',
      'message-in', 'location-in', 'scan-started',
      'bt-status', 'scan-result'
    ];
    if (allowed.includes(channel)) {
      ipcRenderer.on(channel, (e, ...args) => cb(...args));
    }
  },
  off: (channel, cb) => {
    ipcRenderer.removeListener(channel, cb);
  }
});
