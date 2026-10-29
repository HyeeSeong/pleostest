import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFrontLeftDoor, VEHICLE_FAILURES } from '../src/door-client.mjs';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const T = '2026-10-28T01:02:03.456Z';
const CAPABILITY = { access: 'READ', matchingAreas: 1, reportedAreas: 'ROW_1_LEFT', minimum: 0.0, maximum: 1.0 };
const QUERY = 'front_left_door_position';

/** Fake Android bridge on an ephemeral loopback port. `reply(body, res, req)` answers each request. */
async function bridge(reply) {
  const seen = [];
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, body });
      reply(JSON.parse(body), res, req);
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    port: server.address().port,
    seen,
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }),
  };
}

function json(res, status, value, type = 'application/json; charset=utf-8') {
  const body = typeof value === 'string' ? value : JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(body), Connection: 'close' });
  res.end(body);
}

const item = (o = {}) => ({
  property: 'DOOR_POSITION', area: 'ROW_1_LEFT', status: 'OK', capability: { ...CAPABILITY }, rawValue: 0,
  meaning: 'closed', unit: null, receivedAt: T, errorType: null, ...o,
});
const envelope = (requestId, outcome, items, o = {}) => ({ requestId, query: QUERY, outcome, sdkVersion: '2.3.2', items, ...o });
const success = (requestId, raw) => envelope(requestId, 'COMPLETE', [item({ rawValue: raw, meaning: raw === 0 ? 'closed' : 'open' })]);
const failure = (requestId, status, extra = {}) => envelope(requestId, 'FAILED', [item({
  status, capability: null, rawValue: null, meaning: null, receivedAt: null, ...extra })]);

async function withBridge(reply, run) {
  const fake = await bridge(reply);
  try { return await run(fake); } finally { await fake.close(); }
}

test('closed and open keep the original scalar result after one v2 POST without credentials', async () => {
  for (const raw of [0, 1]) {
    await withBridge((body, res) => json(res, 200, success(body.requestId, raw)), async (fake) => {
      const result = await readFrontLeftDoor({ port: fake.port });
      assert.equal(fake.seen.length, 1);
      const [sent] = fake.seen;
      assert.equal(sent.method, 'POST');
      assert.equal(sent.url, '/v2/vehicle-read');
      assert.equal(sent.headers.host, `127.0.0.1:${fake.port}`);
      assert.equal(sent.headers.authorization, undefined);
      assert.equal(sent.headers.origin, undefined);
      const body = JSON.parse(sent.body);
      assert.deepEqual(Object.keys(body).sort(), ['query', 'remainingMs', 'requestId']);
      assert.equal(body.query, QUERY);
      assert.match(body.requestId, UUID_RE);
      assert.deepEqual(result, {
        ok: true, requestId: body.requestId, area: 'ROW_1_LEFT', status: raw === 0 ? 'CLOSED' : 'OPEN',
        state: raw === 0 ? 'closed' : 'open', rawValue: raw, receivedAt: T,
        sdkVersion: '2.3.2', capability: CAPABILITY,
      });
    });
  }
});

test('every vehicle failure keeps status, capability and observation without a normal state', async () => {
  assert.equal(VEHICLE_FAILURES.length, 9);
  for (const code of VEHICLE_FAILURES) {
    const itemStatus = code;
    const extras = {
      NO_VALUE: { capability: CAPABILITY, receivedAt: T },
      UNKNOWN_VALUE: { capability: { ...CAPABILITY, maximum: 100 }, rawValue: 0, receivedAt: T },
      CAPABILITY_FAILED: { errorType: 'SecurityException' },
      READ_UNAVAILABLE: { capability: { ...CAPABILITY, access: 'NONE' } },
      AREA_UNSUPPORTED: { capability: { ...CAPABILITY, matchingAreas: 0, minimum: null, maximum: null } },
      SUPPORT_UNCONFIRMED: { capability: { ...CAPABILITY, minimum: null } },
      READ_FAILED: { capability: CAPABILITY, errorType: 'IllegalStateException' },
    };
    const wire = ['TIMEOUT', 'CANCELLED'].includes(code)
      ? (id) => envelope(id, code, [])
      : (id) => failure(id, itemStatus, extras[code]);
    await withBridge((body, res) => json(res, 200, wire(body.requestId)), async (fake) => {
      const result = await readFrontLeftDoor({ port: fake.port });
      const { requestId } = JSON.parse(fake.seen[0].body);
      const extra = extras[code] ?? {};
      assert.deepEqual(result, {
        ok: false, source: 'android', code, requestId, area: 'ROW_1_LEFT', status: code, sdkVersion: '2.3.2',
        capability: extra.capability ?? null, errorType: extra.errorType ?? null,
        observation: extra.receivedAt ? { rawValue: extra.rawValue ?? null, receivedAt: T } : null,
      }, code);
      assert.equal('state' in result, false);
    });
  }
});

test('the alias is never projected onto another door, a lock or an extra item', async () => {
  const cases = [
    [item({ area: 'ROW_1_RIGHT' })],
    [item({ property: 'DOOR_LOCK', capability: { ...CAPABILITY, minimum: null, maximum: null }, rawValue: true, meaning: 'locked' })],
    [item({ property: 'WINDOW_POSITION', meaning: null })],
    [item(), item({ area: 'ROW_1_RIGHT' })],
    [item(), item({ property: 'DOOR_LOCK', capability: { ...CAPABILITY, minimum: null, maximum: null }, rawValue: true, meaning: 'locked' })],
    [],
  ];
  for (const items of cases) {
    await withBridge((body, res) => json(res, 200, envelope(body.requestId, 'COMPLETE', items)), async (fake) => {
      const result = await readFrontLeftDoor({ port: fake.port });
      assert.equal(result.code, 'INVALID_RESPONSE', JSON.stringify(items));
      assert.equal(result.source, 'pc');
      assert.equal(result.ok, false);
      assert.equal('state' in result, false);
      assert.equal(fake.seen.length, 1);
    });
  }
});

test('corrupt, mismatched, retired or oversized responses are INVALID_RESPONSE', async () => {
  const cases = [
    (id, res) => json(res, 200, { ...success(id, 0), requestId: '0f8fad5b-d9cb-469f-a165-70867728950e' }),
    (id, res) => json(res, 200, { ...success(id, 0), query: 'door_status' }),
    (id, res) => json(res, 200, envelope(id, 'COMPLETE', [item({ meaning: 'open' })])),
    (id, res) => json(res, 200, envelope(id, 'COMPLETE', [item({ rawValue: 0, meaning: 'open' })])),
    (id, res) => json(res, 200, envelope(id, 'COMPLETE', [item({ capability: { ...CAPABILITY, maximum: 100 } })])),
    (id, res) => json(res, 200, envelope(id, 'COMPLETE', [item({ capability: { ...CAPABILITY, matchingAreas: 2 } })])),
    (id, res) => json(res, 200, envelope(id, 'COMPLETE', [item({ receivedAt: 'yesterday' })])),
    (id, res) => json(res, 200, { ...success(id, 0), sdkVersion: '2.3.1' }),
    (id, res) => json(res, 200, { ...success(id, 0), extra: true }),
    (id, res) => json(res, 200, envelope(id, 'FAILED', [item()])),
    (id, res) => json(res, 200, failure(id, 'READ_FAILED', { errorType: 'bad name' })),
    (id, res) => json(res, 200, { requestId: id, ok: true, area: 'ROW_1_LEFT', status: 'CLOSED', state: 'closed', rawValue: 0,
      receivedAt: T, sdkVersion: '2.3.2', capability: CAPABILITY }),
    (id, res) => json(res, 200, '{not json'),
    (id, res) => json(res, 200, success(id, 0), 'text/plain'),
    (id, res) => json(res, 200, { ...success(id, 0), pad: 'x'.repeat(17 * 1024) }),
    (id, res) => json(res, 500, { ok: false }),
    (id, res) => json(res, 401, { ok: false, code: 'UNAUTHORIZED' }),
    (id, res) => json(res, 403, { ok: false, code: 'UNAUTHORIZED' }),
    (id, res) => json(res, 403, { ok: false, code: 'INVALID_REQUEST' }),
    (id, res) => json(res, 409, { requestId: id, ok: true, code: 'BUSY' }),
    (id, res) => json(res, 503, { requestId: '0f8fad5b-d9cb-469f-a165-70867728950e', ok: false, code: 'APP_NOT_READY' }),
  ];
  for (const [index, reply] of cases.entries()) {
    await withBridge((body, res) => reply(body.requestId, res), async (fake) => {
      const result = await readFrontLeftDoor({ port: fake.port });
      assert.equal(result.code, 'INVALID_RESPONSE', `case ${index}`);
      assert.equal(result.ok, false);
      assert.equal(result.source, 'pc');
      assert.equal('state' in result, false);
      assert.equal(fake.seen.length, 1);
    });
  }
});

test('forbidden, admission and connection outcomes stay distinct', async () => {
  const replies = [
    [(id, res) => json(res, 403, { ok: false, code: 'REQUEST_FORBIDDEN' }), 'REQUEST_FORBIDDEN', 'android'],
    [(id, res) => json(res, 400, { ok: false, code: 'INVALID_REQUEST' }), 'INVALID_REQUEST', 'android'],
    [(id, res) => json(res, 409, { requestId: id, ok: false, code: 'BUSY' }), 'BUSY', 'android'],
    [(id, res) => json(res, 503, { requestId: id, ok: false, code: 'APP_NOT_READY' }), 'APP_NOT_READY', 'android'],
    [(id, res) => { res.writeHead(302, { Location: 'http://127.0.0.1:1/' }); res.end(); }, 'INVALID_RESPONSE', 'pc'],
    [(id, res, req) => req.socket.destroy(), 'ANDROID_UNREACHABLE', 'pc'],
  ];
  for (const [reply, code, source] of replies) {
    await withBridge((body, res, req) => reply(body.requestId, res, req), async (fake) => {
      const result = await readFrontLeftDoor({ port: fake.port });
      assert.equal(result.code, code);
      assert.equal(result.source, source);
      assert.equal(result.area, 'ROW_1_LEFT');
      assert.equal(fake.seen.length, 1, code);
      if (code === 'BUSY' || code === 'APP_NOT_READY') assert.match(result.requestId, UUID_RE);
    });
  }
  const closed = await bridge(() => {});
  const port = closed.port;
  await closed.close();
  const unreachable = await readFrontLeftDoor({ port });
  assert.equal(unreachable.code, 'ANDROID_UNREACHABLE');
});

test('a leftover VEHICLE_BRIDGE_TOKEN in the environment never becomes authentication', async () => {
  const previous = process.env.VEHICLE_BRIDGE_TOKEN;
  process.env.VEHICLE_BRIDGE_TOKEN = 'A'.repeat(43);
  try {
    await withBridge((body, res) => json(res, 200, success(body.requestId, 0)), async (fake) => {
      const result = await readFrontLeftDoor({ port: fake.port });
      assert.equal(result.ok, true);
      assert.equal(fake.seen.length, 1);
      assert.equal(fake.seen[0].headers.authorization, undefined);
    });
  } finally {
    if (previous === undefined) delete process.env.VEHICLE_BRIDGE_TOKEN; else process.env.VEHICLE_BRIDGE_TOKEN = previous;
  }
});

test('slow headers or body end as TIMEOUT at the single deadline with one attempt', async () => {
  const stalls = [
    () => {},
    (id, res) => { res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': 500 }); res.write('{'); },
  ];
  for (const stall of stalls) {
    await withBridge((body, res) => stall(body.requestId, res), async (fake) => {
      const started = performance.now();
      const result = await readFrontLeftDoor({ port: fake.port, deadlineMs: 300 });
      const elapsed = performance.now() - started;
      assert.equal(result.code, 'TIMEOUT');
      assert.equal(result.source, 'pc');
      assert.ok(elapsed >= 280 && elapsed < 1_500, `elapsed ${elapsed}`);
      assert.equal(fake.seen.length, 1);
      assert.ok(JSON.parse(fake.seen[0].body).remainingMs <= 300);
    });
  }
});

test('a valid answer that arrives after the deadline is discarded', async () => {
  let clock = 0;
  await withBridge((body, res) => { clock = 10_000; json(res, 200, success(body.requestId, 1)); }, async (fake) => {
    const result = await readFrontLeftDoor({ port: fake.port, now: () => clock });
    assert.equal(result.code, 'TIMEOUT');
    assert.equal(result.ok, false);
    assert.equal('state' in result, false);
  });
});

test('caller cancellation ends only its own request', async () => {
  const held = [];
  await withBridge((body, res) => held.push([body.requestId, res]), async (fake) => {
    const cancel = new AbortController();
    const first = readFrontLeftDoor({ port: fake.port, signal: cancel.signal });
    const second = readFrontLeftDoor({ port: fake.port });
    while (held.length < 2) await new Promise((resolve) => setTimeout(resolve, 5));
    cancel.abort();
    assert.equal((await first).code, 'CANCELLED');
    // The cancelled request's socket is already gone, so answering both reaches only the second.
    for (const [id, res] of held) if (!res.destroyed) json(res, 200, success(id, 0));
    const other = await second;
    assert.equal(other.ok, true);
    assert.notEqual(JSON.parse(fake.seen[0].body).requestId, JSON.parse(fake.seen[1].body).requestId);
    assert.equal(fake.seen.length, 2);
  });
  const aborted = new AbortController();
  aborted.abort();
  const early = await readFrontLeftDoor({ port: 1, signal: aborted.signal });
  assert.equal(early.code, 'CANCELLED');
});
