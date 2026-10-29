import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer as createHttpServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { Client, InMemoryTransport } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { TOOLS, TOOL_NAMES, createServer, runTool } from '../src/server.mjs';

const SERVER = fileURLToPath(new URL('../src/server.mjs', import.meta.url));
const MODERN = { versionNegotiation: { mode: { pin: '2026-07-28' } } };
const T = '2026-10-28T01:02:03.456Z';
const AREAS = ['ROW_1_LEFT', 'ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_RIGHT'];
const DOOR_TOOL = 'get_front_left_door_position';

const cap = (o = {}) => ({ access: 'READ', matchingAreas: 1, reportedAreas: AREAS.join(', '), minimum: 0, maximum: 1, ...o });
const scalarCap = () => ({ access: 'READ', matchingAreas: null, reportedAreas: null, minimum: null, maximum: null });
const pos = (area, raw, o = {}) => ({ property: 'DOOR_POSITION', area, status: 'OK', capability: cap(), rawValue: raw,
  meaning: raw === 0 ? 'closed' : 'open', unit: null, receivedAt: T, errorType: null, ...o });
const lock = (area, raw) => ({ property: 'DOOR_LOCK', area, status: 'OK', capability: cap({ minimum: null, maximum: null }),
  rawValue: raw, meaning: raw ? 'locked' : 'unlocked', unit: null, receivedAt: T, errorType: null });
const win = (area, raw) => ({ property: 'WINDOW_POSITION', area, status: 'OK', capability: cap({ minimum: 0, maximum: 100 }),
  rawValue: raw, meaning: null, unit: null, receivedAt: T, errorType: null });
const energy = (property, raw) => ({ property, area: null, status: 'OK', capability: scalarCap(), rawValue: raw, meaning: null,
  unit: '%', receivedAt: T, errorType: null });
const SETS = {
  door_status: () => [...AREAS.map((a, i) => pos(a, i % 2)), ...AREAS.map((a, i) => lock(a, i % 2 === 0))],
  window_status: () => AREAS.map((a, i) => win(a, [0, 1, 37, 100][i])),
  energy_level: () => [energy('FUEL_PERCENT', 50), energy('EV_BATTERY_PERCENT', 80)],
  front_left_door_position: () => [pos('ROW_1_LEFT', 1)],
};
const envelope = (requestId, query, outcome, items) => ({ requestId, query, outcome, sdkVersion: '2.3.2', items });
const complete = (body) => envelope(body.requestId, body.query, 'COMPLETE', SETS[body.query]());

/** Fake Android bridge that counts requests and lets each test decide the reply. */
async function bridge(reply, listenPort = 0) {
  const seen = [];
  const server = createHttpServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const entry = { body: JSON.parse(body), closed: false, authorization: req.headers.authorization, url: req.url };
      res.on('close', () => { entry.closed = true; });
      seen.push(entry);
      reply(entry.body, res);
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(listenPort, '127.0.0.1', () => { server.off('error', reject); resolve(); });
  });
  return {
    server,
    port: server.address().port,
    seen,
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }),
  };
}

function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

/** serveStdio's era handling over an in-memory pipe, so the bridge port can point at a fake. */
async function inProcess(port, clientOptions = {}, shutdownSignal) {
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  let factoryCalls = 0;
  const handle = serveStdio(() => {
    factoryCalls += 1;
    return createServer({ port, shutdownSignal });
  }, { transport: serverSide, legacy: 'serve' });
  const client = new Client({ name: 'test', version: '0' }, clientOptions);
  await client.connect(clientSide);
  return { client, clientSide, factoryCalls: () => factoryCalls, close: async () => { await client.close(); await handle.close(); } };
}

async function spawned(env = {}, clientOptions = {}) {
  const transport = new StdioClientTransport({
    command: process.execPath, args: [SERVER], env: { PATH: process.env.PATH ?? '', ...env }, stderr: 'pipe',
  });
  const client = new Client({ name: 'test', version: '0' }, clientOptions);
  await client.connect(transport);
  return client;
}

test('the tool table is exactly the four fixed read-only tools', () => {
  assert.deepEqual(TOOL_NAMES, ['get_door_status', 'get_window_status', 'get_energy_level', DOOR_TOOL]);
  assert.deepEqual(TOOLS.map((tool) => tool.query), ['door_status', 'window_status', 'energy_level', 'front_left_door_position']);
});

test('legacy initialize and modern discovery each list exactly four strict read-only tools', async () => {
  for (const [options, era] of [[{}, '2025-11-25'], [MODERN, '2026-07-28']]) {
    const client = await spawned({}, options);
    try {
      assert.equal(client.getNegotiatedProtocolVersion(), era);
      const { tools } = await client.listTools();
      assert.deepEqual(tools.map((tool) => tool.name), TOOL_NAMES);
      for (const tool of tools) {
        assert.equal(tool.inputSchema.type, 'object', tool.name);
        assert.equal(tool.inputSchema.additionalProperties, false, tool.name);
        assert.deepEqual(Object.keys(tool.inputSchema.properties ?? {}), [], tool.name);
        assert.equal(tool.annotations.readOnlyHint, true, tool.name);
        assert.equal(tool.annotations.destructiveHint, false, tool.name);
        assert.equal(tool.annotations.openWorldHint, false, tool.name);
      }
    } finally {
      await client.close();
    }
  }
});

// The subprocess uses the fixed bridge port, so this test needs 127.0.0.1:18765 free (no adb forward running).
test('real stdio subprocess makes one fake-bridge request per tool call and none for discovery', async (t) => {
  for (const [options, era] of [[{}, '2025-11-25'], [MODERN, '2026-07-28']]) {
    const fake = await bridge((body, res) => json(res, 200, complete(body)), 18765).catch((error) => {
      if (error.code === 'EADDRINUSE') return null;
      throw error;
    });
    if (!fake) { t.skip('127.0.0.1:18765 is in use (for example by an adb forward)'); return; }
    const client = await spawned({}, options);
    try {
      assert.equal(client.getNegotiatedProtocolVersion(), era);
      const { tools } = await client.listTools();
      assert.deepEqual(tools.map((tool) => tool.name), TOOL_NAMES);
      assert.equal(fake.seen.length, 0, 'discovery and listing never reach the bridge');
      for (const [index, tool] of TOOLS.entries()) {
        const result = await client.callTool({ name: tool.name, arguments: {} });
        assert.equal(fake.seen.length, index + 1);
        assert.equal(fake.seen[index].body.query, tool.query);
        assert.equal(fake.seen[index].url, '/v2/vehicle-read');
        assert.equal(fake.seen[index].authorization, undefined);
        if (tool.query === 'front_left_door_position') {
          assert.equal(result.structuredContent.state, 'open');
        } else {
          assert.equal(result.structuredContent.query, tool.query);
        }
      }
    } finally {
      await client.close();
      await fake.close();
    }
  }
});

test('stdout carries only MCP JSON-RPC and stdin EOF ends the process', async () => {
  const child = spawn(process.execPath, [SERVER], { env: { PATH: process.env.PATH ?? '' }, stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const send = (message) => child.stdin.write(`${JSON.stringify(message)}\n`);
  send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {
    protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'raw', version: '0' } } });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  while (!stdout.includes('"id":2')) await new Promise((resolve) => setTimeout(resolve, 10));
  const exited = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
  child.stdin.end();
  const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve('hung'), 3_000))]);
  if (code === 'hung') child.kill();
  assert.equal(code, 0);
  const lines = stdout.trim().split('\n');
  for (const line of lines) assert.equal(JSON.parse(line).jsonrpc, '2.0');
  assert.equal(JSON.parse(lines.find((line) => line.includes('"id":2'))).result.tools.length, 4);
  assert.equal(stderr.trim(), '');
});

test('text and structuredContent come from the same validated result for every tool', async () => {
  const fake = await bridge((body, res) => json(res, 200, complete(body)));
  const session = await inProcess(fake.port, MODERN);
  try {
    const listed = await session.client.listTools();
    assert.equal(listed.tools.length, 4);
    assert.equal(fake.seen.length, 0, 'discovery and listing never reach the bridge');
    assert.equal(session.factoryCalls() >= 1, true);

    const door = await session.client.callTool({ name: 'get_door_status', arguments: {} });
    assert.notEqual(door.isError, true);
    assert.equal(door.structuredContent.outcome, 'COMPLETE');
    assert.deepEqual(door.structuredContent.items, SETS.door_status());
    const doorText = door.content[0].text;
    for (const area of AREAS) assert.match(doorText, new RegExp(area));
    assert.match(doorText, /문 위치.*앞좌측.*닫힘/);
    assert.match(doorText, /문 위치.*앞우측.*열림/);
    assert.match(doorText, /문 잠금.*앞좌측.*잠김/);
    assert.match(doorText, /문 잠금.*앞우측.*잠금 해제/);
    assert.match(doorText, /동시에 읽은 스냅샷이 아닙니다/);
    assert.match(doorText, /최신성을 보장하지 않습니다/);

    const windows = await session.client.callTool({ name: 'get_window_status', arguments: {} });
    assert.notEqual(windows.isError, true);
    assert.deepEqual(windows.structuredContent.items.map((item) => item.rawValue), [0, 1, 37, 100]);
    assert.match(windows.content[0].text, /원시값 37/);
    assert.match(windows.content[0].text, /범위 0~100/);
    assert.doesNotMatch(windows.content[0].text, /37\s*%/);
    assert.doesNotMatch(windows.content[0].text, /(닫힘|열림)/);

    const level = await session.client.callTool({ name: 'get_energy_level', arguments: {} });
    assert.notEqual(level.isError, true);
    assert.match(level.content[0].text, /연료 잔량.*50%/);
    assert.match(level.content[0].text, /EV 배터리 잔량.*80%/);

    const front = await session.client.callTool({ name: DOOR_TOOL, arguments: {} });
    assert.notEqual(front.isError, true);
    assert.equal(front.structuredContent.state, 'open');
    assert.equal(front.structuredContent.rawValue, 1);
    assert.equal(front.structuredContent.area, 'ROW_1_LEFT');
    assert.match(front.content[0].text, /열림 \(open\)/);
    assert.match(front.content[0].text, /원시값 1/);
    assert.match(front.content[0].text, /2026-10-28T01:02:03.456Z/);
    assert.match(front.content[0].text, /최신성을 보장하지 않습니다/);

    assert.deepEqual(fake.seen.map((entry) => entry.body.query),
      ['door_status', 'window_status', 'energy_level', 'front_left_door_position']);
    assert.ok(fake.seen.every((entry) => entry.authorization === undefined));
  } finally {
    await session.close();
    await fake.close();
  }
});

test('partial and failed results are errors that keep every observation and reason', async () => {
  const items = [
    pos('ROW_1_LEFT', 0),
    { ...pos('ROW_1_RIGHT', 0), status: 'NO_VALUE', rawValue: null, meaning: null },
    { ...pos('ROW_2_LEFT', 0), status: 'AREA_UNSUPPORTED', capability: cap({ matchingAreas: 0, minimum: null, maximum: null }),
      rawValue: null, meaning: null, receivedAt: null },
    { ...pos('ROW_2_RIGHT', 0), status: 'UNKNOWN_VALUE', capability: cap({ maximum: 100 }), rawValue: 1, meaning: null },
    ...AREAS.map((a) => ({ ...lock(a, true), status: 'CAPABILITY_FAILED', capability: null, rawValue: null, meaning: null,
      receivedAt: null, errorType: 'SecurityException' })),
  ];
  const fake = await bridge((body, res) => json(res, 200, envelope(body.requestId, body.query, 'PARTIAL', items)));
  const session = await inProcess(fake.port);
  try {
    const result = await session.client.callTool({ name: 'get_door_status', arguments: {} });
    assert.equal(result.isError, true);
    assert.equal(result.structuredContent.outcome, 'PARTIAL');
    assert.deepEqual(result.structuredContent.items, items);
    const text = result.content[0].text;
    assert.match(text, /PARTIAL/);
    assert.match(text, /문 위치.*앞좌측.*닫힘/);
    assert.match(text, /NO_VALUE/);
    assert.match(text, /AREA_UNSUPPORTED/);
    assert.match(text, /UNKNOWN_VALUE.*원시값 1/);
    assert.match(text, /SecurityException/);
    assert.doesNotMatch(text, /앞우측.*(닫힘|열림)/);
  } finally {
    await session.close();
    await fake.close();
  }
  const allBad = SETS.window_status().map((item) => ({ ...item, status: 'READ_FAILED', rawValue: null, receivedAt: null, errorType: 'IOException' }));
  const failedBridge = await bridge((body, res) => json(res, 200, envelope(body.requestId, body.query, 'FAILED', allBad)));
  const failedSession = await inProcess(failedBridge.port);
  try {
    const result = await failedSession.client.callTool({ name: 'get_window_status', arguments: {} });
    assert.equal(result.isError, true);
    assert.equal(result.structuredContent.outcome, 'FAILED');
    assert.match(result.content[0].text, /READ_FAILED/);
  } finally {
    await failedSession.close();
    await failedBridge.close();
  }
});

test('bridge errors and Android timeouts become isError results that state no value', async () => {
  const replies = [
    [409, (body) => ({ requestId: body.requestId, ok: false, code: 'BUSY' }), 'BUSY'],
    [503, (body) => ({ requestId: body.requestId, ok: false, code: 'APP_NOT_READY' }), 'APP_NOT_READY'],
    [403, () => ({ ok: false, code: 'REQUEST_FORBIDDEN' }), 'REQUEST_FORBIDDEN'],
    [200, (body) => envelope(body.requestId, body.query, 'TIMEOUT', []), 'TIMEOUT'],
    [401, () => ({ ok: false, code: 'UNAUTHORIZED' }), 'INVALID_RESPONSE'],
  ];
  for (const [status, make, code] of replies) {
    const fake = await bridge((body, res) => json(res, status, make(body)));
    const session = await inProcess(fake.port);
    try {
      for (const name of TOOL_NAMES) {
        const result = await session.client.callTool({ name, arguments: {} });
        assert.equal(result.isError, true, `${name} ${code}`);
        assert.equal(result.structuredContent.code, code, `${name} ${code}`);
        assert.equal('items' in result.structuredContent, false);
        assert.equal('state' in result.structuredContent, false);
        assert.match(result.content[0].text, new RegExp(code));
        assert.doesNotMatch(result.content[0].text, /\((open|closed)\)|닫힘|열림 \(|[0-9]+%/);
      }
      assert.equal(fake.seen.length, TOOL_NAMES.length, 'one HTTP request per call, no retry');
    } finally {
      await session.close();
      await fake.close();
    }
  }
});

test('only omitted or empty arguments reach the bridge, for every tool', async () => {
  const fake = await bridge((body, res) => json(res, 503, { requestId: body.requestId, ok: false, code: 'APP_NOT_READY' }));
  const session = await inProcess(fake.port);
  try {
    const raw = async (id, name, args) => {
      const reply = new Promise((resolve) => {
        const previous = session.clientSide.onmessage;
        session.clientSide.onmessage = (message, extra) => {
          if (message.id === id) { session.clientSide.onmessage = previous; resolve(message); }
          else previous?.(message, extra);
        };
      });
      const params = args === 'omit' ? { name } : { name, arguments: args };
      await session.clientSide.send({ jsonrpc: '2.0', id, method: 'tools/call', params });
      return reply;
    };
    let id = 1_000;
    for (const name of TOOL_NAMES) {
      for (const args of [{ area: 'ROW_1_RIGHT' }, { query: 'window_status' }, { remainingMs: 1 }, null, [], 'x']) {
        const message = await raw(id += 1, name, args);
        const rejected = message.error !== undefined || message.result?.isError === true;
        assert.ok(rejected, `${name} arguments ${JSON.stringify(args)} must be rejected`);
        assert.equal(message.result?.structuredContent, undefined);
      }
    }
    assert.equal(fake.seen.length, 0);
    for (const name of TOOL_NAMES) {
      for (const args of ['omit', {}]) {
        const message = await raw(id += 1, name, args);
        assert.equal(message.result.structuredContent.code, 'APP_NOT_READY');
      }
    }
    assert.equal(fake.seen.length, TOOL_NAMES.length * 2);
  } finally {
    await session.close();
    await fake.close();
  }
});

test('MCP cancellation and shutdown abort the bridge request instead of answering late', async () => {
  const fake = await bridge(() => {});
  const shutdown = new AbortController();
  const session = await inProcess(fake.port, {}, shutdown.signal);
  try {
    const cancel = new AbortController();
    const call = session.client.callTool({ name: 'get_energy_level', arguments: {} }, { signal: cancel.signal });
    while (fake.seen.length < 1) await new Promise((resolve) => setTimeout(resolve, 5));
    cancel.abort();
    await assert.rejects(call);
    const started = performance.now();
    while (!fake.seen[0].closed && performance.now() - started < 2_000) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    assert.equal(fake.seen[0].closed, true, 'cancel closed the bridge connection');

    const second = session.client.callTool({ name: 'get_door_status', arguments: {} });
    while (fake.seen.length < 2) await new Promise((resolve) => setTimeout(resolve, 5));
    shutdown.abort();
    const result = await second;
    assert.equal(result.isError, true);
    assert.equal(result.structuredContent.code, 'CANCELLED');
    assert.equal(fake.seen.length, 2);
  } finally {
    await session.close();
    await fake.close();
  }
});

test('presentation counts against the same deadline as the fetch', async () => {
  const fake = await bridge((body, res) => json(res, 200, complete(body)));
  try {
    for (const tool of TOOLS) {
      let clock = 0;
      const result = await runTool(tool, {
        port: fake.port, now: () => clock,
        render: (dto, render) => { const out = render(dto); clock = 10_000; return out; },
      });
      assert.equal(result.isError, true, tool.name);
      assert.equal(result.structuredContent.code, 'TIMEOUT', tool.name);
      assert.equal('items' in result.structuredContent, false, tool.name);
    }
    const quick = await runTool(TOOLS[1], { port: fake.port });
    assert.notEqual(quick.isError, true);
  } finally {
    await fake.close();
  }
});

test('two server processes run independently over their own stdio', async () => {
  const first = await spawned();
  const second = await spawned();
  try {
    const [a, b] = await Promise.all([first.listTools(), second.listTools()]);
    assert.deepEqual(a.tools.map((tool) => tool.name), b.tools.map((tool) => tool.name));
    assert.notEqual(first.transport.pid, second.transport.pid);
  } finally {
    await first.close();
    await second.close();
  }
});

test('a server path reached through a junction or symlink still serves MCP', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'pleostest-mcp-'));
  const link = join(dir, 'mcp');
  symlinkSync(fileURLToPath(new URL('..', import.meta.url)), link, 'junction');
  const transport = new StdioClientTransport({
    command: process.execPath, args: [join(link, 'src', 'server.mjs')], env: { PATH: process.env.PATH ?? '' }, stderr: 'pipe',
  });
  const client = new Client({ name: 'test', version: '0' });
  try {
    await client.connect(transport);
    const { tools } = await client.listTools();
    assert.deepEqual(tools.map((tool) => tool.name), TOOL_NAMES);
  } finally {
    await client.close().catch(() => {});
    rmSync(link);
    rmSync(dir, { recursive: true });
  }
});
