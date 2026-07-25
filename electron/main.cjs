const { app, BrowserWindow, desktopCapturer, ipcMain } = require('electron');
const { autoUpdater } = require('electron-updater');
const path = require('path');

const isDev = !app.isPackaged;
const devServerUrl = 'http://localhost:5173';
let updateState = {
  status: 'idle',
  currentVersion: app.getVersion(),
  latestVersion: null,
  percent: 0,
  message: '업데이트를 확인할 수 있습니다.',
  releaseNotes: '',
};

function normalizeReleaseNotes(releaseNotes) {
  if (typeof releaseNotes === 'string') return releaseNotes;
  if (Array.isArray(releaseNotes)) {
    return releaseNotes.map((note) => note.note || '').filter(Boolean).join('\n\n');
  }
  return '';
}

function publishUpdateState(patch) {
  updateState = { ...updateState, ...patch };
  BrowserWindow.getAllWindows().forEach((window) => {
    window.webContents.send('update:state', updateState);
  });
}

function configureUpdater() {
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('checking-for-update', () => publishUpdateState({ status: 'checking', message: '최신 버전을 확인하고 있습니다.' }));
  autoUpdater.on('update-available', (info) => publishUpdateState({ status: 'available', latestVersion: info.version, message: `HAN2 DESKTOP ${info.version} 업데이트를 사용할 수 있습니다.`, releaseNotes: normalizeReleaseNotes(info.releaseNotes) }));
  autoUpdater.on('update-not-available', (info) => publishUpdateState({ status: 'latest', latestVersion: info.version || app.getVersion(), percent: 0, message: '현재 최신 버전을 사용하고 있습니다.' }));
  autoUpdater.on('download-progress', (progress) => publishUpdateState({ status: 'downloading', percent: Math.round(progress.percent), message: `업데이트를 다운로드하고 있습니다. ${Math.round(progress.percent)}%` }));
  autoUpdater.on('update-downloaded', (info) => publishUpdateState({ status: 'downloaded', latestVersion: info.version, percent: 100, message: '다운로드가 완료되었습니다. 앱을 다시 시작해 설치합니다.', releaseNotes: normalizeReleaseNotes(info.releaseNotes) || updateState.releaseNotes }));
  autoUpdater.on('error', (error) => publishUpdateState({ status: 'error', message: `업데이트를 확인하지 못했습니다: ${error.message}` }));
}

ipcMain.handle('update:get-state', () => updateState);
ipcMain.handle('update:check', async () => {
  if (isDev) {
    publishUpdateState({ status: 'latest', latestVersion: app.getVersion(), message: '개발 모드입니다. 설치된 앱에서 업데이트를 확인할 수 있습니다.' });
    return updateState;
  }
  await autoUpdater.checkForUpdates();
  return updateState;
});
ipcMain.handle('update:install', async () => {
  if (isDev) return updateState;
  if (updateState.status === 'downloaded') {
    autoUpdater.quitAndInstall(false, true);
    return updateState;
  }
  if (updateState.status !== 'available') return updateState;
  await autoUpdater.downloadUpdate();
  setTimeout(() => autoUpdater.quitAndInstall(false, true), 700);
  return updateState;
});

ipcMain.handle('screen:get-sources', async () => {
  const sources = await desktopCapturer.getSources({
    types: ['screen', 'window'],
    thumbnailSize: { width: 320, height: 180 },
    fetchWindowIcons: true,
  });

  return sources.map((source) => ({
    id: source.id,
    name: source.name,
    thumbnail: source.thumbnail.toDataURL(),
  }));
});

function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 960,
    minWidth: 1180,
    minHeight: 760,
    backgroundColor: '#0b1020',
    title: 'HAN2 DESKTOP',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  if (isDev) {
    win.loadURL(devServerUrl);
    if (process.env.HAN2_OPEN_DEVTOOLS === '1') {
      win.webContents.openDevTools({ mode: 'detach' });
    }
    return;
  }

  win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
}

app.whenReady().then(() => {
  if (process.platform === 'win32') {
    app.setAppUserModelId('com.fronte.desktop');
  }

  configureUpdater();
  createWindow();
  if (!isDev) {
    setTimeout(() => autoUpdater.checkForUpdates().catch(() => {}), 2500);
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
