import { useState, useEffect } from 'react';
import { Container, Typography, Box, CircularProgress, Alert } from '@mui/material';
import SubmissionCard from '../components/submission/SubmissionCard';
import { SubmissionResponse } from '../utils/types';
import api from '../services/api';

export default function Submissions() {
  const [submissions, setSubmissions] = useState<SubmissionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<SubmissionResponse[]>('/submissions')
      .then((res) => setSubmissions(res.data))
      .catch((err) => {
        if (err.response?.status === 404) {
          setSubmissions([]);
        } else {
          setError('Failed to load submissions');
        }
      })
      .finally(() => setLoading(false));
  }, []);

  return (
    <Container maxWidth="md" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 3, fontWeight: 600 }}>
        My Submissions
      </Typography>

      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      {!loading && !error && submissions.length === 0 && (
        <Typography color="text.secondary">No submissions yet. Try the Playground!</Typography>
      )}

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
        {submissions.map((sub) => (
          <SubmissionCard key={sub.id} submission={sub} />
        ))}
      </Box>
    </Container>
  );
}
