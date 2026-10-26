'use strict';
// Minimal, typed bridge exposed to the page as window.desktop (see admin-dashboard/src/lib/desktop.ts).
const { contextBridge, ipcRenderer } = require('electron');

const info = ipcRenderer.sendSync('info');
contextBridge.exposeInMainWorld('desktop', {
  apiBase: info.apiBase,
  deviceName: info.deviceName,
  version: info.version,
  store: {
    get: (k) => ipcRenderer.sendSync('store:get', String(k)),
    set: (k, v) => { ipcRenderer.sendSync('store:set', String(k), String(v)); },
    remove: (k) => { ipcRenderer.sendSync('store:remove', String(k)); },
  },
  request: (url, init) => ipcRenderer.invoke('api:request', String(url), init),
  saveFile: (name, content) => ipcRenderer.invoke('file:save', String(name), String(content)),
});
