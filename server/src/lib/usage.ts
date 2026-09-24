import { q, q1 } from './db.js';
import { getSettings } from './settings.js';

export class LimitError extends Error {
  constructor(public readonly reason: 'daily' | 'monthly' | 'rate', message: string) {
    super(message);
  }
}

const recent: number[] = [];

/** Throws LimitError if spending caps or the per-minute request cap are exceeded. */
export async function checkLimits() {
  const limits = await getSettings('limits');
  const now = Date.now();
  while (recent.length && now - recent[0]! > 60_000) recent.shift();
  if (limits.requestsPerMinute > 0 && recent.length >= limits.requestsPerMinute) {
    throw new LimitError('rate', 'יותר מדי בקשות בדקה האחרונה. נסה שוב עוד רגע.');
  }
  const { day, month } = await spend();
  if (limits.dailyUsd > 0 && day >= limits.dailyUsd) throw new LimitError('daily', 'הגעת לתקרת העלות היומית שהוגדרה.');
  if (limits.monthlyUsd > 0 && month >= limits.monthlyUsd) throw new LimitError('monthly', 'הגעת לתקרת העלות החודשית שהוגדרה.');
  recent.push(now);
}

export async function spend() {
  const r = await q1<{ day: string; month: string }>(
    `SELECT
       COALESCE(SUM(cost_usd) FILTER (WHERE ts >= date_trunc('day', now() AT TIME ZONE 'Asia/Jerusalem') AT TIME ZONE 'Asia/Jerusalem'),0) AS day,
       COALESCE(SUM(cost_usd) FILTER (WHERE ts >= date_trunc('month', now() AT TIME ZONE 'Asia/Jerusalem') AT TIME ZONE 'Asia/Jerusalem'),0) AS month
     FROM usage WHERE ts > now() - interval '32 days'`,
  );
  return { day: Number(r?.day ?? 0), month: Number(r?.month ?? 0) };
}

export async function recordLlm(provider: string, model: string, inputTokens: number, outputTokens: number, meta: object = {}) {
  const pricing = await getSettings('pricing');
  const p = pricing.llm[model] ?? matchPrefix(pricing.llm, model) ?? pricing.llm.default ?? { in: 0, out: 0 };
  const cost = (inputTokens * p.in + outputTokens * p.out) / 1_000_000;
  await q('INSERT INTO usage(provider, model, kind, input_units, output_units, cost_usd, meta) VALUES ($1,$2,$3,$4,$5,$6,$7)', [
    provider, model, 'llm', inputTokens, outputTokens, cost, JSON.stringify(meta),
  ]);
  return cost;
}

export async function recordStt(model: string, seconds: number) {
  const pricing = await getSettings('pricing');
  const perMin = pricing.stt[model] ?? pricing.stt.default ?? 0;
  const cost = (seconds / 60) * perMin;
  await q('INSERT INTO usage(provider, model, kind, input_units, cost_usd) VALUES ($1,$2,$3,$4,$5)', ['openai', model, 'stt', seconds, cost]);
  return cost;
}

export async function recordTts(provider: string, model: string, chars: number) {
  const pricing = await getSettings('pricing');
  const perM = pricing.tts[provider === 'azure' ? 'azure' : model] ?? pricing.tts.default ?? 0;
  const cost = (chars * perM) / 1_000_000;
  await q('INSERT INTO usage(provider, model, kind, input_units, cost_usd) VALUES ($1,$2,$3,$4,$5)', [provider, model, 'tts', chars, cost]);
  return cost;
}

function matchPrefix<T>(table: Record<string, T>, model: string): T | undefined {
  const key = Object.keys(table)
    .filter((k) => k !== 'default' && model.startsWith(k))
    .sort((a, b) => b.length - a.length)[0];
  return key ? table[key] : undefined;
}

export async function usageSummary(days = 30) {
  const byDay = await q(
    `SELECT to_char(ts AT TIME ZONE 'Asia/Jerusalem', 'YYYY-MM-DD') AS day, kind, SUM(cost_usd)::float AS cost, COUNT(*)::int AS calls
     FROM usage WHERE ts > now() - ($1 || ' days')::interval GROUP BY 1,2 ORDER BY 1`,
    [String(days)],
  );
  const byModel = await q(
    `SELECT provider, model, kind, SUM(input_units)::float AS input, SUM(output_units)::float AS output, SUM(cost_usd)::float AS cost, COUNT(*)::int AS calls
     FROM usage WHERE ts > now() - ($1 || ' days')::interval GROUP BY 1,2,3 ORDER BY cost DESC`,
    [String(days)],
  );
  return { byDay, byModel, ...(await spend()) };
}
