import { AppBar, Toolbar, Typography, Button, IconButton, Box } from '@mui/material';
import { Brightness4, Brightness7, Code } from '@mui/icons-material';
import { useNavigate, useLocation } from 'react-router-dom';
import { useThemeStore } from '../../stores/themeStore';
import { useAuthStore } from '../../stores/authStore';
import keycloak from '../../services/keycloak';

export default function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const { darkMode, toggleDarkMode } = useThemeStore();
  const { authenticated, username } = useAuthStore();

  const navItems = [
    { label: 'Playground', path: '/playground' },
    { label: 'Problems', path: '/problems' },
    ...(authenticated ? [{ label: 'Submissions', path: '/submissions' }] : []),
  ];

  return (
    <AppBar position="sticky" color="default" elevation={1}>
      <Toolbar>
        <Code sx={{ mr: 1 }} />
        <Typography
          variant="h6"
          sx={{ cursor: 'pointer', fontWeight: 700, mr: 4 }}
          onClick={() => navigate('/')}
        >
          CodeRank
        </Typography>

        <Box sx={{ display: 'flex', gap: 1, flexGrow: 1 }}>
          {navItems.map((item) => (
            <Button
              key={item.path}
              color={location.pathname === item.path ? 'primary' : 'inherit'}
              onClick={() => navigate(item.path)}
            >
              {item.label}
            </Button>
          ))}
        </Box>

        <IconButton onClick={toggleDarkMode} color="inherit">
          {darkMode ? <Brightness7 /> : <Brightness4 />}
        </IconButton>

        {authenticated ? (
          <Button color="inherit" onClick={() => keycloak.logout()}>
            {username}
          </Button>
        ) : (
          <Button variant="outlined" size="small" onClick={() => keycloak.login()}>
            Login
          </Button>
        )}
      </Toolbar>
    </AppBar>
  );
}
