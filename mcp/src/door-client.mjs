import { BRIDGE_PORT, TOTAL_DEADLINE_MS, SDK_VERSION, readVehicle } from './vehicle-client.mjs';

export { BRIDGE_PORT, TOTAL_DEADLINE_MS, SDK_VERSION };
export const AREA = 'ROW_1_LEFT';
export const QUERY = 'front_left_door_position';
export const VEHICLE_FAILURES = Object.freeze([
  'CAPABILITY_FAILED', 'READ_UNAVAILABLE', 'AREA_UNSUPPORTED', 'SUPPORT_UNCONFIRMED',
  'NO_VALUE', 'UNKNOWN_VALUE', 'READ_FAILED', 'TIMEOUT', 'CANCELLED',
]);

/**
 * The original front-left door result, derived from the one validated v2 item for exactly that door's
 * position. Another door, a lock or an extra item is never projected onto it: such replies are
 * INVALID_RESPONSE in the shared client.
 */
export async function readFrontLeftDoor(options = {}) {
  return toScalarResult(await readVehicle(QUERY, options));
}

function toScalarResult(dto) {
  if (dto.ok === false) {
    if (dto.source === 'android' && (dto.code === 'TIMEOUT' || dto.code === 'CANCELLED')) {
      return {
        ok: false, source: 'android', code: dto.code, requestId: dto.requestId, area: AREA, status: dto.code,
        sdkVersion: SDK_VERSION, capability: null, errorType: null, observation: null,
      };
    }
    return { ok: false, source: dto.source, code: dto.code, requestId: dto.requestId, area: AREA };
  }
  const [item] = dto.items;
  if (item.status === 'OK') {
    return {
      ok: true, requestId: dto.requestId, area: AREA, status: item.meaning === 'open' ? 'OPEN' : 'CLOSED',
      state: item.meaning, rawValue: item.rawValue, receivedAt: item.receivedAt, sdkVersion: SDK_VERSION,
      capability: item.capability,
    };
  }
  return {
    ok: false, source: 'android', code: item.status, requestId: dto.requestId, area: AREA, status: item.status,
    sdkVersion: SDK_VERSION, capability: item.capability, errorType: item.errorType,
    observation: item.receivedAt === null ? null : { rawValue: item.rawValue, receivedAt: item.receivedAt },
  };
}
