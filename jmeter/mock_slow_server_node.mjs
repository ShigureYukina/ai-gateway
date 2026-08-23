#!/usr/bin/env node

/**
 * Slow mock upstream — responds with configurable delay
 * Used by regression.sh for timeout and concurrent-limit testing.
 * Default port 18082, delay 6s (can override via MOCK_SLOW_DELAY_MS env).
 * For streaming: sends first chunk immediately, then delays before [DONE].
 */

import http from 'node:http';

const PORT = parseInt(process.env.MOCK_SLOW_PORT || '18082', 10);
const DELAY_MS = parseInt(process.env.MOCK_SLOW_DELAY_MS || '6000', 10);

function jsonResponse(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const method = req.method;

  console.log(`[mock-slow] ${method} ${url.pathname} delay=${DELAY_MS}ms`);

  if (method === 'POST' && url.pathname === '/v1/chat/completions') {
    let body = '';
    req.on('data', chunk => (body += chunk));
    req.on('end', () => {
      try {
        const reqBody = JSON.parse(body);
        const stream = reqBody.stream === true;

        if (stream) {
          // Streaming: emit first chunk immediately, then delay before [DONE]
          res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          });
          const chunk = {
            id: 'chatcmpl-slow',
            object: 'chat.completion.chunk',
            created: Math.floor(Date.now() / 1000),
            model: reqBody.model || 'gpt-4o-mini',
            choices: [{ index: 0, delta: { content: 'Slow ' }, finish_reason: null }],
          };
          res.write(`data: ${JSON.stringify(chunk)}\n\n`);
          setTimeout(() => {
            const done = {
              id: 'chatcmpl-slow',
              object: 'chat.completion.chunk',
              created: Math.floor(Date.now() / 1000),
              model: reqBody.model || 'gpt-4o-mini',
              choices: [{ index: 0, delta: { content: 'response' }, finish_reason: 'stop' }],
            };
            res.write(`data: ${JSON.stringify(done)}\n\n`);
            res.write('data: [DONE]\n\n');
            res.end();
          }, DELAY_MS);
        } else {
          // Non-streaming: delay before full response
          setTimeout(() => {
            jsonResponse(res, 200, {
              id: 'chatcmpl-slow',
              object: 'chat.completion',
              created: Math.floor(Date.now() / 1000),
              model: reqBody.model || 'gpt-4o-mini',
              choices: [{ index: 0, message: { role: 'assistant', content: 'Slow response' }, finish_reason: 'stop' }],
              usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
            });
          }, DELAY_MS);
        }
      } catch (e) {
        jsonResponse(res, 400, { error: { message: `Invalid JSON: ${e.message}`, type: 'invalid_request_error' } });
      }
    });
    return;
  }

  if (method === 'GET' && url.pathname === '/v1/models') {
    return jsonResponse(res, 200, {
      object: 'list',
      data: [{ id: 'gpt-4o-mini', object: 'model', created: 1700000000, owned_by: 'mock' }],
    });
  }

  jsonResponse(res, 404, { error: { message: 'Not Found', type: 'not_found' } });
});

server.listen(PORT, () => {
  console.log(`[mock-slow] listening on :${PORT} with delay=${DELAY_MS}ms`);
});
