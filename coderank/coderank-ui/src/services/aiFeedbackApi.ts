import api from './api';
import { AiFeedbackMessage } from '../utils/types';

export async function requestAiFeedback(submissionId: string): Promise<void> {
  await api.post(`/submissions/${submissionId}/feedback`);
}

export async function getAiFeedback(submissionId: string): Promise<AiFeedbackMessage | null> {
  try {
    const { data } = await api.get<AiFeedbackMessage>(`/submissions/${submissionId}/feedback`);
    return data;
  } catch {
    return null;
  }
}
