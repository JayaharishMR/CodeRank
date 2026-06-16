import { useState, useEffect, useCallback, useRef } from 'react';
import { Box, Container, CircularProgress, Alert } from '@mui/material';
import { useParams } from 'react-router-dom';
import CodeEditor from '../components/editor/CodeEditor';
import LanguageSelector from '../components/editor/LanguageSelector';
import SubmitButton from '../components/submission/SubmitButton';
import ResultPanel from '../components/submission/ResultPanel';
import ProblemDetail from '../components/problem/ProblemDetail';
import AiFeedbackPanel from '../components/submission/AiFeedbackPanel';
import { useSubmissionStore } from '../stores/submissionStore';
import { submitCode } from '../services/submissionApi';
import { subscribeToSubmission } from '../services/websocket';
import { getProblem } from '../services/problemApi';
import { Language, SubmissionResponse, Problem, AiFeedbackMessage } from '../utils/types';

export default function ProblemView() {
  const { id } = useParams<{ id: string }>();
  const [problem, setProblem] = useState<Problem | null>(null);
  const [problemLoading, setProblemLoading] = useState(true);
  const [problemError, setProblemError] = useState<string | null>(null);

  const [code, setCode] = useState('');
  const [language, setLanguage] = useState<Language>('JAVA');
  const [aiFeedback, setAiFeedback] = useState<string | null>(null);

  const { currentSubmission, status, loading, setSubmission, setLoading, reset } = useSubmissionStore();
  const unsubscribeRef = useRef<(() => void) | null>(null);

  // Fetch problem from API — reset all state when navigating to a different problem
  useEffect(() => {
    if (!id) return;
    reset();
    setAiFeedback(null);
    if (unsubscribeRef.current) {
      unsubscribeRef.current();
      unsubscribeRef.current = null;
    }
    setProblemLoading(true);
    setProblemError(null);
    getProblem(id)
      .then((p) => {
        setProblem(p);
        setCode(p.starterCode || '');
      })
      .catch(() => setProblemError('Failed to load problem'))
      .finally(() => setProblemLoading(false));
  }, [id]);

  const handleSubmit = useCallback(async () => {
    if (!problem) return;
    if (unsubscribeRef.current) {
      unsubscribeRef.current();
      unsubscribeRef.current = null;
    }
    reset();
    setAiFeedback(null);
    setLoading(true);

    try {
      const response = await submitCode({
        language,
        sourceCode: code,
        problemId: problem.id,
      });

      setSubmission(response);

      unsubscribeRef.current = subscribeToSubmission(
        response.id,
        (update: SubmissionResponse) => {
          setSubmission(update);
          if (update.status === 'COMPLETED' || update.status === 'FAILED') {
            setLoading(false);
            // Don't unsubscribe yet — keep listening for AI feedback
          }
        },
        (feedback: AiFeedbackMessage) => {
          if (feedback.type === 'AI_FEEDBACK') {
            setAiFeedback(feedback.feedback);
          }
        }
      );
    } catch (err) {
      setLoading(false);
      console.error('Submission failed:', err);
    }
  }, [code, language, problem, reset, setLoading, setSubmission]);

  // Cleanup WebSocket on unmount
  useEffect(() => {
    return () => {
      if (unsubscribeRef.current) {
        unsubscribeRef.current();
      }
    };
  }, []);

  if (problemLoading) {
    return (
      <Container sx={{ py: 4, textAlign: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (problemError || !problem) {
    return (
      <Container sx={{ py: 4 }}>
        <Alert severity="error">{problemError || 'Problem not found'}</Alert>
      </Container>
    );
  }

  return (
    <Box sx={{ display: 'flex', height: 'calc(100vh - 128px)', overflow: 'hidden' }}>
      <Box sx={{ width: '40%', overflow: 'auto', borderRight: 1, borderColor: 'divider' }}>
        <ProblemDetail problem={problem} />
      </Box>
      <Box sx={{ width: '60%', display: 'flex', flexDirection: 'column', p: 2, overflow: 'auto' }}>
        <CodeEditor value={code} onChange={setCode} language={language.toLowerCase()} />
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 2 }}>
          <LanguageSelector value={language} onChange={setLanguage} />
          <Box sx={{ flexGrow: 1 }} />
          <SubmitButton onClick={handleSubmit} loading={loading} />
        </Box>
        <ResultPanel submission={currentSubmission} status={status} />
        {currentSubmission && !loading && (
          <AiFeedbackPanel
            submissionId={currentSubmission.id}
            feedback={aiFeedback}
            onFeedbackReceived={setAiFeedback}
          />
        )}
      </Box>
    </Box>
  );
}
