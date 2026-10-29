#!/usr/bin/env node
// stdout carries MCP messages only; diagnostics go to stderr as fixed codes only.
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod';
import { AREA, readFrontLeftDoor } from './door-client.mjs';
import { BRIDGE_PORT, TOTAL_DEADLINE_MS, pcFailure, readVehicle } from './vehicle-client.mjs';

const RECEIPT_NOTE = '수신 시각은 앱이 콜백을 받은 시각이며 차량 측 측정 시각이나 값의 최신성을 보장하지 않습니다.';
const SNAPSHOT_NOTE = '항목들은 순차로 읽었으며 동시에 읽은 스냅샷이 아닙니다.';
const LOCK_NOTE = '잠김/잠금 해제는 SDK 불리언을 getDoorLockState 이름 기준(true=잠김)으로 해석한 것이며 공식 문서 본문에서 값 의미를 직접 확인하지는 못했습니다.';
const NO_GUESS = '확인하지 못한 항목의 값을 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다.';
const BUILD_NOTE = 'APK와 서버 파일이 같은 v2 빌드인지 확인하세요(예전 토큰/v1 빌드와 섞이면 거부됩니다).';

const ERROR_TEXT = {
  CAPABILITY_FAILED: '지원 확인에 실패했습니다. Service 연결·권한·지원 여부를 확인하지 못했습니다.',
  READ_UNAVAILABLE: 'capability가 읽기 접근을 허용하지 않았습니다.',
  AREA_UNSUPPORTED: 'capability에 앞좌측 문 단독 영역이 없습니다.',
  SUPPORT_UNCONFIRMED: '영역 또는 값 범위를 확정할 수 없어 값을 읽지 않았습니다.',
  NO_VALUE: '콜백을 받았지만 값이 null입니다.',
  UNKNOWN_VALUE: '원시값을 받았지만 열림·닫힘 의미를 확인할 수 없습니다.',
  READ_FAILED: '값 조회에 실패했습니다.',
  TIMEOUT: '총 10초 안에 결과를 확인하지 못했습니다. 늦게 도착한 결과는 버립니다.',
  CANCELLED: '조회가 취소되었습니다.',
  BUSY: '다른 조회가 진행 중이라 이번 요청은 시작하지 않았습니다. 기존 조회는 계속됩니다.',
  APP_NOT_READY: 'Android 앱 화면이 조회를 받을 수 있는 상태가 아닙니다. 앱 화면을 연 상태인지 확인하세요.',
  REQUEST_FORBIDDEN: 'Android 앱이 요청의 Host/Origin을 거부했습니다. 브라우저 등 다른 경로가 아닌 이 MCP 서버의 직접 요청인지, 서버 파일과 APK가 같은 빌드인지 확인하세요.',
  INVALID_REQUEST: `Android 앱이 요청 형식을 거부했습니다. ${BUILD_NOTE}`,
  ANDROID_UNREACHABLE: 'Android 앱에 연결하지 못했습니다. adb forward와 앱의 AI 연결 서비스(알림)가 실행 중인지 확인하세요.',
  INVALID_RESPONSE: `Android 앱의 응답을 검증하지 못해 결과로 쓰지 않았습니다. ${BUILD_NOTE}`,
};

const ITEM_TEXT = {
  NO_VALUE: '콜백을 받았지만 값이 null입니다.',
  UNKNOWN_VALUE: '원시값을 받았지만 의미나 단위를 확인할 수 없습니다.',
  CAPABILITY_FAILED: '지원 확인에 실패했습니다(Service 연결·권한·지원 여부 미확인).',
  READ_UNAVAILABLE: 'capability가 읽기 접근을 허용하지 않았습니다.',
  AREA_UNSUPPORTED: 'capability에 이 영역 단독 항목이 없습니다.',
  SUPPORT_UNCONFIRMED: '영역 또는 값 범위를 확정할 수 없어 값을 읽지 않았습니다.',
  READ_FAILED: '값 조회에 실패했습니다.',
};

const PROPERTY_LABEL = {
  DOOR_POSITION: '문 위치', DOOR_LOCK: '문 잠금', WINDOW_POSITION: '창문 위치',
  FUEL_PERCENT: '연료 잔량', EV_BATTERY_PERCENT: 'EV 배터리 잔량',
};
const AREA_LABEL = { ROW_1_LEFT: '앞좌측', ROW_1_RIGHT: '앞우측', ROW_2_LEFT: '뒤좌측', ROW_2_RIGHT: '뒤우측' };

/** The fixed tool table. Names, queries and descriptions are never model input. */
export const TOOLS = Object.freeze([
  {
    name: 'get_door_status', query: 'door_status', label: '문 상태',
    title: '문 4영역 위치·잠금 조회',
    description: 'Pleos 에뮬레이터 차량의 문 4영역(앞좌/앞우/뒤좌/뒤우) 열림·닫힘 위치와, 지원되는 경우 잠금 상태를 한 번 읽습니다. ' +
      '인자는 없습니다. 항목별로 확인 여부가 다를 수 있으니 확인되지 않은 항목은 추정하지 말고 그대로 전달하세요.',
  },
  {
    name: 'get_window_status', query: 'window_status', label: '창문 상태',
    title: '창문 4영역 위치 조회',
    description: 'Pleos 에뮬레이터 차량의 창문 4개 단독 영역 위치를 한 번 읽습니다. 원시값과 capability 범위를 그대로 전하며 ' +
      '퍼센트나 개폐 의미로 변환하지 않습니다. 인자는 없습니다. 확인되지 않은 항목은 추정하지 마세요.',
  },
  {
    name: 'get_energy_level', query: 'energy_level', label: '에너지 잔량',
    title: '연료·EV 배터리 잔량 조회',
    description: 'Pleos 에뮬레이터 차량의 연료 잔량(%)과 EV 배터리 잔량(%) 중 지원되는 항목을 한 번 읽습니다. 인자는 없습니다. ' +
      '지원되지 않거나 확인되지 않은 항목은 추정하지 말고 그대로 전달하세요.',
  },
  {
    name: 'get_front_left_door_position', query: 'front_left_door_position', label: '앞좌측 문', legacy: true,
    title: '앞좌측 문 열림·닫힘 조회',
    description: 'Pleos 에뮬레이터 차량의 앞좌측(운전석) 문 열림·닫힘(Door Position, ROW_1_LEFT)을 한 번 읽습니다. ' +
      '인자는 없습니다. 실패하면 상태를 추정하지 말고 오류를 그대로 전달하세요.',
  },
]);
export const TOOL_NAMES = Object.freeze(TOOLS.map((tool) => tool.name));

const textResult = (lines, structuredContent, isError) => ({
  content: [{ type: 'text', text: lines.join('\n') }], structuredContent, ...(isError ? { isError: true } : {}),
});

/** Original front-left scalar result; both MCP outputs come from the one validated result. */
export function toToolResult(result) {
  if (result.ok === true) {
    const label = result.state === 'closed' ? '닫힘' : '열림';
    return textResult([
      `앞좌측 문(${AREA}): ${label} (${result.state}).`,
      `이번 콜백 원시값 ${result.rawValue}, capability 범위 ${result.capability.minimum}~${result.capability.maximum},` +
        ` 접근 ${result.capability.access}, Vehicle SDK ${result.sdkVersion}.`,
      `콜백 수신 시각(UTC): ${result.receivedAt}. ${RECEIPT_NOTE}`,
    ], result, false);
  }
  const lines = [
    `앞좌측 문(${AREA}) 상태를 확인하지 못했습니다 [${result.code}, ${result.source === 'android' ? 'Android' : 'PC'}].`,
    ERROR_TEXT[result.code] ?? '알 수 없는 오류입니다.',
  ];
  if (result.observation) {
    lines.push(`관측된 원시값: ${result.observation.rawValue ?? 'null'} (정상 상태로 해석하지 않음).` +
      ` 콜백 수신 시각(UTC): ${result.observation.receivedAt}. ${RECEIPT_NOTE}`);
  }
  if (result.errorType) lines.push(`오류 유형: ${result.errorType}`);
  lines.push('문 상태를 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다.');
  return textResult(lines, result, true);
}

function itemLine(item) {
  const name = `[${PROPERTY_LABEL[item.property]}]${item.area ? ` ${AREA_LABEL[item.area]}(${item.area})` : ''}`;
  if (item.status !== 'OK') {
    const parts = [`${name}: 확인 실패 [${item.status}] ${ITEM_TEXT[item.status]}`];
    if (item.receivedAt !== null) parts.push(`관측 원시값 ${item.rawValue ?? 'null'}, 콜백 수신 ${item.receivedAt}.`);
    if (item.errorType !== null) parts.push(`오류 유형: ${item.errorType}`);
    return parts.join(' ');
  }
  const range = `capability 범위 ${item.capability.minimum}~${item.capability.maximum}`;
  const value = {
    DOOR_POSITION: `${item.meaning === 'open' ? '열림' : '닫힘'} (원시값 ${item.rawValue}, ${range})`,
    DOOR_LOCK: `${item.meaning === 'locked' ? '잠김' : '잠금 해제'} (원시값 ${item.rawValue})`,
    WINDOW_POSITION: `위치 원시값 ${item.rawValue} (${range}). %나 개폐 정도로 변환하지 않았습니다.`,
  }[item.property] ?? `${item.rawValue}% (원시값 ${item.rawValue})`;
  return `${name}: ${value} 콜백 수신 ${item.receivedAt}.`;
}

const OUTCOME_TEXT = {
  COMPLETE: '모든 항목을 확인했습니다.',
  PARTIAL: '일부 항목만 확인했습니다. 항목별 결과를 그대로 전달하세요.',
  FAILED: '확인된 항목이 없습니다.',
};

/** Multi-item result: COMPLETE is a success, PARTIAL/FAILED are errors that still keep every observation. */
export function toStatusToolResult(dto, tool) {
  if (dto.ok === false) {
    return textResult([
      `${tool.label} 조회를 확인하지 못했습니다 [${dto.code}, ${dto.source === 'android' ? 'Android' : 'PC'}].`,
      ERROR_TEXT[dto.code] ?? '알 수 없는 오류입니다.',
      '일부 항목을 받았더라도 사용하지 않았으며 값을 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다.',
    ], dto, true);
  }
  const lines = [`${tool.label} (${tool.query}) 결과 ${dto.outcome}: ${OUTCOME_TEXT[dto.outcome]} Vehicle SDK ${dto.sdkVersion}.`,
    ...dto.items.map(itemLine)];
  if (dto.items.some((item) => item.property === 'DOOR_LOCK' && item.status === 'OK')) lines.push(LOCK_NOTE);
  lines.push(`${RECEIPT_NOTE} ${SNAPSHOT_NOTE}`);
  if (dto.outcome !== 'COMPLETE') lines.push(NO_GUESS);
  return textResult(lines, dto, dto.outcome !== 'COMPLETE');
}

/**
 * Runs one tool call under a single monotonic deadline that starts here and also covers presentation.
 * `now`, `deadlineMs`, `port` and `render` exist for tests and the fixed server configuration.
 */
export async function runTool(tool, {
  signal, port = BRIDGE_PORT, deadlineMs = TOTAL_DEADLINE_MS, now = () => performance.now(),
  render = (dto, defaultRender) => defaultRender(dto),
} = {}) {
  const startedAt = now();
  const options = { signal, port, deadlineMs, now, startedAt };
  const dto = tool.legacy ? await readFrontLeftDoor(options) : await readVehicle(tool.query, options);
  const defaultRender = tool.legacy ? toToolResult : (value) => toStatusToolResult(value, tool);
  const out = render(dto, defaultRender);
  if (now() - startedAt < deadlineMs) return out;
  const late = tool.legacy ? { ok: false, source: 'pc', code: 'TIMEOUT', requestId: dto.requestId, area: AREA }
    : pcFailure('TIMEOUT', dto.requestId, tool.query);
  return defaultRender(late);
}

/**
 * Factory for serveStdio. It may be called again for the legacy fallback, so it only registers:
 * no bridge connection, GET or ADB work happens here.
 */
export function createServer({ shutdownSignal, port = BRIDGE_PORT, deadlineMs = TOTAL_DEADLINE_MS }) {
  const server = new McpServer({ name: 'pleostest-vehicle', version: '0.2.0' }, { capabilities: { tools: {} } });
  for (const tool of TOOLS) {
    server.registerTool(tool.name, {
      title: tool.title,
      description: tool.description,
      inputSchema: z.strictObject({}),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    }, async (_args, ctx) => {
      const signals = [ctx.mcpReq.signal, shutdownSignal].filter(Boolean);
      return runTool(tool, { port, deadlineMs, signal: AbortSignal.any(signals) });
    });
  }
  return server;
}

function main() {
  const shutdown = new AbortController();
  const handle = serveStdio(() => createServer({ shutdownSignal: shutdown.signal }), {
    legacy: 'serve',
    onerror: () => process.stderr.write('pleostest-vehicle-mcp: STDIO_ERROR\n'),
  });
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    shutdown.abort();
    handle.close().catch(() => {});
  };
  process.stdin.once('end', stop);
  process.stdin.once('close', stop);
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}

// Node runs the main module from its real path, so compare real paths (junction/symlink safe).
const invokedPath = process.argv[1] ? realpathSync(process.argv[1]) : '';
if (realpathSync(fileURLToPath(import.meta.url)) === invokedPath) main();
