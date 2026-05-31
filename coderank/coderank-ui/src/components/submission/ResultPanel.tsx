import { Box, Chip, Paper, Tab, Tabs, Typography, Stepper, Step, StepLabel } from '@mui/material';
import { useState } from 'react';
import { SubmissionResponse, SubmissionStatus } from '../../utils/types';
import { VERDICT_COLORS, VERDICT_LABELS } from '../../utils/constants';
import { formatExecutionTime } from '../../utils/formatters';

interface ResultPanelProps {
  submission: SubmissionResponse | null;
  status: SubmissionStatus | null;
}

const STATUS_STEPS: SubmissionStatus[] = ['QUEUED', 'COMPILING', 'RUNNING', 'COMPLETED'];

function getActiveStep(status: SubmissionStatus | null): number {
  if (!status) return -1;
  if (status === 'FAILED') return 4;
  const idx = STATUS_STEPS.indexOf(status);
  return idx >= 0 ? idx : -1;
}

export default function ResultPanel({ submission, status }: ResultPanelProps) {
  const [tab, setTab] = useState(0);

  if (!status && !submission) return null;

  const isRunning = status && !['COMPLETED', 'FAILED'].includes(status);
  const verdict = submission?.verdict;

  const hasTestCases = submission?.testCaseResults && submission.testCaseResults.length > 0;
  const hasError = !!submission?.errorMessage;

  // Determine tab count and mapping
  // Tabs: stdout(0), stderr(1), [Error(2)], [Test Cases(last)]
  const errorTabIndex = hasError ? 2 : -1;
  const testCasesTabIndex = hasTestCases ? (hasError ? 3 : 2) : -1;

  return (
    <Paper variant="outlined" sx={{ mt: 2, p: 2 }}>
      {/* Status Stepper — visible while executing */}
      {isRunning && (
        <Stepper activeStep={getActiveStep(status)} sx={{ mb: 2 }}>
          {STATUS_STEPS.map((label) => (
            <Step key={label}>
              <StepLabel>{label}</StepLabel>
            </Step>
          ))}
        </Stepper>
      )}

      {/* Verdict + execution time — visible after completion */}
      {verdict && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
          <Chip
            label={VERDICT_LABELS[verdict] || verdict}
            sx={{
              bgcolor: VERDICT_COLORS[verdict] || '#9e9e9e',
              color: '#fff',
              fontWeight: 600,
            }}
          />
          {submission?.executionTimeMs != null && (
            <Typography variant="body2" color="text.secondary">
              Time: {formatExecutionTime(submission.executionTimeMs)}
            </Typography>
          )}
        </Box>
      )}

      {/* Test case summary — visible for judge mode */}
      {submission?.passedTestCases != null && submission?.totalTestCases != null && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Passed {submission.passedTestCases}/{submission.totalTestCases} test cases
        </Typography>
      )}

      {/* Output tabs — visible after completion */}
      {submission && !isRunning && (
        <>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 1 }}>
            <Tab label="stdout" />
            <Tab label="stderr" />
            {hasError && <Tab label="Error" />}
            {hasTestCases && <Tab label="Test Cases" />}
          </Tabs>

          {/* stdout / stderr / error content */}
          {tab === 0 && (
            <Box
              sx={{
                bgcolor: 'background.default',
                p: 2,
                borderRadius: 1,
                fontFamily: 'monospace',
                fontSize: 13,
                whiteSpace: 'pre-wrap',
                maxHeight: 300,
                overflow: 'auto',
                minHeight: 60,
              }}
            >
              {submission.stdout || 'No output'}
            </Box>
          )}
          {tab === 1 && (
            <Box
              sx={{
                bgcolor: 'background.default',
                p: 2,
                borderRadius: 1,
                fontFamily: 'monospace',
                fontSize: 13,
                whiteSpace: 'pre-wrap',
                maxHeight: 300,
                overflow: 'auto',
                minHeight: 60,
              }}
            >
              {submission.stderr || 'No errors'}
            </Box>
          )}
          {tab === errorTabIndex && (
            <Box
              sx={{
                bgcolor: 'background.default',
                p: 2,
                borderRadius: 1,
                fontFamily: 'monospace',
                fontSize: 13,
                whiteSpace: 'pre-wrap',
                maxHeight: 300,
                overflow: 'auto',
                minHeight: 60,
              }}
            >
              {submission.errorMessage}
            </Box>
          )}

          {/* Test Cases tab content */}
          {tab === testCasesTabIndex && submission.testCaseResults && (
            <Box>
              {submission.testCaseResults.map((tc) => (
                <Paper key={tc.index} variant="outlined" sx={{ p: 1.5, mb: 1 }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: tc.actualOutput ? 1 : 0 }}>
                    <Chip
                      label={VERDICT_LABELS[tc.verdict] || tc.verdict}
                      size="small"
                      sx={{ bgcolor: VERDICT_COLORS[tc.verdict] || '#9e9e9e', color: '#fff', fontWeight: 600, fontSize: 11 }}
                    />
                    <Typography variant="body2" color="text.secondary">
                      Test {tc.index + 1} {tc.isSample ? '(sample)' : '(hidden)'}
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ ml: 'auto' }}>
                      {tc.executionTimeMs}ms
                    </Typography>
                  </Box>
                  {tc.expectedOutput != null && (
                    <Box sx={{ fontFamily: 'monospace', fontSize: 12, mt: 1 }}>
                      <Typography variant="caption" color="text.secondary">Expected:</Typography>
                      <Box sx={{ whiteSpace: 'pre-wrap', bgcolor: 'background.default', p: 1, borderRadius: 0.5, mb: 0.5 }}>
                        {tc.expectedOutput}
                      </Box>
                      <Typography variant="caption" color="text.secondary">Got:</Typography>
                      <Box sx={{ whiteSpace: 'pre-wrap', bgcolor: 'background.default', p: 1, borderRadius: 0.5, color: tc.verdict === 'ACCEPTED' ? 'success.main' : 'error.main' }}>
                        {tc.actualOutput || '(no output)'}
                      </Box>
                    </Box>
                  )}
                </Paper>
              ))}
            </Box>
          )}
        </>
      )}
    </Paper>
  );
}
