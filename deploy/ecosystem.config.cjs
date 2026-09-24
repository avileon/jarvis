// Alternative to Docker: Node.js 22 + PM2 + an existing PostgreSQL and reverse proxy (nginx/Caddy).
// Usage: npm ci && npm run build && pm2 start deploy/ecosystem.config.cjs && pm2 save
module.exports = {
  apps: [
    {
      name: 'jarvis',
      cwd: __dirname + '/../server',
      script: 'dist/index.js',
      node_args: '--env-file=../deploy/.env',
      env: { NODE_ENV: 'production', PORT: 3000, HOST: '127.0.0.1', DATA_DIR: __dirname + '/../data', WEB_DIST: __dirname + '/../web/dist' },
      max_memory_restart: '400M',
    },
  ],
};
