import { create } from 'zustand';
import { SubmissionResponse, SubmissionStatus } from '../utils/types';

interface SubmissionState {
  currentSubmission: SubmissionResponse | null;
  status: SubmissionStatus | null;
  loading: boolean;
  setSubmission: (submission: SubmissionResponse) => void;
  setStatus: (status: SubmissionStatus) => void;
  setLoading: (loading: boolean) => void;
  reset: () => void;
}

export const useSubmissionStore = create<SubmissionState>((set) => ({
  currentSubmission: null,
  status: null,
  loading: false,
  setSubmission: (submission) => set({ currentSubmission: submission, status: submission.status }),
  setStatus: (status) => set({ status }),
  setLoading: (loading) => set({ loading }),
  reset: () => set({ currentSubmission: null, status: null, loading: false }),
}));
