import { useState, useCallback, useRef } from 'react';
import { Box, Container, TextField, Collapse, IconButton, Tooltip, Typography } from '@mui/material';
import { Input as InputIcon } from '@mui/icons-material';
import CodeEditor from '../components/editor/CodeEditor';
import LanguageSelector from '../components/editor/LanguageSelector';
import SubmitButton from '../components/submission/SubmitButton';
import ResultPanel from '../components/submission/ResultPanel';
import { useSubmissionStore } from '../stores/submissionStore';
import { submitCode } from '../services/submissionApi';
import { subscribeToSubmission } from '../services/websocket';
import { DEFAULT_JAVA_CODE } from '../utils/constants';
import { Language, SubmissionResponse } from '../utils/types';

export default function Playground() {
  const [code, setCode] = useState(DEFAULT_JAVA_CODE);
  const [language, setLanguage] = useState<Language>('JAVA');
  const [stdin, setStdin] = useState('');
  const [showStdin, setShowStdin] = useState(false);

  const { currentSubmission, status, loading, setSubmission, setLoading, reset } = useSubmissionStore();
  const unsubscribeRef = useRef<(() => void) | null>(null);

  const handleSubmit = useCallback(async () => {
    // Cleanup previous subscription
    if (unsubscribeRef.current) {
      unsubscribeRef.current();
      unsubscribeRef.current = null;
    }
    reset();
    setLoading(true);

    try {
      const response = await submitCode({
        language,
        sourceCode: code,
        stdin: stdin || undefined,
      });

      setSubmission(response);

      // Subscribe to WebSocket for real-time updates
      unsubscribeRef.current = subscribeToSubmission(response.id, (update: SubmissionResponse) => {
        setSubmission(update);
        if (update.status === 'COMPLETED' || update.status === 'FAILED') {
          setLoading(false);
          if (unsubscribeRef.current) {
            unsubscribeRef.current();
            unsubscribeRef.current = null;
          }
        }
      });
    } catch (err) {
      setLoading(false);
      console.error('Submission failed:', err);
    }
  }, [code, language, stdin, reset, setLoading, setSubmission]);

  return (
    <Container maxWidth="lg" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 2, fontWeight: 600 }}>
        Playground
      </Typography>

      <CodeEditor value={code} onChange={setCode} language={language.toLowerCase()} />

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 2 }}>
        <LanguageSelector value={language} onChange={setLanguage} />

        <Tooltip title={showStdin ? 'Hide stdin' : 'Provide stdin input'}>
          <IconButton
            onClick={() => setShowStdin(!showStdin)}
            color={showStdin ? 'primary' : 'default'}
          >
            <InputIcon />
          </IconButton>
        </Tooltip>

        <Box sx={{ flexGrow: 1 }} />

        <SubmitButton onClick={handleSubmit} loading={loading} />
      </Box>

      <Collapse in={showStdin}>
        <TextField
          label="stdin"
          multiline
          rows={3}
          fullWidth
          value={stdin}
          onChange={(e) => setStdin(e.target.value)}
          placeholder="Enter input for your program..."
          sx={{ mt: 2 }}
          variant="outlined"
          size="small"
        />
      </Collapse>

      <ResultPanel submission={currentSubmission} status={status} />
    </Container>
  );
}
