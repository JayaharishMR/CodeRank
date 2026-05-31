import { Box, Typography, Paper, Chip } from '@mui/material';
import { Problem } from '../../utils/types';
import { DIFFICULTY_COLORS, DIFFICULTY_LABELS } from '../../utils/constants';

interface ProblemDetailProps {
  problem: Problem;
}

export default function ProblemDetail({ problem }: ProblemDetailProps) {
  return (
    <Box sx={{ p: 2, overflow: 'auto', height: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
        <Typography variant="h5" sx={{ fontWeight: 700 }}>
          {problem.title}
        </Typography>
        <Chip
          label={DIFFICULTY_LABELS[problem.difficulty] || problem.difficulty}
          size="small"
          sx={{ bgcolor: DIFFICULTY_COLORS[problem.difficulty] || '#9e9e9e', color: '#fff', fontWeight: 600 }}
        />
      </Box>

      <Typography variant="body1" sx={{ mb: 3, whiteSpace: 'pre-wrap' }}>
        {problem.description}
      </Typography>

      {problem.sampleTestCases.map((tc, idx) => (
        <Paper key={idx} variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Example {idx + 1}</Typography>
          <Box sx={{ fontFamily: 'monospace', fontSize: 13 }}>
            <Typography variant="body2"><strong>Input:</strong></Typography>
            <Box sx={{ whiteSpace: 'pre-wrap', ml: 1, mb: 1 }}>{tc.input}</Box>
            <Typography variant="body2"><strong>Output:</strong></Typography>
            <Box sx={{ whiteSpace: 'pre-wrap', ml: 1 }}>{tc.expectedOutput}</Box>
          </Box>
        </Paper>
      ))}

      <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>Constraints</Typography>
      <Box component="ul" sx={{ pl: 2 }}>
        {problem.constraints.map((c, idx) => (
          <Typography component="li" variant="body2" key={idx} sx={{ fontFamily: 'monospace', fontSize: 13 }}>
            {c}
          </Typography>
        ))}
      </Box>

      <Box sx={{ display: 'flex', gap: 3, mt: 2 }}>
        <Typography variant="body2" color="text.secondary">
          Time Limit: {problem.timeLimitMs}ms
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Memory Limit: {Math.round(problem.memoryLimitKb / 1024)}MB
        </Typography>
      </Box>
    </Box>
  );
}
