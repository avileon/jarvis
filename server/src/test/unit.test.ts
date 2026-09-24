import { describe, expect, it, beforeAll } from 'vitest';

beforeAll(() => {
  process.env.JARVIS_MASTER_KEY = 'c'.repeat(64);
  process.env.SESSION_SECRET = 'd'.repeat(40);
});

describe('crypto', () => {
  it('round-trips and detects tampering', async () => {
    const { encrypt, decrypt, hashPassword, verifyPassword } = await import('../lib/crypto.js');
    const enc = encrypt('סוד 123');
    expect(decrypt(enc)).toBe('סוד 123');
    const parts = enc.split('.');
    parts[3] = parts[3]!.slice(0, -2) + (parts[3]!.endsWith('A') ? 'BB' : 'AA');
    expect(() => decrypt(parts.join('.'))).toThrow();
    const h = hashPassword('correct horse');
    expect(verifyPassword('correct horse', h)).toBe(true);
    expect(verifyPassword('wrong', h)).toBe(false);
  });
});

describe('confirmation classifier', () => {
  it('classifies Hebrew yes/no', async () => {
    const { classifyConfirmation } = await import('../ai/agent.js');
    expect(classifyConfirmation('כן')).toBe('yes');
    expect(classifyConfirmation('כן, תאשר.')).toBe('yes');
    expect(classifyConfirmation("ג'ארביס, בצע")).toBe('yes');
    expect(classifyConfirmation('לא')).toBe('no');
    expect(classifyConfirmation('אל תעשה את זה')).toBe('no');
    expect(classifyConfirmation('כנראה שלא')).toBe(null);
    expect(classifyConfirmation('מה השעה?')).toBe(null);
  });
});

describe('provider message mapping', () => {
  it('merges tool results for Anthropic', async () => {
    const { toAnthropicMessages } = await import('../ai/anthropic.js');
    const out = toAnthropicMessages([
      { role: 'user', content: 'a' },
      { role: 'assistant', content: '', toolCalls: [{ id: '1', name: 'x', args: {} }, { id: '2', name: 'y', args: {} }] },
      { role: 'tool', toolCallId: '1', name: 'x', content: 'r1' },
      { role: 'tool', toolCallId: '2', name: 'y', content: 'r2' },
    ]);
    expect(out.map((m) => m.role)).toEqual(['user', 'assistant', 'user']);
    expect(out[2]!.content).toHaveLength(2);
  });
});

describe('ssml', () => {
  it('escapes text', async () => {
    const { buildSsml } = await import('../voice/speech.js');
    const s = buildSsml('a < b & "c"', { azureVoice: 'he-IL-AvriNeural', azureRate: '-4%', azurePitch: '-10%', azureStyle: '' });
    expect(s).toContain('a &lt; b &amp; &quot;c&quot;');
    expect(s).toContain('he-IL-AvriNeural');
  });
});

describe('drive folder parsing', () => {
  it('extracts id from URL', async () => {
    const { parseFolderId } = await import('../google/google.js');
    expect(parseFolderId('https://drive.google.com/drive/folders/1AbC_d-9?usp=sharing')).toBe('1AbC_d-9');
    expect(parseFolderId('1AbC_d-9')).toBe('1AbC_d-9');
  });
});

describe('sleep command', () => {
  it('detects Hebrew sleep phrases', async () => {
    process.env.JARVIS_MASTER_KEY ??= 'c'.repeat(64);
    const { isSleepCommand } = await import('../routes/device.js');
    expect(isSleepCommand("ג'ארביס, לך לישון.")).toBe(true);
    expect(isSleepCommand('תלך לישון')).toBe(true);
    expect(isSleepCommand('לילה טוב')).toBe(true);
    expect(isSleepCommand('מתי הילדים הולכים לישון בדרך כלל?')).toBe(false);
    expect(isSleepCommand('מה יש לי היום?')).toBe(false);
  });
});
