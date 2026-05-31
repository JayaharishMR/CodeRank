import { Box, Typography, Link } from '@mui/material';

export default function Footer() {
  return (
    <Box component="footer" sx={{ py: 2, px: 3, mt: 'auto', textAlign: 'center', borderTop: 1, borderColor: 'divider' }}>
      <Typography variant="body2" color="text.secondary">
        CodeRank — Online Code Execution Platform |{' '}
        <Link href="https://github.com/JayaharishMR/CodeRank" target="_blank" rel="noopener" color="inherit">
          GitHub
        </Link>
      </Typography>
    </Box>
  );
}
