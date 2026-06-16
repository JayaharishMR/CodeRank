export const VERDICT_COLORS: Record<string, string> = {
  ACCEPTED: '#4caf50',
  WRONG_ANSWER: '#f44336',
  TIME_LIMIT_EXCEEDED: '#ff9800',
  MEMORY_LIMIT_EXCEEDED: '#ff9800',
  RUNTIME_ERROR: '#f44336',
  COMPILATION_ERROR: '#ff5722',
  PENDING: '#9e9e9e',
};

export const VERDICT_LABELS: Record<string, string> = {
  ACCEPTED: 'Accepted',
  WRONG_ANSWER: 'Wrong Answer',
  TIME_LIMIT_EXCEEDED: 'Time Limit Exceeded',
  MEMORY_LIMIT_EXCEEDED: 'Memory Limit Exceeded',
  RUNTIME_ERROR: 'Runtime Error',
  COMPILATION_ERROR: 'Compilation Error',
  PENDING: 'Pending',
};

export const DEFAULT_JAVA_CODE = `public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, CodeRank!");
    }
}`;

export const DIFFICULTY_COLORS: Record<string, string> = {
  EASY: '#4caf50',
  MEDIUM: '#ff9800',
  HARD: '#f44336',
};

export const DIFFICULTY_LABELS: Record<string, string> = {
  EASY: 'Easy',
  MEDIUM: 'Medium',
  HARD: 'Hard',
};
