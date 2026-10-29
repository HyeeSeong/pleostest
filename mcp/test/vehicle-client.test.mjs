import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readVehicle, QUERIES, BRIDGE_PATH, MAX_RESPONSE_BYTES } from '../src/vehicle-client.mjs';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const T = '2026-10-28T01:02:03.456Z';
const AREAS = ['ROW_1_LEFT', 'ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_RIGHT'];

const cap = (o = {}) => ({ access: 'READ', matchingAreas: 1, reportedAreas: AREAS.join(', '), minimum: 0, maximum: 1, ...o });
const scalarCap = (o = {}) => ({ access: 'READ', matchingAreas: null, reportedAreas: null, minimum: null, maximum: null, ...o });
const pos = (area, raw, o = {}) => ({
  property: 'DOOR_POSITION', area, status: 'OK', capability: cap(), rawValue: raw,
  meaning: raw === 0 ? 'closed' : 'open', unit: null, receivedAt: T, errorType: null, ...o,
});
const lock = (area, raw, o = {}) => ({
  property: 'DOOR_LOCK', area, status: 'OK', capability: cap({ minimum: null, maximum: null }), rawValue: raw,
  meaning: raw ? 'locked' : 'unlocked', unit: null, receivedAt: T, errorType: null, ...o,
});
const win = (area, raw, o = {}) => ({
  property: 'WINDOW_POSITION', area, status: 'OK', capability: cap({ minimum: 0, maximum: 100 }), rawValue: raw,
  meaning: null, unit: null, receivedAt: T, errorType: null, ...o,
});
const energy = (property, raw, o = {}) => ({
  property, area: null, status: 'OK', capability: scalarCap(), rawValue: raw, meaning: null, unit: '%',
  receivedAt: T, errorType: null, ...o,
});
const bare = (base, status, o = {}) => ({
  ...base, status, rawValue: null, meaning: null, unit: null, receivedAt: null, errorType: null, ...o,
});

const SETS = {
  door_status: () => [...AREAS.map((a) => pos(a, 0)), ...AREAS.map((a) => lock(a, true))],
  window_status: () => AREAS.map((a) => win(a, 37)),
  energy_level: () => [energy('FUEL_PERCENT', 50), energy('EV_BATTERY_PERCENT', 80)],
  front_left_door_position: () => [pos('ROW_1_LEFT', 0)],
};
const envelope = (requestId, query, outcome, items, o = {}) => ({ requestId, query, outcome, sdkVersion: '2.3.2', items, ...o });

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

async function withBridge(reply, run) {
  const fake = await bridge(reply);
  try { return await run(fake); } finally { await fake.close(); }
}

/** Runs one call against a bridge answering with `make(requestId, query)`; returns { result, fake }. */
async function call(query, make, status = 200) {
  return withBridge((body, res) => json(res, status, make(body.requestId, body.query)), async (fake) => ({
    result: await readVehicle(query, { port: fake.port }),
    seen: fake.seen,
  }));
}

test('the four fixed queries are the only ones and each call is one POST without credentials', async () => {
  assert.deepEqual(Object.keys(QUERIES), ['door_status', 'window_status', 'energy_level', 'front_left_door_position']);
  assert.equal(BRIDGE_PATH, '/v2/vehicle-read');
  for (const query of Object.keys(QUERIES)) {
    const { result, seen } = await call(query, (id, q) => envelope(id, q, 'COMPLETE', SETS[query]()));
    assert.equal(seen.length, 1);
    const [sent] = seen;
    assert.equal(sent.method, 'POST');
    assert.equal(sent.url, '/v2/vehicle-read');
    assert.equal(sent.headers['content-type'], 'application/json');
    assert.equal(sent.headers.authorization, undefined);
    assert.equal(sent.headers.origin, undefined);
    const body = JSON.parse(sent.body);
    assert.deepEqual(Object.keys(body).sort(), ['query', 'remainingMs', 'requestId']);
    assert.equal(body.query, query);
    assert.match(body.requestId, UUID_RE);
    assert.ok(Number.isInteger(body.remainingMs) && body.remainingMs >= 9_000 && body.remainingMs <= 10_000);
    assert.deepEqual(result, {
      requestId: body.requestId, query, outcome: 'COMPLETE', sdkVersion: '2.3.2', items: SETS[query](),
    });
  }
});

test('an unknown query is rejected before any I/O', async () => {
  await withBridge((body, res) => json(res, 200, {}), async (fake) => {
    for (const query of ['vehicle_speed', 'DOOR_STATUS', '', undefined, null, { query: 'door_status' }]) {
      await assert.rejects(readVehicle(query, { port: fake.port }), TypeError);
    }
    assert.equal(fake.seen.length, 0);
  });
});

test('eight door items fit the response limit and keep each area and lock apart', async () => {
  const items = [...AREAS.map((a, i) => pos(a, i % 2)), ...AREAS.map((a, i) => lock(a, i % 2 === 0))];
  const { result } = await call('door_status', (id, q) => envelope(id, q, 'COMPLETE', items));
  assert.ok(Buffer.byteLength(JSON.stringify(envelope('x'.repeat(36), 'door_status', 'COMPLETE', items))) < MAX_RESPONSE_BYTES);
  assert.deepEqual(result.items.map((i) => [i.property, i.area, i.rawValue, i.meaning]), [
    ['DOOR_POSITION', 'ROW_1_LEFT', 0, 'closed'], ['DOOR_POSITION', 'ROW_1_RIGHT', 1, 'open'],
    ['DOOR_POSITION', 'ROW_2_LEFT', 0, 'closed'], ['DOOR_POSITION', 'ROW_2_RIGHT', 1, 'open'],
    ['DOOR_LOCK', 'ROW_1_LEFT', true, 'locked'], ['DOOR_LOCK', 'ROW_1_RIGHT', false, 'unlocked'],
    ['DOOR_LOCK', 'ROW_2_LEFT', true, 'locked'], ['DOOR_LOCK', 'ROW_2_RIGHT', false, 'unlocked'],
  ]);
});

test('items arrive in the fixed order even when Android lists them differently', async () => {
  const items = SETS.window_status().reverse();
  const { result } = await call('window_status', (id, q) => envelope(id, q, 'COMPLETE', items));
  assert.deepEqual(result.items.map((i) => i.area), AREAS);
});

test('window raw values and bounds are kept without a percent or door meaning', async () => {
  const items = AREAS.map((a, i) => win(a, [0, 1, 37, 100][i]));
  const { result } = await call('window_status', (id, q) => envelope(id, q, 'COMPLETE', items));
  assert.deepEqual(result.items.map((i) => i.rawValue), [0, 1, 37, 100]);
  for (const item of result.items) {
    assert.equal(item.meaning, null);
    assert.equal(item.unit, null);
    assert.equal(item.capability.minimum, 0);
    assert.equal(item.capability.maximum, 100);
  }
  for (const bad of [{ meaning: 'open' }, { meaning: 'closed' }, { unit: '%' }, { rawValue: 1.5 }, { rawValue: '37' }]) {
    const out = await call('window_status', (id, q) => envelope(id, q, 'COMPLETE', [win('ROW_1_LEFT', 37, bad), ...SETS.window_status().slice(1)]));
    assert.equal(out.result.code, 'INVALID_RESPONSE', JSON.stringify(bad));
  }
});

test('energy keeps the supported side only and fuel/EV percents carry the official unit', async () => {
  const items = [energy('FUEL_PERCENT', 63), bare(energy('EV_BATTERY_PERCENT', 0), 'READ_UNAVAILABLE', { capability: scalarCap({ access: 'NONE' }) })];
  const { result } = await call('energy_level', (id, q) => envelope(id, q, 'PARTIAL', items));
  assert.equal(result.outcome, 'PARTIAL');
  assert.equal(result.items[0].unit, '%');
  assert.equal(result.items[1].status, 'READ_UNAVAILABLE');
  assert.equal(result.items[1].rawValue, null);
  const other = [bare(energy('FUEL_PERCENT', 0), 'READ_UNAVAILABLE', { capability: scalarCap({ access: 'WRITE' }) }), energy('EV_BATTERY_PERCENT', 0)];
  assert.equal((await call('energy_level', (id, q) => envelope(id, q, 'PARTIAL', other))).result.outcome, 'PARTIAL');
  for (const bad of [{ unit: 'kWh' }, { unit: null }, { rawValue: 101 }, { rawValue: -1 }, { meaning: 'full' }]) {
    const out = await call('energy_level', (id, q) => envelope(id, q, 'PARTIAL', [energy('FUEL_PERCENT', 63, bad), items[1]]));
    assert.equal(out.result.code, 'INVALID_RESPONSE', JSON.stringify(bad));
  }
});

test('mixed items keep each reason and the overall outcome is derived from them', async () => {
  const lockBase = lock('ROW_1_LEFT', true);
  const items = [
    pos('ROW_1_LEFT', 1),
    bare(pos('ROW_1_RIGHT', 0), 'NO_VALUE', { receivedAt: T }),
    bare(pos('ROW_2_LEFT', 0), 'AREA_UNSUPPORTED', { capability: cap({ matchingAreas: 0, minimum: null, maximum: null }) }),
    bare(pos('ROW_2_RIGHT', 0), 'UNKNOWN_VALUE', { capability: cap({ maximum: 100 }), rawValue: 1, receivedAt: T }),
    bare(lockBase, 'READ_FAILED', { errorType: 'SecurityException' }),
    bare(lock('ROW_1_RIGHT', true), 'CAPABILITY_FAILED', { capability: null, errorType: 'IOException' }),
    bare(lock('ROW_2_LEFT', true), 'READ_UNAVAILABLE', { capability: cap({ access: 'NONE', minimum: null, maximum: null }) }),
    bare(lock('ROW_2_RIGHT', true), 'SUPPORT_UNCONFIRMED', { capability: cap({ matchingAreas: 2, minimum: null, maximum: null }) }),
  ];
  const { result } = await call('door_status', (id, q) => envelope(id, q, 'PARTIAL', items));
  assert.equal(result.outcome, 'PARTIAL');
  assert.deepEqual(result.items.map((i) => i.status), ['OK', 'NO_VALUE', 'AREA_UNSUPPORTED', 'UNKNOWN_VALUE', 'READ_FAILED',
    'CAPABILITY_FAILED', 'READ_UNAVAILABLE', 'SUPPORT_UNCONFIRMED']);
  assert.equal(result.items[1].rawValue, null);
  assert.equal(result.items[3].meaning, null);
  assert.equal(result.items[3].rawValue, 1);
  // The same items under the wrong overall outcome are not accepted.
  for (const outcome of ['COMPLETE', 'FAILED']) {
    assert.equal((await call('door_status', (id, q) => envelope(id, q, outcome, items))).result.code, 'INVALID_RESPONSE');
  }
  const none = SETS.door_status().map((i) => bare(i, 'NO_VALUE', { receivedAt: T }));
  assert.equal((await call('door_status', (id, q) => envelope(id, q, 'FAILED', none))).result.outcome, 'FAILED');
  for (const outcome of ['COMPLETE', 'PARTIAL']) {
    assert.equal((await call('door_status', (id, q) => envelope(id, q, outcome, none))).result.code, 'INVALID_RESPONSE');
  }
  assert.equal((await call('door_status', (id, q) => envelope(id, q, 'PARTIAL', SETS.door_status()))).result.code, 'INVALID_RESPONSE');
});

test('timeout and cancel from Android carry no items and never a usable payload', async () => {
  for (const outcome of ['TIMEOUT', 'CANCELLED']) {
    const { result } = await call('window_status', (id, q) => envelope(id, q, outcome, []));
    assert.equal(result.ok, false);
    assert.equal(result.source, 'android');
    assert.equal(result.code, outcome);
    assert.equal(result.query, 'window_status');
    assert.equal('items' in result, false);
    const leaked = await call('window_status', (id, q) => envelope(id, q, outcome, SETS.window_status()));
    assert.equal(leaked.result.code, 'INVALID_RESPONSE');
  }
});

test('corrupt, mismatched or oversized responses are INVALID_RESPONSE', async () => {
  const good = () => SETS.door_status();
  const door = (mutate) => (id, q) => envelope(id, q, 'COMPLETE', mutate(good()));
  const withItem = (index, o) => door((items) => items.map((item, i) => (i === index ? { ...item, ...o } : item)));
  const cases = [
    ['other requestId', (id, q) => envelope('0f8fad5b-d9cb-469f-a165-70867728950e', q, 'COMPLETE', good())],
    ['other query', (id) => envelope(id, 'window_status', 'COMPLETE', good())],
    ['sdk version', (id, q) => envelope(id, q, 'COMPLETE', good(), { sdkVersion: '2.3.1' })],
    ['unknown outcome', (id, q) => envelope(id, q, 'OK', good())],
    ['extra envelope key', (id, q) => envelope(id, q, 'COMPLETE', good(), { extra: true })],
    ['missing envelope key', (id, q) => { const e = envelope(id, q, 'COMPLETE', good()); delete e.sdkVersion; return e; }],
    ['items not an array', (id, q) => envelope(id, q, 'COMPLETE', { 0: 1 })],
    ['missing item', door((items) => items.slice(1))],
    ['duplicate item', door((items) => [...items.slice(0, 7), items[0]])],
    ['extra item', door((items) => [...items, pos('ROW_1_LEFT', 0)])],
    ['extra item for another area', door((items) => [...items, win('ROW_1_LEFT', 1)])],
    ['other property', door((items) => items.map((item, i) => (i === 0 ? win('ROW_1_LEFT', 1) : item)))],
    ['other area', door((items) => items.map((item, i) => (i === 0 ? pos('ROW_1_LEFT', 0, { area: 'ROW_3_LEFT' }) : item)))],
    ['non-string area', withItem(0, { area: 7 })],
    ['item extra key', withItem(0, { extra: 1 })],
    ['item missing key', door((items) => items.map((item, i) => { if (i === 0) delete item.unit; return item; }))],
    ['raw does not match meaning', withItem(0, { rawValue: 1, meaning: 'closed' })],
    ['door raw 2', withItem(0, { rawValue: 2, meaning: 'open' })],
    ['door bounds not 0..1', withItem(0, { capability: cap({ maximum: 100 }) })],
    ['door bounds missing', withItem(0, { capability: cap({ minimum: null }) })],
    ['capability extra key', withItem(0, { capability: { ...cap(), extra: 1 } })],
    ['capability matching 2', withItem(0, { capability: cap({ matchingAreas: 2 }) })],
    ['capability unreadable', withItem(0, { capability: cap({ access: 'NONE' }) })],
    ['capability non-finite bound', withItem(0, { capability: cap({ maximum: '1' }) })],
    ['lock raw not boolean', withItem(4, { rawValue: 1, meaning: 'locked' })],
    ['lock meaning mismatch', withItem(4, { rawValue: true, meaning: 'unlocked' })],
    ['lock with bounds', withItem(4, { capability: cap() })],
    ['unit on a door', withItem(0, { unit: '%' })],
    ['bad receivedAt', withItem(0, { receivedAt: 'yesterday' })],
    ['missing receivedAt on OK', withItem(0, { receivedAt: null })],
    ['unknown status', withItem(0, { status: 'CLOSED' })],
    ['OK with error type', withItem(0, { errorType: 'IOException' })],
    ['bad error type', door((items) => items.map((item, i) => (i === 0 ? bare(item, 'READ_FAILED', { errorType: 'bad name' }) : item)))],
    ['NO_VALUE with a value', door((items) => items.map((item, i) => (i === 0 ? { ...item, status: 'NO_VALUE', meaning: null } : item)))],
    ['AREA_UNSUPPORTED claiming a match', door((items) => items.map((item, i) => (i === 0 ? bare(item, 'AREA_UNSUPPORTED') : item)))],
    ['v1 success envelope', (id) => ({ requestId: id, ok: true, area: 'ROW_1_LEFT', status: 'CLOSED', state: 'closed', rawValue: 0,
      receivedAt: T, sdkVersion: '2.3.2', capability: cap() })],
    ['v1 failure envelope', (id) => ({ requestId: id, ok: false, area: 'ROW_1_LEFT', code: 'NO_VALUE', status: 'NO_VALUE',
      sdkVersion: '2.3.2', capability: null, errorType: null, observation: null })],
    ['not an object', () => '[1]'],
    ['not json', () => '{not json'],
    ['non-finite number text', () => '{"requestId":"x","items":[NaN]}'],
  ];
  for (const [name, make] of cases) {
    const { result, seen } = await call('door_status', make);
    assert.equal(result.code, 'INVALID_RESPONSE', name);
    assert.equal(result.ok, false, name);
    assert.equal(result.source, 'pc', name);
    assert.equal('items' in result, false, name);
    assert.equal(seen.length, 1, name);
  }
  const wrongType = await withBridge((body, res) => json(res, 200, envelope(body.requestId, body.query, 'COMPLETE', good()), 'text/plain'),
    (fake) => readVehicle('door_status', { port: fake.port }));
  assert.equal(wrongType.code, 'INVALID_RESPONSE');
  const big = await call('door_status', (id, q) => envelope(id, q, 'COMPLETE', good(), { pad: 'x'.repeat(17 * 1024) }));
  assert.equal(big.result.code, 'INVALID_RESPONSE');
});

test('HTTP status and body combinations: only the documented pairs are accepted', async () => {
  const wrongCode = (id) => ({ requestId: id, ok: false, code: 'BUSY' });
  const cases = [
    [409, (id) => ({ requestId: id, ok: false, code: 'BUSY' }), 'BUSY', 'android'],
    [503, (id) => ({ requestId: id, ok: false, code: 'APP_NOT_READY' }), 'APP_NOT_READY', 'android'],
    [403, () => ({ ok: false, code: 'REQUEST_FORBIDDEN' }), 'REQUEST_FORBIDDEN', 'android'],
    [400, () => ({ ok: false, code: 'INVALID_REQUEST' }), 'INVALID_REQUEST', 'android'],
    [413, () => ({ ok: false, code: 'INVALID_REQUEST' }), 'INVALID_REQUEST', 'android'],
    [415, () => ({ ok: false, code: 'INVALID_REQUEST' }), 'INVALID_REQUEST', 'android'],
    [409, (id) => ({ requestId: id, ok: true, code: 'BUSY' }), 'INVALID_RESPONSE', 'pc'],
    [409, () => ({ requestId: '0f8fad5b-d9cb-469f-a165-70867728950e', ok: false, code: 'BUSY' }), 'INVALID_RESPONSE', 'pc'],
    [503, wrongCode, 'INVALID_RESPONSE', 'pc'],
    [401, () => ({ ok: false, code: 'UNAUTHORIZED' }), 'INVALID_RESPONSE', 'pc'],
    [403, () => ({ ok: false, code: 'UNAUTHORIZED' }), 'INVALID_RESPONSE', 'pc'],
    [403, () => ({ ok: false, code: 'INVALID_REQUEST' }), 'INVALID_RESPONSE', 'pc'],
    [400, () => ({ ok: false, code: 'REQUEST_FORBIDDEN' }), 'INVALID_RESPONSE', 'pc'],
    [500, () => ({ ok: false }), 'INVALID_RESPONSE', 'pc'],
    [200, (id, q) => envelope(id, q, 'COMPLETE', SETS.door_status()), 'ok', 'pc'],
  ];
  for (const [status, make, code, source] of cases) {
    const { result, seen } = await call('energy_level', (id, q) => make(id, q), status);
    assert.equal(seen.length, 1);
    if (code === 'ok') {
      // A 200 for another query is rejected because the echoed query/items differ.
      assert.equal(result.code, 'INVALID_RESPONSE');
    } else {
      assert.equal(result.code, code, `${status} ${code}`);
      assert.equal(result.source, source);
      assert.equal(result.query, 'energy_level');
      if (code === 'BUSY' || code === 'APP_NOT_READY') assert.match(result.requestId, UUID_RE);
    }
  }
  const redirect = await withBridge((body, res) => { res.writeHead(302, { Location: 'http://127.0.0.1:1/' }); res.end(); },
    (fake) => readVehicle('energy_level', { port: fake.port }));
  assert.equal(redirect.code, 'INVALID_RESPONSE');
  const reset = await withBridge((body, res, req) => req.socket.destroy(), (fake) => readVehicle('energy_level', { port: fake.port }));
  assert.equal(reset.code, 'ANDROID_UNREACHABLE');
  const closed = await bridge(() => {});
  const { port } = closed;
  await closed.close();
  assert.equal((await readVehicle('energy_level', { port })).code, 'ANDROID_UNREACHABLE');
});

test('a leftover VEHICLE_BRIDGE_TOKEN in the environment never becomes authentication', async () => {
  const previous = process.env.VEHICLE_BRIDGE_TOKEN;
  process.env.VEHICLE_BRIDGE_TOKEN = 'A'.repeat(43);
  try {
    const { result, seen } = await call('window_status', (id, q) => envelope(id, q, 'COMPLETE', SETS.window_status()));
    assert.equal(result.outcome, 'COMPLETE');
    assert.equal(seen[0].headers.authorization, undefined);
  } finally {
    if (previous === undefined) delete process.env.VEHICLE_BRIDGE_TOKEN; else process.env.VEHICLE_BRIDGE_TOKEN = previous;
  }
});

test('slow headers or body end as one TIMEOUT at the single deadline, with one HTTP attempt', async () => {
  const stalls = [
    () => {},
    (res) => { res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': 5_000 }); res.write('{'); },
  ];
  for (const stall of stalls) {
    await withBridge((body, res) => stall(res), async (fake) => {
      const started = performance.now();
      const result = await readVehicle('door_status', { port: fake.port, deadlineMs: 300 });
      const elapsed = performance.now() - started;
      assert.equal(result.code, 'TIMEOUT');
      assert.equal(result.source, 'pc');
      assert.equal(result.query, 'door_status');
      assert.ok(elapsed >= 280 && elapsed < 1_500, `elapsed ${elapsed}`);
      assert.equal(fake.seen.length, 1);
      assert.ok(JSON.parse(fake.seen[0].body).remainingMs <= 300);
    });
  }
});

test('the deadline starts at the handler entry and only the remaining budget goes to Android', async () => {
  let clock = 0;
  await withBridge((body, res) => json(res, 200, envelope(body.requestId, body.query, 'COMPLETE', SETS.energy_level())), async (fake) => {
    const result = await readVehicle('energy_level', { port: fake.port, now: () => clock, startedAt: -4_000 });
    assert.equal(result.outcome, 'COMPLETE');
    assert.equal(JSON.parse(fake.seen[0].body).remainingMs, 6_000);
  });
  await withBridge((body, res) => json(res, 200, envelope(body.requestId, body.query, 'COMPLETE', SETS.energy_level())), async (fake) => {
    const result = await readVehicle('energy_level', { port: fake.port, now: () => clock, startedAt: -10_000 });
    assert.equal(result.code, 'TIMEOUT');
    assert.equal(fake.seen.length, 0, 'an exhausted budget never reaches Android');
  });
});

test('a valid answer that arrives after the deadline is discarded', async () => {
  let clock = 0;
  await withBridge((body, res) => { clock = 10_000; json(res, 200, envelope(body.requestId, body.query, 'COMPLETE', SETS.window_status())); }, async (fake) => {
    const result = await readVehicle('window_status', { port: fake.port, now: () => clock });
    assert.equal(result.code, 'TIMEOUT');
    assert.equal(result.ok, false);
    assert.equal('items' in result, false);
  });
});

test('caller cancellation ends only its own request and an early abort does no I/O', async () => {
  const held = [];
  await withBridge((body, res) => held.push([body, res]), async (fake) => {
    const cancel = new AbortController();
    const first = readVehicle('energy_level', { port: fake.port, signal: cancel.signal });
    const second = readVehicle('window_status', { port: fake.port });
    while (held.length < 2) await new Promise((resolve) => setTimeout(resolve, 5));
    cancel.abort();
    const cancelled = await first;
    assert.equal(cancelled.code, 'CANCELLED');
    assert.equal(cancelled.query, 'energy_level');
    for (const [body, res] of held) if (!res.destroyed) json(res, 200, envelope(body.requestId, body.query, 'COMPLETE', SETS[body.query]()));
    const other = await second;
    assert.equal(other.outcome, 'COMPLETE');
    assert.equal(other.query, 'window_status');
    assert.equal(fake.seen.length, 2);
  });
  const aborted = new AbortController();
  aborted.abort();
  assert.equal((await readVehicle('door_status', { port: 1, signal: aborted.signal })).code, 'CANCELLED');
});
