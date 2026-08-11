import { registerPlugin } from '@capacitor/core';

export type KeyboardStatus = { enabled: boolean; selected: boolean };
export type DetectionReport = {
  id: string;
  level: 'warning' | 'danger';
  title: string;
  reason: string;
  preview: string;
  detectedAt: number;
};

type KeyboardSetupPlugin = {
  getStatus(): Promise<KeyboardStatus>;
  openKeyboardSettings(): Promise<void>;
  showKeyboardPicker(): Promise<void>;
  setProtectionEnabled(options: { enabled: boolean }): Promise<{ enabled: boolean }>;
  getProtectionEnabled(): Promise<{ enabled: boolean }>;
  setKeyboardPreferences(options: {
    protection: boolean;
    haptics: boolean;
    sound: boolean;
    numberRow: boolean;
    theme: 'system' | 'light' | 'dark';
  }): Promise<void>;
  getDetectionReports(): Promise<{ reports: DetectionReport[] }>;
  clearDetectionReports(): Promise<void>;
};

export const KeyboardSetup = registerPlugin<KeyboardSetupPlugin>('KeyboardSetup');
