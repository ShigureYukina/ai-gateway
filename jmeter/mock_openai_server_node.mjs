#!/usr/bin/env node

/**
 * OpenAI 兼容 mock 上游服务器
 * 监听 :18080，模拟 OpenAI API 响应，供 verify.sh 黑盒测试使用。
 * 该 mock 也用于压测，保持语义稳定前提下尽量减少自身噪音。
 */

import http from 'node:http';

const PORT = parseInt(process.env.MOCK_PORT || '18080', 10);
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const SSE_HEADERS = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache',
  Connection: 'keep-alive',
};

const MODELS = [
  { id: 'gpt-4o-mini', object: 'model', created: 1700000000, owned_by: 'mock' },
  { id: 'gpt-4o', object: 'model', created: 1700000000, owned_by: 'mock' },
];

const MODELS_RESPONSE = JSON.stringify({ object: 'list', data: MODELS });
const NOT_FOUND_RESPONSE = JSON.stringify({ error: { message: 'Not Found', type: 'not_found' } });
const CHAT_PREFIX = '{"id":"chatcmpl-mock","object":"chat.completion","created":';
const CHAT_MIDDLE = ',"model":';
const CHAT_SUFFIX = ',"choices":[{"index":0,"message":{"role":"assistant","content":"Hello from mock!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}';
const CHAT_STREAM_PREFIX = '{"id":"chatcmpl-mock","object":"chat.completion.chunk","created":';
const CHAT_STREAM_SUFFIX = ',"choices":[{"index":0,"delta":{"content":"Hello from mock!"},"finish_reason":"stop"}]}';

function requestPath(url = '') {
  const queryIndex = url.indexOf('?');
  return queryIndex === -1 ? url : url.slice(0, queryIndex);
}

function jsonResponse(res, status, body) {
  res.writeHead(status, JSON_HEADERS);
  res.end(JSON.stringify(body));
}

function jsonTextResponse(res, status, bodyText) {
  res.writeHead(status, JSON_HEADERS);
  res.end(bodyText);
}

function buildChatResponse(created, model) {
  return CHAT_PREFIX + created + CHAT_MIDDLE + JSON.stringify(model) + CHAT_SUFFIX;
}

function buildChatStreamChunk(created, model) {
  return CHAT_STREAM_PREFIX + created + CHAT_MIDDLE + JSON.stringify(model) + CHAT_STREAM_SUFFIX;
}

const server = http.createServer((req, res) => {
  const method = req.method;
  const pathname = requestPath(req.url);

  // ---------- GET /v1/models ----------
  if (method === 'GET' && pathname === '/v1/models') {
    return jsonTextResponse(res, 200, MODELS_RESPONSE);
  }

  // ---------- POST /v1/chat/completions ----------
  if (method === 'POST' && pathname === '/v1/chat/completions') {
    let body = '';
    req.on('data', chunk => (body += chunk));
    req.on('end', () => {
      try {
        const reqBody = JSON.parse(body);
        const model = reqBody.model || 'gpt-4o-mini';
        const stream = reqBody.stream === true;
        const created = Math.floor(Date.now() / 1000);

        if (stream) {
          // 流式响应
          res.writeHead(200, SSE_HEADERS);
          res.write(`data: ${buildChatStreamChunk(created, model)}\n\n`);
          res.write('data: [DONE]\n\n');
          res.end();
        } else {
          // 非流式响应
          jsonTextResponse(res, 200, buildChatResponse(created, model));
        }
      } catch (e) {
        jsonResponse(res, 400, { error: { message: `Invalid JSON: ${e.message}`, type: 'invalid_request_error' } });
      }
    });
    return;
  }

  // ---------- 其他路径 ----------
  jsonTextResponse(res, 404, NOT_FOUND_RESPONSE);
});

server.listen(PORT, () => {
  console.log(`[mock] OpenAI compatible server listening on :${PORT}`);
});
