import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { getTheme } from './theme';
import { useThemeStore } from './stores/themeStore';
import { useAuthStore } from './stores/authStore';
import keycloak from './services/keycloak';
import './styles/global.css';

function Root() {
  const darkMode = useThemeStore((s) => s.darkMode);
  const setAuth = useAuthStore((s) => s.setAuth);
  const [keycloakReady, setKeycloakReady] = useState(false);

  useEffect(() => {
    keycloak
      .init({ onLoad: 'check-sso', silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html' })
      .then((authenticated) => {
        if (authenticated) {
          setAuth(
            true,
            keycloak.tokenParsed?.preferred_username,
            keycloak.tokenParsed?.email,
          );
        }
        setKeycloakReady(true);
      })
      .catch(() => {
        // Keycloak unavailable — continue as guest
        setKeycloakReady(true);
      });

    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => {
        useAuthStore.getState().logout();
      });
    };
  }, [setAuth]);

  if (!keycloakReady) return null;

  return (
    <ThemeProvider theme={getTheme(darkMode)}>
      <CssBaseline />
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ThemeProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
