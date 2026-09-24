export interface ToolCall {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

export type ChatMessage =
  | { role: 'user'; content: string }
  | { role: 'assistant'; content: string; toolCalls?: ToolCall[] }
  | { role: 'tool'; toolCallId: string; name: string; content: string };

export interface ToolSpec {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface LlmResult {
  text: string;
  toolCalls: ToolCall[];
  inputTokens: number;
  outputTokens: number;
  stopReason: string;
}

export interface LlmRequest {
  model: string;
  system: string;
  messages: ChatMessage[];
  tools: ToolSpec[];
  temperature?: number;
  maxTokens?: number;
}

export interface LlmProvider {
  id: 'anthropic' | 'openai';
  complete(req: LlmRequest, apiKey: string): Promise<LlmResult>;
  listModels(apiKey: string): Promise<string[]>;
}

export class ProviderError extends Error {
  constructor(public readonly status: number, message: string, public readonly body?: string) {
    super(message);
  }
}
