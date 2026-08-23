#!/usr/bin/env node

/**
 * Error mock upstream — always returns 500
 * Used by regression.sh for fallback testing.
 * Listens on :18081 by default.
 */

import http from 'node:http';

const PORT = parseInt(process.env.ERROR_MOCK_PORT || '18081', 10);

function jsonResponse(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  console.log('[mock-error]', req.method, req.url);
  jsonResponse(res, 500, {
    error: {
      message: 'Mock upstream failure',
      type: 'server_error',
    },
  });
});

server.listen(PORT, () => {
  console.log(`[mock-error] listening on :${PORT}`);
});
