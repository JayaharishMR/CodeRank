import api from './api';
import { SubmissionRequest, SubmissionResponse } from '../utils/types';

export async function submitCode(request: SubmissionRequest): Promise<SubmissionResponse> {
  const { data } = await api.post<SubmissionResponse>('/submissions', request);
  return data;
}

export async function getSubmission(id: string): Promise<SubmissionResponse> {
  const { data } = await api.get<SubmissionResponse>(`/submissions/${id}`);
  return data;
}
