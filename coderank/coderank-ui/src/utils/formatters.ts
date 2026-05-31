export function formatExecutionTime(ms: number | null): string {
  if (ms === null) return '-';
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`;
}

export function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString();
}
