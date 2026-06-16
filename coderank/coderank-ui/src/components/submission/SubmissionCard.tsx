import { Card, CardActionArea, CardContent, Chip, Typography, Box } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { SubmissionResponse } from '../../utils/types';
import { VERDICT_COLORS, VERDICT_LABELS } from '../../utils/constants';
import { formatExecutionTime, formatTimestamp } from '../../utils/formatters';

interface SubmissionCardProps {
  submission: SubmissionResponse;
}

export default function SubmissionCard({ submission }: SubmissionCardProps) {
  const navigate = useNavigate();

  return (
    <Card variant="outlined">
      <CardActionArea onClick={() => navigate(`/submissions/${submission.id}`)}>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
              {submission.verdict && (
                <Chip
                  label={VERDICT_LABELS[submission.verdict] || submission.verdict}
                  size="small"
                  sx={{
                    bgcolor: VERDICT_COLORS[submission.verdict] || '#9e9e9e',
                    color: '#fff',
                    fontWeight: 600,
                  }}
                />
              )}
              <Typography variant="body2" color="text.secondary">
                {formatExecutionTime(submission.executionTimeMs)}
              </Typography>
            </Box>
            <Typography variant="body2" color="text.secondary">
              {formatTimestamp(submission.createdAt)}
            </Typography>
          </Box>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}
