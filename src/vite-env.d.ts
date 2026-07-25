/// <reference types="vite/client" />

type ScreenSource = {
  id: string;
  name: string;
  thumbnail: string;
};

type UpdateStatus = 'idle' | 'checking' | 'available' | 'downloading' | 'downloaded' | 'latest' | 'error';

type UpdateState = {
  status: UpdateStatus;
  currentVersion: string;
  latestVersion: string | null;
  percent: number;
  message: string;
};

interface Window {
  frontendHan2?: {
    appName: string;
    version: string;
    getScreenSources: () => Promise<ScreenSource[]>;
    getUpdateState: () => Promise<UpdateState>;
    checkForUpdates: () => Promise<UpdateState>;
    installUpdate: () => Promise<UpdateState>;
    onUpdateState: (callback: (state: UpdateState) => void) => () => void;
  };
}
