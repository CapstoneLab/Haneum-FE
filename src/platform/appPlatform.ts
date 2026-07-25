import { Capacitor } from '@capacitor/core';

export type AppPlatform = 'electron' | 'android' | 'ios' | 'web';

export const appPlatform: AppPlatform = window.frontendHan2
  ? 'electron'
  : Capacitor.getPlatform() === 'android'
    ? 'android'
    : Capacitor.getPlatform() === 'ios'
      ? 'ios'
      : 'web';

export const platformCapabilities = {
  desktopScreenShare: appPlatform === 'electron',
  desktopAutoUpdate: appPlatform === 'electron',
  nativeMobile: appPlatform === 'android' || appPlatform === 'ios',
};

export const platformDisplayName = platformCapabilities.nativeMobile
  ? 'HAN2 APP'
  : appPlatform === 'electron'
    ? 'HAN2 DESKTOP'
    : 'HAN2 WEB';

export function applyPlatformClass() {
  document.documentElement.dataset.platform = appPlatform;
  document.documentElement.classList.toggle('native-mobile', platformCapabilities.nativeMobile);
}
