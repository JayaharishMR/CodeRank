import { useState, useEffect } from 'react';
import { Container, Typography, Grid, CircularProgress, Alert } from '@mui/material';
import ProblemCard from '../components/problem/ProblemCard';
import { getProblems } from '../services/problemApi';
import { Problem } from '../utils/types';

export default function Problems() {
  const [problems, setProblems] = useState<Problem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getProblems()
      .then(setProblems)
      .catch(() => setError('Failed to load problems'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <Container sx={{ py: 4, textAlign: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (error) {
    return (
      <Container sx={{ py: 4 }}>
        <Alert severity="error">{error}</Alert>
      </Container>
    );
  }

  return (
    <Container maxWidth="md" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 3, fontWeight: 600 }}>Problems</Typography>
      <Grid container spacing={2}>
        {problems.map((problem) => (
          <Grid size={{ xs: 12, sm: 6 }} key={problem.id}>
            <ProblemCard problem={problem} />
          </Grid>
        ))}
      </Grid>
    </Container>
  );
}
