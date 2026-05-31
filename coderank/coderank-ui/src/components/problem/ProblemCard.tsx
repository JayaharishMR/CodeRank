import { Card, CardActionArea, CardContent, Chip, Typography, Box } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { Problem } from '../../utils/types';
import { DIFFICULTY_COLORS, DIFFICULTY_LABELS } from '../../utils/constants';

interface ProblemCardProps {
  problem: Problem;
}

export default function ProblemCard({ problem }: ProblemCardProps) {
  const navigate = useNavigate();

  return (
    <Card variant="outlined">
      <CardActionArea onClick={() => navigate(`/problems/${problem.slug}`)}>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
            <Typography variant="h6" sx={{ fontWeight: 600 }}>
              {problem.title}
            </Typography>
            <Chip
              label={DIFFICULTY_LABELS[problem.difficulty] || problem.difficulty}
              size="small"
              sx={{
                bgcolor: DIFFICULTY_COLORS[problem.difficulty] || '#9e9e9e',
                color: '#fff',
                fontWeight: 600,
              }}
            />
          </Box>
          <Typography variant="body2" color="text.secondary" sx={{
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
          }}>
            {problem.description}
          </Typography>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}
