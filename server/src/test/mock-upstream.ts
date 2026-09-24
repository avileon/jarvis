import http from 'node:http';

/** A fake Anthropic / OpenAI / Azure / webhook server for integration tests. */
export interface Recorded {
  path: string;
  headers: http.IncomingHttpHeaders;
  body: string;
}

export type Scripted = (req: Recorded) => { status?: number; json?: unknown; raw?: Buffer; type?: string } | undefined;

export async function startMock(script: Scripted) {
  const calls: Recorded[] = [];
  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const rec: Recorded = { path: req.url ?? '', headers: req.headers, body: Buffer.concat(chunks).toString('utf8') };
      calls.push(rec);
      const out = script(rec) ?? { status: 404, json: { error: 'unscripted' } };
      res.statusCode = out.status ?? 200;
      if (out.raw) {
        res.setHeader('content-type', out.type ?? 'application/octet-stream');
        res.end(out.raw);
      } else {
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify(out.json ?? {}));
      }
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const port = (server.address() as any).port as number;
  return { url: `http://127.0.0.1:${port}`, calls, close: () => new Promise<void>((r) => server.close(() => r())) };
}
