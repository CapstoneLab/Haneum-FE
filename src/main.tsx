import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { applyPlatformClass } from './platform/appPlatform';
import './styles.css';

applyPlatformClass();

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
