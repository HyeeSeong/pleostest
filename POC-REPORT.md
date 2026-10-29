# Vehicle MCP PoC 결과 보고서

작성일 2026-10-28. 범위는 현대자동차그룹 Pleos 공모전용 최소 PoC다. 계획과 수락 기준의 원본은 Linear이며, 이 문서는 결과 요약이다. 환경 재현은 [README.md](README.md)를 따른다.

## 1. 판정

**목표 달성: GPT와 Claude가 Pleos 에뮬레이터의 Vehicle SDK 차량 데이터를 MCP로 조회하고, 그 값으로 답변할 수 있음을 확인했다.**

단, 검증 대상은 Pleos 에뮬레이터(AVD)뿐이다. 실차, Fleet/Vehicle Data API, 장시간 안정성은 검증하지 않았다. 아래 "미검증"을 PoC 통과 범위에 포함하지 않는다.

## 2. 구조

```
AI 앱(Claude/ChatGPT) → 앱별 stdio MCP 프로세스(PC, mcp/src/server.mjs)
  → PC 127.0.0.1:18765 → 사용자가 만든 adb forward
  → Android 127.0.0.1:18765 → Foreground Service(BridgeService)
  → 공통 조회기(VehicleStatusReader) → Pleos Vehicle SDK 2.3.2
```

- MCP 도구 4개: `get_door_status`, `get_window_status`, `get_energy_level`, `get_front_left_door_position`. 모두 읽기 전용이며 인자 없음.
- 내부 계약: `POST /v2/vehicle-read`. 호출 전체 기한 10초, HTTP 1회, 자동 재시도·ADB 복구·이전 값 재사용 없음.
- 토큰 없음. 127.0.0.1 bind, 정확한 Host, Origin 거부만 검사한다(프로세스별 인증이 아님).
- 앱 화면이 백그라운드여도 Foreground Service(`specialUse`)가 서버와 SDK를 유지한다. 종료는 알림 중지 또는 화면 종료 버튼.

## 3. 마일스톤별 결과

| Issue | 내용 | 결과 |
| --- | --- | --- |
| HYE-27·28·29·30 (M1) | 연결 조건·SDK 버전·앞좌측 문 조회·공식 샘플 비교 | Done. 앞좌측 문 닫힘 0 / 열림 1을 기존 앱과 공식 샘플 비교 빌드 모두에서 확인 |
| HYE-31 (M2) | MCP 도구와 Android 조회 연결 | Done |
| HYE-34 (M4) | 토큰 제거, Host/Origin 403 | Done. Gradle 3종·npm 통과 |
| HYE-35 (M4) | 문·창문·에너지 확장, v2 계약, 도구 4개 | Done. JVM 79건 통과 시점에 구현 검증 |
| HYE-32 (M4 검증) | 두 AI 앱 실제 검증 | Done(사용자 결정). 필수 사례 일부 미검증 |
| HYE-33 (M4 상위) | 상위 Issue | Done |
| HYE-36 (M5) | Foreground Service로 백그라운드 유지 | Done(사용자 결정). 핵심 사례 검증 |

## 4. 확인된 것

- **두 AI 앱 실제 성공:** Claude와 ChatGPT 모두 도구를 호출해 실제 값을 받고 답했다.
- **3개 도메인 값 수신:** 문 위치 4영역, 창문 위치 4영역(raw와 범위 0~10 보존), EV 배터리(%).
- **값 일치:** Console에서 값을 바꿔 가며 앱 답변과 일치함을 사용자가 확인했다(예: EV 73%→35%, 창문 raw 0→10, 문 상태 변화).
- **기존 도구 회귀 없음:** `get_front_left_door_position`이 `get_door_status`의 앞좌측 값과 일치.
- **미지원·실패를 정상값으로 채우지 않음:** 문 잠금 `AREA_UNSUPPORTED`, 연료 `CAPABILITY_FAILED`가 실패로 전달되었고 두 AI 앱 모두 이를 실패로 설명했다. 전체 결과는 PARTIAL로 해석된다.
- **백그라운드 동작:** Service 컨텍스트에서 SDK 조회가 되고, 앱을 내린 상태에서도 두 앱 조회가 성공했다.
- **로컬 검증(최종):** `assembleDebug`, `testDebugUnitTest`(87건), `lintDebug`(오류 0, 경고 5) exit 0. `npm test` 36건 통과. APK SHA256 `048BE73C38DB211D05FEA7D0E471A6575392D56435439269EB9EF34A9450D81E`.

## 5. 이번 PoC에서 알게 된 한계와 발견

| 항목 | 내용 |
| --- | --- |
| 문 잠금 | 에뮬레이터 capability가 4문 묶음 영역과 HOOD만 보고하고 단독 영역이 없다. 계약상 묶음을 분해하지 않으므로 `AREA_UNSUPPORTED`다. 환경 한계로 보이나 SDK 특성인지 확정하지 못했다. |
| 연료 | `CAPABILITY_FAILED`, errorType이 난독화된 `a`라 원인(미지원/연결/권한)을 구분하지 못했다. 진단 정보 개선이 필요하다. |
| 창문 | raw 0~10의 개폐 의미를 SDK가 제공하지 않아 해석하지 않는다. |
| 동시 호출 | ChatGPT가 4개 도구를 동시에 호출하면 `BUSY`와 `ANDROID_UNREACHABLE`이 나왔다. 3초 간격 순차 호출에서는 재현되지 않았다. 동시 호출이 원인이라는 것은 정황이며 확정하지 못했다. |
| 수신 시각 | 앱 콜백 수신 시각일 뿐 차량 측정 시각이나 동시 스냅샷이 아니다. |
| 보안 | 토큰이 없어 adb forward가 켜져 있고 Service가 살아 있는 동안 같은 PC의 다른 로컬 프로세스도 조회할 수 있다. 읽기 전용이지만 프로세스별 인증은 아니다. |
| 환경 | 단일 슬롯, 요청당 일회 조회, 구독 없음. adb forward와 PC의 stdio 프로세스에 의존한다. |

## 6. 미검증 (통과로 계산하지 않음)

- HYE-32 필수 사례 중: 두 호스트의 도구 목록 화면 기록, 통신 오류(`forward --remove`) 사례와 오류 후 복구, Android 재시작 후 복구, tool call 수·HTTP 시도 수·자동 재시도 유무의 로그 확인.
- HYE-36: 알림 중지/화면 종료 후 포트·SDK 정리와 이후 `ANDROID_UNREACHABLE`, 화면 재진입 시 reader 1개, 알림 권한 요청·표시 모습, 장시간 유지.
- 환경 기록 불완전: AVD/API/serial/Service 버전(기록상 2.3.22), 호스트 제품·버전, Console 설정값 원문.
- 공식 API Reference 4개 페이지의 시그니처·권한 등급은 웹에서 확인하지 못했다(SDK 2.3.2 AAR의 `javap`로 선언만 확인). 문서 버전(2.3.1/2.3.2 차이)과 실제 Service 호환은 임의로 단정하지 않았다.
- 실차, Fleet/Vehicle Data API, 제어·구독 기능.
- 구현 전 실패 기준선은 HYE-34에서 관측하지 못했고, HYE-35·36은 컴파일 실패 수준으로만 관측했다.

## 7. 다음 단계 후보 (미결정, 계획은 추후)

1. 연료 실패 예외를 Logcat에 남겨 원인을 구분하고, 도구 설명에 순차 호출을 안내한다.
2. 동시 호출 시 `ANDROID_UNREACHABLE` 오인 여부를 확인하고 포화 응답을 구분한다.
3. 실서비스 구조(데이터 경로, 인증, 실차 접근)는 본격 구축 계획에서 정한다. 후보는 기기 내 브리지 유지, Fleet/Vehicle Data API, 서버 중계다.
4. HYE-32의 미수행 사례를 필요 시 보강한다.

## 8. 작업 방식 기록

- 구현은 서브에이전트로 순차 수행했다(HYE-34 → HYE-35 → HYE-36). 저장소는 Git이 아니어서 worktree·commit을 사용하지 않았다.
- Linear 상태·댓글은 사용자 요청 범위에서만 변경했다. 사용자 결정으로 HYE-32·33·36을 일부 미검증 상태로 Done 처리했다.
- SDK/AGP/Gradle/JDK/Node 의존성 버전은 바꾸지 않았다.
