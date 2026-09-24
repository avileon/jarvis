import type { ChatMessage, LlmProvider, LlmRequest, LlmResult } from './types.js';
import { ProviderError } from './types.js';

export const OPENAI_BASE = process.env.OPENAI_BASE_URL ?? 'https://api.openai.com';

export function toOpenAiMessages(system: string, messages: ChatMessage[]) {
  const out: any[] = [{ role: 'system', content: system }];
  for (const m of messages) {
    if (m.role === 'user') out.push({ role: 'user', content: m.content });
    else if (m.role === 'assistant') {
      const msg: any = { role: 'assistant', content: m.content || null };
      if (m.toolCalls?.length)
        msg.tool_calls = m.toolCalls.map((tc) => ({ id: tc.id, type: 'function', function: { name: tc.name, arguments: JSON.stringify(tc.args) } }));
      out.push(msg);
    } else out.push({ role: 'tool', tool_call_id: m.toolCallId, content: m.content });
  }
  return out;
}

async function call(body: Record<string, unknown>, apiKey: string) {
  const res = await fetch(`${OPENAI_BASE}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90_000),
  });
  const text = await res.text();
  if (!res.ok) throw new ProviderError(res.status, `OpenAI ${res.status}: ${text.slice(0, 300)}`, text);
  return JSON.parse(text);
}

export const openai: LlmProvider = {
  id: 'openai',
  async complete(req: LlmRequest, apiKey: string): Promise<LlmResult> {
    const body: Record<string, unknown> = {
      model: req.model,
      messages: toOpenAiMessages(req.system, req.messages),
      max_completion_tokens: req.maxTokens ?? 1024,
    };
    if (req.tools.length)
      body.tools = req.tools.map((t) => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } }));
    if (req.temperature !== undefined) body.temperature = req.temperature;
    let data: any;
    try {
      data = await call(body, apiKey);
    } catch (e) {
      // Reasoning models only accept the default temperature.
      if (e instanceof ProviderError && e.status === 400 && /temperature/i.test(e.body ?? '')) {
        delete body.temperature;
        data = await call(body, apiKey);
      } else throw e;
    }
    const choice = data.choices?.[0];
    const msg = choice?.message ?? {};
    return {
      text: (msg.content ?? '').trim(),
      toolCalls: (msg.tool_calls ?? []).map((tc: any) => ({
        id: tc.id,
        name: tc.function.name,
        args: safeJson(tc.function.arguments),
      })),
      inputTokens: data.usage?.prompt_tokens ?? 0,
      outputTokens: data.usage?.completion_tokens ?? 0,
      stopReason: choice?.finish_reason ?? '',
    };
  },
  async listModels(apiKey: string) {
    const res = await fetch(`${OPENAI_BASE}/v1/models`, { headers: { authorization: `Bearer ${apiKey}` }, signal: AbortSignal.timeout(15_000) });
    if (!res.ok) throw new ProviderError(res.status, `OpenAI models ${res.status}`);
    const data: any = await res.json();
    return (data.data ?? [])
      .map((m: any) => m.id as string)
      .filter((id: string) => /^(gpt-|o\d|chatgpt)/.test(id) && !/(audio|realtime|transcribe|tts|image|embedding|search|instruct)/.test(id))
      .sort();
  },
};

function safeJson(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s || '{}');
  } catch {
    return {};
  }
}
