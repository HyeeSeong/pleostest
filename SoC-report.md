# pleostest SoC 보고서

- 작성일: 2026-10-28 (KST)
- 원본: Linear Project [PoC(Proof of Concept) · P-HYE-7](https://linear.app/hye-seong/project/pocproof-of-concept-355f945a981b), Issue HYE-27 ~ HYE-35
- 상태: **중단 시점 기록.** PoC 프로젝트는 Planned, 마지막 Issue(HYE-32)는 In Progress.

## 1. 한눈에 보기

| 질문 | 답 |
| -- | -- |
| GPT/Claude가 Pleos 차량 데이터를 MCP로 조회해 답할 수 있나? | **예, 실제 값으로 확인함.** Claude·ChatGPT 모두 성공 1건 이상. |
| 값이 에뮬레이터 상태와 맞나? | 예. Console에서 값을 바꿀 때마다 답변이 따라 바뀜. |
| PoC 수락 기준을 모두 채웠나? | **아니오.** 통신 오류·복구·재시작 복구가 미검증. |
| 그래서 Done인가? | 아니오. HYE-32는 In Progress로 남김. |

## 2. Milestone 현황

| Milestone | 진행률 | 내용 |
| -- | -- | -- |
| M1 연결 조건·차량 조회 기반 | 100% | 앞좌측 문 조회 계약과 앱 구현 완료 |
| M2 MCP로 차량 조회 연결 | 100% | stdio MCP ↔ Android bridge 구현 완료 |
| M3 두 AI 앱 사용자 검증 | 0% | HYE-32를 M4로 재사용하면서 사실상 M4에 흡수 |
| M4 차량 상태 전반 조회 | 6% | 토큰 제거·조회 확장 구현은 Todo, 검증만 부분 진행 |

## 3. Issue별 결과

| Issue | 제목 | 상태 | 한 줄 결과 |
| -- | -- | -- | -- |
| HYE-27 | [M1] 두 AI 앱 연결 조건·구조 확정 | Done | PC stdio MCP → ADB forward → Android loopback 구조로 결정 |
| HYE-28 | [M1] SDK 버전·앞좌측 문 조회 계약 | Done | SDK 2.3.2, Door Position / ROW_1_LEFT, 0=닫힘·1=열림(bounds 0..1일 때만) |
| HYE-29 | [M1] 단건 조회와 진단 화면 구현 | Done | 10초 한도·재시도 없음·늦은 콜백 폐기 |
| HYE-30 | [M1] SDK 조회와 공식 샘플 비교 | Done | 자체 앱과 공식 샘플 모두 닫힘 0/열림 1 확인 |
| HYE-31 | [M2] MCP ↔ Android 연결 | Done | 구현·로컬 검사 완료. 실제 AI 앱 성공으로는 계산하지 않음 |
| HYE-32 | [M4 검증] 두 AI 앱 SDK 조회·PoC 판정 | **In Progress** | 값 수신·일치 확인, 오류·복구 사례 미수행 |
| HYE-33 | [M4] 조회 확장과 토큰 없는 접속 (상위) | Backlog | 미착수 |
| HYE-34 | [M4] 토큰 없이 기존 문 조회 연결 | Todo | 미착수(Linear 기준) |
| HYE-35 | [M4] 문·창문·에너지로 확장 | Todo | 미착수(Linear 기준) |

> 주의: HYE-32 댓글에는 "HYE-34·35 반영 v2 APK/Node"로 검증했다고 적혀 있으나, Linear 상태는 둘 다 Todo다. 상태와 실제 작업이 어긋나 있으니 재개할 때 먼저 맞춰야 한다.

## 4. 실제로 확인한 것 (HYE-32 기록, 2026-10-28)

- **두 호스트 성공**: Claude, ChatGPT 모두 도구 호출로 실제 값을 받아 답함.
- **3개 도메인 값 수신**: 문 위치 4영역, 창문 4영역(raw 0~10 그대로 보존), EV 배터리(%).
- **값 일치**: Console에서 값을 바꾸며 확인. 예) EV 73% → 35%, 창문 raw 0 → 10, 문 4개 닫힘 → 앞좌측만 열림 → 전부 열림. 이전 값 재사용이 아닌 새 관측.
- **기존 도구 회귀 없음**: `get_front_left_door_position`이 `get_door_status` 앞좌측 값과 일치.
- **미지원을 정상값으로 채우지 않음**: 문 잠금 4영역 `AREA_UNSUPPORTED`, 연료 `CAPABILITY_FAILED`. 두 AI 모두 실패로 설명했고 PARTIAL로 해석됨.
- **자동 재시도 없음, 상태 추정 없음**: 이전 Claude 검증에서도 모든 호출에서 충족.

## 5. 미완료·미검증

| 항목 | 상태 |
| -- | -- |
| 두 호스트의 실제 도구 목록 화면 기록(4개 도구·빈 인자) | 미수행 |
| 통신 오류(forwarding 제거 → `ANDROID_UNREACHABLE`) | 미수행 (M3 때 Claude 1차 시도에서 `ANDROID_UNREACHABLE` 확인된 적은 있으나 새 빌드 근거 아님) |
| 오류 후 복구(`forward --no-rebind`) | 미수행 |
| Android 앱 재시작 후 두 호스트 복구 | 미수행 |
| 연료 지원 여부 | 이 환경에서 미확인 |
| 문 잠금 값 일치 | 지원 조건 미충족으로 검증 불가 |
| tool call 수·HTTP 시도 수·retry 유무 로그 | 미첨부 |
| AVD/API/serial/Service 버전, 호스트 제품 버전 | 기록 누락 |
| Console 설정값 원문 | 미첨부 |

## 6. 알려진 문제

1. **토큰·서버가 Activity 수명에 묶임.** 앱이 백그라운드로 가면 서버가 내려가 `ANDROID_UNREACHABLE`, 돌아오면 토큰이 바뀌어 `UNAUTHORIZED`. 그래서 `APP_NOT_READY`에 도달 못 함. 이 때문에 M4(HYE-34)에서 토큰 제거를 계획함. 원인은 관찰 추론이며 코드 확인은 안 됨.
2. **동시 호출 시 `BUSY`·`ANDROID_UNREACHABLE`.** ChatGPT가 4개 도구를 동시에 보냈을 때 발생. 3초 간격 순차 호출에서는 재현 안 됨. `ANDROID_UNREACHABLE` 원인은 미확정.
3. **ChatGPT `UNAUTHORIZED` 이력.** M4 계획에 "토큰 제거만으로 해결을 단정하지 않는다"고 명시됨.
4. 앱은 Android user 10에서 실행됨. `am start --user 10`이 필요했음.

## 7. 재개 시 순서

1. HYE-34/35 실제 구현 상태와 Linear 상태 맞추기.
2. HYE-32의 미수행 4개 사례(오류·복구·재시작 복구·도구 목록) 수행.
3. 환경 식별값(AVD/API/serial/Service/호스트 버전)과 호출 수 로그 기록.
4. 모두 충족되면 HYE-32 → HYE-33 → M4 Done 판정.

## 8. 이 보고서가 말하지 않는 것

- 빌드·JVM 테스트·Lint 결과는 Linear에 근거가 남아 있지 않아 이 보고서에 넣지 않았다. 로컬에서 별도 실행한 기록이 필요하다.
- 코드와 APK는 직접 열어보지 않았다. 모든 내용은 Linear 기록 기준이다.
