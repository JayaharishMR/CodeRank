import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Container, Typography, Box, CircularProgress, Alert, Chip, Paper } from '@mui/material';
import CodeEditor from '../components/editor/CodeEditor';
import AiFeedbackPanel from '../components/submission/AiFeedbackPanel';
import { getSubmission } from '../services/submissionApi';
import { getAiFeedback } from '../services/aiFeedbackApi';
import { SubmissionResponse } from '../utils/types';
import { VERDICT_COLORS, VERDICT_LABELS } from '../utils/constants';
import { formatExecutionTime, formatTimestamp } from '../utils/formatters';

export default function SubmissionDetail() {
  const { id } = useParams<{ id: string }>();
  const [submission, setSubmission] = useState<SubmissionResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [aiFeedback, setAiFeedback] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    getSubmission(id)
      .then(setSubmission)
      .catch(() => setError('Failed to load submission'))
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    if (submission?.id) {
      getAiFeedback(submission.id).then((f) => {
        if (f?.feedback) setAiFeedback(f.feedback);
      });
    }
  }, [submission]);

  if (loading) {
    return (
      <Container sx={{ py: 4, textAlign: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (error || !submission) {
    return (
      <Container sx={{ py: 4 }}>
        <Alert severity="error">{error || 'Submission not found'}</Alert>
      </Container>
    );
  }

  return (
    <Container maxWidth="lg" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 2, fontWeight: 600 }}>
        Submission Detail
      </Typography>

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3 }}>
        {submission.verdict && (
          <Chip
            label={VERDICT_LABELS[submission.verdict] || submission.verdict}
            sx={{
              bgcolor: VERDICT_COLORS[submission.verdict] || '#9e9e9e',
              color: '#fff',
              fontWeight: 600,
            }}
          />
        )}
        <Typography variant="body2" color="text.secondary">
          Time: {formatExecutionTime(submission.executionTimeMs)}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Submitted: {formatTimestamp(submission.createdAt)}
        </Typography>
      </Box>

      {submission.passedTestCases != null && submission.totalTestCases != null && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Passed {submission.passedTestCases}/{submission.totalTestCases} test cases
        </Typography>
      )}

      <Typography variant="subtitle2" sx={{ mb: 1 }}>Source Code</Typography>
      <CodeEditor value={submission.sourceCode || '// No source code available'} onChange={() => {}} readOnly />

      {submission.stdout && (
        <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>stdout</Typography>
          <Box sx={{ fontFamily: 'monospace', fontSize: 13, whiteSpace: 'pre-wrap' }}>
            {submission.stdout}
          </Box>
        </Paper>
      )}

      {submission.stderr && (
        <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>stderr</Typography>
          <Box sx={{ fontFamily: 'monospace', fontSize: 13, whiteSpace: 'pre-wrap', color: 'error.main' }}>
            {submission.stderr}
          </Box>
        </Paper>
      )}

      {submission.errorMessage && (
        <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Error</Typography>
          <Box sx={{ fontFamily: 'monospace', fontSize: 13, whiteSpace: 'pre-wrap', color: 'error.main' }}>
            {submission.errorMessage}
          </Box>
        </Paper>
      )}

      {submission && (
        <AiFeedbackPanel
          submissionId={submission.id}
          feedback={aiFeedback}
          onFeedbackReceived={setAiFeedback}
        />
      )}
    </Container>
  );
}
