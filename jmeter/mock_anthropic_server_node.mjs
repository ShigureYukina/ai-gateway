#!/usr/bin/env node

/**
 * Anthropic mock 上游服务器
 * 监听 :18084，模拟 Anthropic Messages API 响应，供回归测试使用。
 */

import http from 'node:http';

const PORT = parseInt(process.env.MOCK_ANTHROPIC_PORT || '18084', 10);

const MODELS = [
  {
    id: 'claude-3-haiku-20240307',
    object: 'model',
    created: 1700000000,
    owned_by: 'anthropic',
  },
];

function jsonResponse(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const method = req.method;

  console.log('[mock-anthropic]', req.method, req.url);

  // ---------- GET /v1/models ----------
  if (method === 'GET' && url.pathname === '/v1/models') {
    return jsonResponse(res, 200, { object: 'list', data: MODELS });
  }

  // ---------- POST /v1/messages ----------
  if (method === 'POST' && url.pathname === '/v1/messages') {
    let body = '';
    req.on('data', chunk => (body += chunk));
    req.on('end', () => {
      try {
        const reqBody = JSON.parse(body);
        const stream = reqBody.stream === true;

        if (stream) {
          res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          });

          res.write(
            'event: message_start\n' +
              'data: {"type":"message_start","message":{"id":"msg_mock_001","type":"message","role":"assistant","content":[],"model":"claude-3-haiku-20240307","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":0}}}\n\n',
          );
          res.write(
            'event: content_block_start\n' +
              'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n',
          );
          res.write(
            'event: content_block_delta\n' +
              'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello from Anthropic mock!"}}\n\n',
          );
          res.write(
            'event: content_block_stop\n' +
              'data: {"type":"content_block_stop","index":0}\n\n',
          );
          res.write(
            'event: message_delta\n' +
              'data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}\n\n',
          );
          res.write('event: message_stop\n' + 'data: {"type":"message_stop"}\n\n');
          res.end();
        } else {
          jsonResponse(res, 200, {
            id: 'msg_mock_001',
            type: 'message',
            role: 'assistant',
            content: [{ type: 'text', text: 'Hello from Anthropic mock!' }],
            model: 'claude-3-haiku-20240307',
            stop_reason: 'end_turn',
            stop_sequence: null,
            usage: { input_tokens: 10, output_tokens: 5 },
          });
        }
      } catch (e) {
        jsonResponse(res, 400, { error: { message: `Invalid JSON: ${e.message}`, type: 'invalid_request_error' } });
      }
    });
    return;
  }

  // ---------- 其他路径 ----------
  jsonResponse(res, 404, { error: { message: 'Not Found', type: 'not_found' } });
});

server.listen(PORT, () => {
  console.log(`[mock-anthropic] Anthropic mock server listening on :${PORT}`);
});
