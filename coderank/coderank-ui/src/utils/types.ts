export type Verdict = 'ACCEPTED' | 'WRONG_ANSWER' | 'TIME_LIMIT_EXCEEDED'
  | 'MEMORY_LIMIT_EXCEEDED' | 'RUNTIME_ERROR' | 'COMPILATION_ERROR' | 'PENDING';

export type SubmissionStatus = 'QUEUED' | 'COMPILING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export type Language = 'JAVA';

export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD';

export type TestCaseVisibility = 'SHOW_FIRST_FAILING' | 'SHOW_SAMPLE_ONLY' | 'SHOW_NONE';

export interface TestCaseDto {
  input: string;
  expectedOutput: string;
}

export interface TestCaseResultDto {
  index: number;
  verdict: Verdict;
  actualOutput: string | null;
  expectedOutput: string | null;
  input: string | null;
  executionTimeMs: number;
  isSample: boolean;
}

export interface SubmissionRequest {
  language: Language;
  sourceCode: string;
  stdin?: string;
  problemId?: string;
}

export interface SubmissionResponse {
  id: string;
  status: SubmissionStatus;
  verdict: Verdict | null;
  sourceCode: string | null;
  stdout: string | null;
  stderr: string | null;
  executionTimeMs: number | null;
  memoryUsedKb: number | null;
  errorMessage: string | null;
  wsChannel: string;
  createdAt: string;
  problemId: string | null;
  passedTestCases: number | null;
  totalTestCases: number | null;
  testCaseResults: TestCaseResultDto[] | null;
}

export interface Problem {
  id: string;
  title: string;
  slug: string;
  difficulty: Difficulty;
  description: string;
  constraints: string[];
  starterCode: string;
  timeLimitMs: number;
  memoryLimitKb: number;
  sampleTestCases: TestCaseDto[];
}

export interface AiFeedbackMessage {
  submissionId: string;
  type: 'AI_FEEDBACK' | 'AI_FEEDBACK_ERROR';
  feedback: string;
  model: string | null;
  timestamp: string;
}
