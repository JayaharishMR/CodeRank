import { Client } from '@stomp/stompjs';
import { SubmissionResponse, AiFeedbackMessage } from '../utils/types';

export function subscribeToSubmission(
  submissionId: string,
  onUpdate: (response: SubmissionResponse) => void,
  onAiFeedback?: (feedback: AiFeedbackMessage) => void,
): () => void {
  const client = new Client({
    brokerURL: import.meta.env.VITE_WS_URL,
    reconnectDelay: 5000,
    onConnect: () => {
      client.subscribe(`/topic/submissions/${submissionId}`, (message) => {
        try {
          const parsed = JSON.parse(message.body);
          // Check if this is an AI feedback message
          if (parsed.type === 'AI_FEEDBACK' || parsed.type === 'AI_FEEDBACK_ERROR') {
            onAiFeedback?.(parsed as AiFeedbackMessage);
          } else {
            onUpdate(parsed as SubmissionResponse);
          }
        } catch (e) {
          console.error('Failed to parse WebSocket message:', e);
        }
      });
    },
    onStompError: (frame) => {
      console.error('STOMP error:', frame.headers['message']);
    },
  });
  client.activate();
  return () => { client.deactivate(); };
}
