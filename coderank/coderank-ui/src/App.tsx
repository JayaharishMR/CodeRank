import { Routes, Route } from 'react-router-dom';
import { Box } from '@mui/material';
import Navbar from './components/layout/Navbar';
import Footer from './components/layout/Footer';
import ProtectedRoute from './components/layout/ProtectedRoute';
import Landing from './pages/Landing';
import Playground from './pages/Playground';
import Problems from './pages/Problems';
import ProblemView from './pages/ProblemView';
import Submissions from './pages/Submissions';
import SubmissionDetail from './pages/SubmissionDetail';

function App() {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <Navbar />
      <Box component="main" sx={{ flex: 1 }}>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/playground" element={<Playground />} />
          <Route path="/problems" element={<Problems />} />
          <Route path="/problems/:id" element={<ProblemView />} />
          <Route path="/submissions" element={<ProtectedRoute><Submissions /></ProtectedRoute>} />
          <Route path="/submissions/:id" element={<ProtectedRoute><SubmissionDetail /></ProtectedRoute>} />
        </Routes>
      </Box>
      <Footer />
    </Box>
  );
}

export default App;
