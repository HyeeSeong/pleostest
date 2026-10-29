import { request as httpRequest } from 'node:http';
import { randomUUID } from 'node:crypto';

export const BRIDGE_HOST = '127.0.0.1';
export const BRIDGE_PORT = 18765;
export const BRIDGE_PATH = '/v2/vehicle-read';
export const TOTAL_DEADLINE_MS = 10_000;
export const MAX_RESPONSE_BYTES = 16 * 1024;
export const SDK_VERSION = '2.3.2';

const AREAS = Object.freeze(['ROW_1_LEFT', 'ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_RIGHT']);
const forAreas = (property) => AREAS.map((area) => ({ property, area }));

/** The exact item set Android must answer for each fixed query, in presentation order. */
export const QUERIES = Object.freeze({
  door_status: Object.freeze([...forAreas('DOOR_POSITION'), ...forAreas('DOOR_LOCK')]),
  window_status: Object.freeze(forAreas('WINDOW_POSITION')),
  energy_level: Object.freeze([{ property: 'FUEL_PERCENT', area: null }, { property: 'EV_BATTERY_PERCENT', area: null }]),
  front_left_door_position: Object.freeze([{ property: 'DOOR_POSITION', area: 'ROW_1_LEFT' }]),
});

const READABLE = ['READ', 'READ_WRITE'];
const SCALARS = ['FUEL_PERCENT', 'EV_BATTERY_PERCENT'];
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
const CLASS_RE = /^[A-Za-z_$][A-Za-z0-9_$]{0,99}$/;
const JSON_TYPE_RE = /^application\/json(\s*;\s*charset="?utf-8"?)?$/i;
const TIMED_OUT = Symbol('timeout');
const CANCELLED = Symbol('cancelled');

export const pcFailure = (code, requestId, query) => ({ ok: false, source: 'pc', code, requestId, query });
const androidFailure = (code, requestId, query) => ({ ok: false, source: 'android', code, requestId, query });
const invalid = (requestId, query) => pcFailure('INVALID_RESPONSE', requestId, query);

/**
 * Runs one fixed query: exactly one HTTP request to the Android bridge under one monotonic deadline that
 * starts at `startedAt` (the handler entry). `deadlineMs`, `port`, `now` and `startedAt` exist for tests and
 * the fixed server configuration; they are never taken from model arguments. Returns a validated DTO.
 */
export async function readVehicle(query, {
  signal, port = BRIDGE_PORT, deadlineMs = TOTAL_DEADLINE_MS, now = () => performance.now(), startedAt = now(),
} = {}) {
  if (typeof query !== 'string' || !Object.hasOwn(QUERIES, query)) throw new TypeError('unknown vehicle query');
  const requestId = randomUUID();
  if (signal?.aborted) return pcFailure('CANCELLED', requestId, query);
  const remaining = Math.floor(deadlineMs - (now() - startedAt));
  if (remaining < 1) return pcFailure('TIMEOUT', requestId, query);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(TIMED_OUT), remaining);
  const onCancel = () => controller.abort(CANCELLED);
  signal?.addEventListener('abort', onCancel, { once: true });
  try {
    const reply = await exchange({ port, requestId, query, remainingMs: remaining, signal: controller.signal });
    if (controller.signal.aborted || reply.aborted) return aborted(controller.signal, requestId, query);
    if (now() - startedAt >= deadlineMs) return pcFailure('TIMEOUT', requestId, query);
    if (reply.unreachable) return pcFailure('ANDROID_UNREACHABLE', requestId, query);
    if (reply.invalid) return invalid(requestId, query);
    const result = validate(reply.status, reply.body, requestId, query);
    if (now() - startedAt >= deadlineMs) return pcFailure('TIMEOUT', requestId, query);
    return result;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onCancel);
  }
}

function aborted(signal, requestId, query) {
  return pcFailure(signal.reason === CANCELLED ? 'CANCELLED' : 'TIMEOUT', requestId, query);
}

function exchange({ port, requestId, query, remainingMs, signal }) {
  const payload = JSON.stringify({ requestId, remainingMs, query });
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => { if (!settled) { settled = true; resolve(value); } };
    const req = httpRequest({
      host: BRIDGE_HOST, port, path: BRIDGE_PATH, method: 'POST', agent: false, signal,
      headers: {
        Host: `${BRIDGE_HOST}:${port}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    }, (res) => {
      if (!JSON_TYPE_RE.test(res.headers['content-type'] ?? '')) {
        res.destroy();
        finish({ invalid: true });
        return;
      }
      const chunks = [];
      let size = 0;
      res.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_RESPONSE_BYTES) {
          res.destroy();
          finish({ invalid: true });
        } else {
          chunks.push(chunk);
        }
      });
      res.on('end', () => finish({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
      res.on('error', () => finish(signal.aborted ? { aborted: true } : { invalid: true }));
    });
    req.on('error', () => finish(signal.aborted ? { aborted: true } : { unreachable: true }));
    req.end(payload);
  });
}

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value);
const hasExactKeys = (value, keys) =>
  isObject(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));
const isTime = (value) => typeof value === 'string' && ISO_RE.test(value) && Number.isFinite(Date.parse(value));
const isBound = (value) => value === null || (typeof value === 'number' && Number.isFinite(value));
const isRaw = (value) => Number.isInteger(value) || typeof value === 'boolean';

function capability(value) {
  if (!hasExactKeys(value, ['access', 'matchingAreas', 'reportedAreas', 'minimum', 'maximum'])) return null;
  const { access, matchingAreas, reportedAreas, minimum, maximum } = value;
  if (typeof access !== 'string' || !(matchingAreas === null || Number.isInteger(matchingAreas))) return null;
  if (!(reportedAreas === null || typeof reportedAreas === 'string') || !isBound(minimum) || !isBound(maximum)) return null;
  return { access, matchingAreas, reportedAreas, minimum, maximum };
}

function validate(status, text, requestId, query) {
  let body;
  try { body = JSON.parse(text); } catch { return invalid(requestId, query); }
  if (!isObject(body)) return invalid(requestId, query);

  switch (status) {
    case 200:
      return outcome(body, requestId, query);
    case 409:
    case 503: {
      const code = status === 409 ? 'BUSY' : 'APP_NOT_READY';
      const ok = hasExactKeys(body, ['requestId', 'ok', 'code']) && body.requestId === requestId &&
        body.ok === false && body.code === code;
      return ok ? androidFailure(code, requestId, query) : invalid(requestId, query);
    }
    case 400:
    case 413:
    case 415:
    case 403: {
      const code = status === 403 ? 'REQUEST_FORBIDDEN' : 'INVALID_REQUEST';
      const ok = hasExactKeys(body, ['ok', 'code']) && body.ok === false && body.code === code;
      return ok ? androidFailure(code, null, query) : invalid(requestId, query);
    }
    default:
      return invalid(requestId, query);
  }
}

function outcome(body, requestId, query) {
  if (!hasExactKeys(body, ['requestId', 'query', 'outcome', 'sdkVersion', 'items']) || body.requestId !== requestId ||
      body.query !== query || body.sdkVersion !== SDK_VERSION || !Array.isArray(body.items)) {
    return invalid(requestId, query);
  }
  if (body.outcome === 'TIMEOUT' || body.outcome === 'CANCELLED') {
    return body.items.length === 0 ? androidFailure(body.outcome, requestId, query) : invalid(requestId, query);
  }
  const expected = QUERIES[query];
  if (!['COMPLETE', 'PARTIAL', 'FAILED'].includes(body.outcome) || body.items.length !== expected.length) {
    return invalid(requestId, query);
  }
  const items = [];
  for (const key of expected) {
    const matches = body.items.filter((item) => isObject(item) && item.property === key.property && item.area === key.area);
    const item = matches.length === 1 ? validItem(matches[0], key) : null;
    if (!item) return invalid(requestId, query);
    items.push(item);
  }
  const successes = items.filter((item) => item.status === 'OK').length;
  const derived = successes === items.length ? 'COMPLETE' : successes === 0 ? 'FAILED' : 'PARTIAL';
  if (derived !== body.outcome) return invalid(requestId, query);
  return { requestId, query, outcome: derived, sdkVersion: SDK_VERSION, items };
}

/** Whether (capability, raw) is a value whose meaning or unit is confirmed for this property. */
function confirmed(property, cap, raw) {
  switch (property) {
    case 'DOOR_POSITION': return cap.minimum === 0 && cap.maximum === 1 && (raw === 0 || raw === 1);
    case 'DOOR_LOCK': return typeof raw === 'boolean';
    case 'WINDOW_POSITION': return Number.isInteger(raw);
    default: return Number.isInteger(raw) && raw >= 0 && raw <= 100;
  }
}

function expectedMeaning(property, raw) {
  if (property === 'DOOR_POSITION') return raw === 0 ? 'closed' : 'open';
  if (property === 'DOOR_LOCK') return raw ? 'locked' : 'unlocked';
  return null;
}

function validItem(item, key) {
  const fields = ['property', 'area', 'status', 'capability', 'rawValue', 'meaning', 'unit', 'receivedAt', 'errorType'];
  if (!hasExactKeys(item, fields)) return null;
  const { property } = key;
  const scalar = SCALARS.includes(property);
  const cap = item.capability === null ? null : capability(item.capability);
  if (item.capability !== null && !cap) return null;
  if (cap) {
    // Scalars have no area; locks have no bounds; position bounds are both present or both absent for OK.
    if (scalar && (cap.matchingAreas !== null || cap.reportedAreas !== null || cap.minimum !== null || cap.maximum !== null)) return null;
    if (!scalar && (cap.matchingAreas === null || cap.reportedAreas === null)) return null;
    if (property === 'DOOR_LOCK' && (cap.minimum !== null || cap.maximum !== null)) return null;
  }
  const { status, rawValue, meaning, unit, receivedAt, errorType } = item;
  const readable = cap !== null && READABLE.includes(cap.access);
  const noValue = rawValue === null && meaning === null && unit === null && receivedAt === null;
  const received = isTime(receivedAt);
  const errorOk = errorType !== null && typeof errorType === 'string' && CLASS_RE.test(errorType);
  const boundsOk = cap && typeof cap.minimum === 'number' && typeof cap.maximum === 'number' && cap.minimum <= cap.maximum;
  let ok;
  switch (status) {
    case 'OK':
      ok = readable && received && errorType === null && (scalar || cap.matchingAreas === 1) &&
        (property === 'DOOR_POSITION' ? cap.minimum === 0 && cap.maximum === 1
          : property === 'WINDOW_POSITION' ? boundsOk : true) &&
        isRaw(rawValue) && confirmed(property, cap, rawValue) &&
        meaning === expectedMeaning(property, rawValue) && unit === (scalar ? '%' : null);
      break;
    case 'NO_VALUE':
      ok = cap !== null && received && rawValue === null && meaning === null && unit === null && errorType === null;
      break;
    case 'UNKNOWN_VALUE':
      ok = cap !== null && received && isRaw(rawValue) && !confirmed(property, cap, rawValue) &&
        meaning === null && unit === null && errorType === null;
      break;
    case 'CAPABILITY_FAILED':
      ok = cap === null && noValue && errorOk;
      break;
    case 'READ_UNAVAILABLE':
      ok = cap !== null && !readable && noValue && errorType === null;
      break;
    case 'AREA_UNSUPPORTED':
      ok = !scalar && readable && cap.matchingAreas === 0 && noValue && errorType === null;
      break;
    case 'SUPPORT_UNCONFIRMED':
      ok = !scalar && readable && noValue && errorType === null &&
        (cap.matchingAreas !== 1 || (property !== 'DOOR_LOCK' && !boundsOk));
      break;
    case 'READ_FAILED':
      ok = readable && noValue && errorOk;
      break;
    default:
      ok = false;
  }
  if (!ok) return null;
  return { property, area: key.area, status, capability: cap, rawValue, meaning, unit, receivedAt, errorType };
}
