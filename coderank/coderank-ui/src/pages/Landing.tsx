import { Box, Button, Container, Grid, Paper, Typography } from '@mui/material';
import { Code, Security, Speed, AutoAwesome } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';

const features = [
  {
    icon: <Code sx={{ fontSize: 40 }} />,
    title: 'Real-time Execution',
    description: 'Write and run Java code instantly with live status updates via WebSocket.',
  },
  {
    icon: <Security sx={{ fontSize: 40 }} />,
    title: 'Secure Sandbox',
    description: '6-layer Docker isolation: no network, dropped capabilities, memory limits, PID limits.',
  },
  {
    icon: <Speed sx={{ fontSize: 40 }} />,
    title: 'Fast Feedback',
    description: 'Async execution via RabbitMQ with real-time verdict delivery in seconds.',
  },
  {
    icon: <AutoAwesome sx={{ fontSize: 40 }} />,
    title: 'AI Assistant',
    description: 'Coming in Phase 2: AI-powered code hints and explanations via OpenAI.',
  },
];

export default function Landing() {
  const navigate = useNavigate();

  return (
    <Box>
      {/* Hero Section */}
      <Box
        sx={{
          py: { xs: 8, md: 12 },
          textAlign: 'center',
          background: 'linear-gradient(135deg, #1976d2 0%, #0d47a1 100%)',
          color: '#fff',
        }}
      >
        <Container maxWidth="md">
          <Typography variant="h2" sx={{ fontWeight: 800, mb: 2 }}>
            CodeRank
          </Typography>
          <Typography variant="h5" sx={{ mb: 4, opacity: 0.9 }}>
            Online Code Execution & Judging Platform
          </Typography>
          <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center' }}>
            <Button
              variant="contained"
              size="large"
              sx={{ bgcolor: '#fff', color: '#1976d2', '&:hover': { bgcolor: '#f5f5f5' } }}
              onClick={() => navigate('/playground')}
            >
              Try Playground
            </Button>
            <Button
              variant="outlined"
              size="large"
              sx={{ borderColor: '#fff', color: '#fff', '&:hover': { borderColor: '#fff', bgcolor: 'rgba(255,255,255,0.1)' } }}
              onClick={() => navigate('/problems')}
            >
              View Problems
            </Button>
          </Box>
        </Container>
      </Box>

      {/* Features Section */}
      <Container maxWidth="lg" sx={{ py: 8 }}>
        <Typography variant="h4" sx={{ textAlign: 'center', mb: 6, fontWeight: 700 }}>
          Features
        </Typography>
        <Grid container spacing={4}>
          {features.map((feature, idx) => (
            <Grid size={{ xs: 12, sm: 6, md: 3 }} key={idx}>
              <Paper
                elevation={0}
                sx={{
                  p: 3,
                  textAlign: 'center',
                  height: '100%',
                  border: 1,
                  borderColor: 'divider',
                  borderRadius: 2,
                }}
              >
                <Box sx={{ color: 'primary.main', mb: 2 }}>{feature.icon}</Box>
                <Typography variant="h6" sx={{ mb: 1, fontWeight: 600 }}>
                  {feature.title}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {feature.description}
                </Typography>
              </Paper>
            </Grid>
          ))}
        </Grid>
      </Container>
    </Box>
  );
}
