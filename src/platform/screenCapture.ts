import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export type ScreenFrameEvent = {
  dataUrl: string;
  width: number;
  height: number;
};

type ScreenCapturePlugin = {
  start(): Promise<{ started: boolean }>;
  stop(): Promise<void>;
  addListener(
    eventName: 'screenFrame',
    listener: (event: ScreenFrameEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'screenStopped',
    listener: () => void,
  ): Promise<PluginListenerHandle>;
};

export const ScreenCapture = registerPlugin<ScreenCapturePlugin>('ScreenCapture');
