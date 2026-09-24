import { z } from 'zod';

const schema = z.object({
  NODE_ENV: z.string().default('production'),
  PORT: z.coerce.number().default(3000),
  HOST: z.string().default('0.0.0.0'),
  PUBLIC_URL: z.string().url().default('http://localhost:3000'),
  DATABASE_URL: z.string().default('postgres://jarvis:jarvis@localhost:5432/jarvis'),
  /** 32-byte key, base64 or hex. Encrypts API keys and OAuth tokens at rest. */
  JARVIS_MASTER_KEY: z.string().min(32),
  /** HMAC secret for admin session cookies. */
  SESSION_SECRET: z.string().min(32),
  /** First-run admin bootstrap. Ignored once an admin exists. */
  ADMIN_USERNAME: z.string().default('avi'),
  ADMIN_PASSWORD: z.string().optional(),
  DATA_DIR: z.string().default('./data'),
  WEB_DIST: z.string().default('../web/dist'),
  TRUST_PROXY: z.coerce.boolean().default(true),
});

export type Config = z.infer<typeof schema>;

let cached: Config | null = null;
export function config(): Config {
  if (!cached) {
    const parsed = schema.safeParse(process.env);
    if (!parsed.success) {
      console.error('Invalid environment:', parsed.error.flatten().fieldErrors);
      process.exit(1);
    }
    cached = parsed.data;
  }
  return cached;
}

/** For tests. */
export function setConfig(c: Partial<Config>) {
  cached = schema.parse({ ...process.env, ...c });
}
