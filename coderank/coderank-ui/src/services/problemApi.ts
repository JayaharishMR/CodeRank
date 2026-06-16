import api from './api';
import { Problem } from '../utils/types';

export async function getProblems(): Promise<Problem[]> {
  const { data } = await api.get<Problem[]>('/problems');
  return data;
}

export async function getProblem(idOrSlug: string): Promise<Problem> {
  const { data } = await api.get<Problem>(`/problems/${idOrSlug}`);
  return data;
}
