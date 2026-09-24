import type { ChatMessage, LlmProvider, LlmRequest, LlmResult } from './types.js';
import { ProviderError } from './types.js';

const BASE = process.env.ANTHROPIC_BASE_URL ?? 'https://api.anthropic.com';

type Block =
  | { type: 'text'; text: string }
  | { type: 'tool_use'; id: string; name: string; input: unknown }
  | { type: 'tool_result'; tool_use_id: string; content: string };

export function toAnthropicMessages(messages: ChatMessage[]) {
  const out: { role: 'user' | 'assistant'; content: Block[] }[] = [];
  const push = (role: 'user' | 'assistant', block: Block) => {
    const last = out[out.length - 1];
    if (last && last.role === role) last.content.push(block);
    else out.push({ role, content: [block] });
  };
  for (const m of messages) {
    if (m.role === 'user') push('user', { type: 'text', text: m.content });
    else if (m.role === 'assistant') {
      if (m.content) push('assistant', { type: 'text', text: m.content });
      for (const tc of m.toolCalls ?? []) push('assistant', { type: 'tool_use', id: tc.id, name: tc.name, input: tc.args });
      if (!m.content && !m.toolCalls?.length) push('assistant', { type: 'text', text: '…' });
    } else push('user', { type: 'tool_result', tool_use_id: m.toolCallId, content: m.content });
  }
  return out;
}

async function call(body: Record<string, unknown>, apiKey: string) {
  const res = await fetch(`${BASE}/v1/messages`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90_000),
  });
  const text = await res.text();
  if (!res.ok) throw new ProviderError(res.status, `Anthropic ${res.status}: ${text.slice(0, 300)}`, text);
  return JSON.parse(text);
}

export const anthropic: LlmProvider = {
  id: 'anthropic',
  async complete(req: LlmRequest, apiKey: string): Promise<LlmResult> {
    const body: Record<string, unknown> = {
      model: req.model,
      max_tokens: req.maxTokens ?? 1024,
      system: req.system,
      messages: toAnthropicMessages(req.messages),
    };
    if (req.tools.length) body.tools = req.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters }));
    if (req.temperature !== undefined) body.temperature = req.temperature;
    let data: any;
    try {
      data = await call(body, apiKey);
    } catch (e) {
      // Some models reject sampling params — retry once without them.
      if (e instanceof ProviderError && e.status === 400 && /temperature/i.test(e.body ?? '')) {
        delete body.temperature;
        data = await call(body, apiKey);
      } else throw e;
    }
    const blocks: any[] = data.content ?? [];
    return {
      text: blocks.filter((b) => b.type === 'text').map((b) => b.text).join('').trim(),
      toolCalls: blocks.filter((b) => b.type === 'tool_use').map((b) => ({ id: b.id, name: b.name, args: b.input ?? {} })),
      inputTokens: data.usage?.input_tokens ?? 0,
      outputTokens: data.usage?.output_tokens ?? 0,
      stopReason: data.stop_reason ?? '',
    };
  },
  async listModels(apiKey: string) {
    const res = await fetch(`${BASE}/v1/models?limit=100`, {
      headers: { 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) throw new ProviderError(res.status, `Anthropic models ${res.status}`);
    const data: any = await res.json();
    return (data.data ?? []).map((m: any) => m.id as string);
  },
};
