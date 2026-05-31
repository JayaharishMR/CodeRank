import { useState } from 'react';
import { Paper, Typography, Button, Box, CircularProgress } from '@mui/material';
import { AutoAwesome as AiIcon } from '@mui/icons-material';
import ReactMarkdown from 'react-markdown';
import { requestAiFeedback, getAiFeedback } from '../../services/aiFeedbackApi';

interface AiFeedbackPanelProps {
  submissionId: string;
  feedback: string | null;
  onFeedbackReceived: (feedback: string) => void;
}

export default function AiFeedbackPanel({ submissionId, feedback, onFeedbackReceived }: AiFeedbackPanelProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleRequest = async () => {
    setLoading(true);
    setError(null);
    try {
      await requestAiFeedback(submissionId);
      // Feedback arrives via WebSocket. Poll as fallback in case WS missed it.
      const poll = async (attempts: number) => {
        if (feedback) { setLoading(false); return; }
        const stored = await getAiFeedback(submissionId);
        if (stored?.feedback) {
          onFeedbackReceived(stored.feedback);
          setLoading(false);
        } else if (attempts > 0) {
          setTimeout(() => poll(attempts - 1), 3000);
        } else {
          setLoading(false);
        }
      };
      setTimeout(() => poll(5), 5000);
    } catch {
      setError('Failed to request AI feedback');
      setLoading(false);
    }
  };

  return (
    <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: feedback ? 2 : 0 }}>
        <AiIcon sx={{ color: 'primary.main' }} />
        <Typography variant="subtitle2">AI Feedback</Typography>
        {!feedback && !loading && (
          <Button
            variant="outlined"
            size="small"
            onClick={handleRequest}
            sx={{ ml: 'auto' }}
            startIcon={<AiIcon />}
          >
            Get AI Feedback
          </Button>
        )}
        {loading && <CircularProgress size={20} sx={{ ml: 'auto' }} />}
      </Box>
      {error && (
        <Typography variant="body2" color="error" sx={{ mt: 1 }}>{error}</Typography>
      )}
      {feedback && (
        <Box sx={{
          fontSize: 14,
          lineHeight: 1.6,
          '& h1, & h2, & h3': { mt: 2, mb: 1, fontSize: '1.1em', fontWeight: 600 },
          '& p': { my: 0.5 },
          '& ul, & ol': { pl: 3, my: 0.5 },
          '& code': {
            bgcolor: 'action.hover',
            px: 0.5,
            py: 0.25,
            borderRadius: 0.5,
            fontFamily: 'monospace',
            fontSize: '0.9em',
          },
          '& pre': {
            bgcolor: 'action.hover',
            p: 1.5,
            borderRadius: 1,
            overflow: 'auto',
            my: 1,
            '& code': { bgcolor: 'transparent', p: 0 },
          },
        }}>
          <ReactMarkdown>{feedback}</ReactMarkdown>
        </Box>
      )}
    </Paper>
  );
}
