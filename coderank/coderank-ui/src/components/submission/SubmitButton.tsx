import { Button, CircularProgress } from '@mui/material';
import { PlayArrow } from '@mui/icons-material';

interface SubmitButtonProps {
  onClick: () => void;
  loading: boolean;
}

export default function SubmitButton({ onClick, loading }: SubmitButtonProps) {
  return (
    <Button
      variant="contained"
      color="primary"
      onClick={onClick}
      disabled={loading}
      startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <PlayArrow />}
      sx={{ minWidth: 120 }}
    >
      {loading ? 'Running...' : 'Run Code'}
    </Button>
  );
}
