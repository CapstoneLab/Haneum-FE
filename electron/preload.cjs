const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('frontendHan2', {
  appName: 'HAN2 DESKTOP',
  version: '0.1.4',
  getScreenSources: () => ipcRenderer.invoke('screen:get-sources'),
  getUpdateState: () => ipcRenderer.invoke('update:get-state'),
  checkForUpdates: () => ipcRenderer.invoke('update:check'),
  installUpdate: () => ipcRenderer.invoke('update:install'),
  onUpdateState: (callback) => {
    const listener = (_event, state) => callback(state);
    ipcRenderer.on('update:state', listener);
    return () => ipcRenderer.removeListener('update:state', listener);
  },
});
