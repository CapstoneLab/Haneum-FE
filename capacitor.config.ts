import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.han2.app',
  appName: 'HAN2 APP',
  webDir: 'dist',
  bundledWebRuntime: false,
  android: {
    backgroundColor: '#080b13',
  },
};

export default config;
