# Vehicle MCP — 최소 PoC v0.1

GPT가 Pleos SDK/API에서 차량 데이터를 조회하고 그 결과로 응답할 수 있는지 검증하기 위한 Android 프로젝트다. 현재 조회 대상은 앞좌측 차 문 열림·닫힘(Door Position / ROW_1_LEFT), Vehicle SDK는 2.3.2다. 사용자가 기존 앱과 공식 샘플 기반 읽기 전용 비교 빌드에서 닫힘0·열림1을 확인해 HYE-28·30은 완료했다. [HYE-31](https://linear.app/hye-seong/issue/HYE-31)에서 두 AI 앱용 stdio MCP 서버(`mcp/`)와 Android loopback bridge를 구현하고 로컬 검사를 마쳤다. [HYE-35](https://linear.app/hye-seong/issue/HYE-35)에서 SDK 공개 읽기를 문 4영역(위치+잠금)·창문 4영역·연료/EV 잔량으로 확장하고 MCP 도구를 4개로 늘렸다(앱 화면의 버튼은 여전히 앞좌측 문 한 가지). [HYE-36](https://linear.app/hye-seong/issue/HYE-36)에서 서버·조회기·SDK 소유를 Activity에서 Foreground Service로 옮겨 화면이 닫혀도 브리지가 응답하도록 구현했다(Pleos 환경에서의 실제 동작은 미검증). 실제 ChatGPT·Claude 연결과 PoC 판정은 [HYE-32](https://linear.app/hye-seong/issue/HYE-32)에서 사용자가 수행한다. OpenAI API는 사용하지 않는다.

개발 지침은 [AGENTS.md](AGENTS.md), 작업 범위·계획·수락 기준은 Linear, 소스·PR·기술 검증 이력은 향후 연결할 GitHub에서 관리한다. 이 문서는 환경 재현과 공식 문서 탐색을 위한 안내이며 작업 백로그가 아니다.

## 확인된 환경

아래 표는 구현 전 환경 기록이다. HYE-29 이후의 실행 방법은 다음 절을 따른다.

2026-10-26 로컬 조사 기준. 설치·연결 상태는 다음 작업 시작 시 다시 확인한다.

| 항목 | 확인 결과 |
| --- | --- |
| 프로젝트 | `pleostest`, 단일 `:app`, Kotlin DSL, `com.example.pleostest` |
| Git / 기존 문서 | `.git`과 기존 README/프로젝트 AGENTS 없음. 이번에 Git 초기화·원격 생성하지 않음 |
| Android Studio | 설치된 `product-info.json`: `AI-262.9437.185.2621.16467767` |
| Gradle / AGP | Wrapper 9.6.0 / version catalog 9.4.1, 실제 빌드 성공 |
| JDK / Java 타깃 | Studio 번들 JBR 25.0.3로 실행, daemon 기준 Java 25 / Java 소스·타깃 11 |
| Kotlin | AGP 내장 KGP 2.2.10을 루트 `buildEnvironment`에서 확인. `gradlew --version`의 2.3.21은 Gradle 내장 Kotlin |
| Android SDK | compile/target 37, min 34. Platform 34·37.0, Build Tools 36.0.0 설치 확인 |
| Pleos Emulator | `Pleos_Connect_v2.1.0` AVD, API 34, x86_64 시스템 이미지 설치 확인 |
| Pleos SDK / Service | 프로젝트에 Pleos Maven·SDK 의존성 없음. 설치된 AVD 내부 Service 버전과 API 호환성은 미검증 |
| 앱 구조 | `app/src/main/`에 manifest·리소스만 있음. main Kotlin/Java 소스 및 Activity 없음 |
| Codex / Claude | Codex CLI 0.158.0. 사용자 전역 설정 존재, 프로젝트별 설정 없음. Claude 전역 MCP는 `rea`, 사용자 설정은 theme만 확인. Claude CLI는 PATH에서 찾지 못함 |

## 앞좌측 차 문 조회 — 사용자 실행 안내

이번 대상은 **Door Position(문 열림·닫힘)**이며 도어 잠금(Door Lock)과 다르다. SDK 대상은 `DoorArea(DoorValue.ROW_1_LEFT)`다.

1. 기존 `Pleos_Connect_v2.1.0` AVD를 실행한다.
2. **Pleos Connect Virtual Console을 실행하고 해당 AVD와 연결된 상태를 확인한다.** AVD만 실행한 상태를 차량 상태 변경 준비 완료로 간주하지 않는다. [공식 Console 가이드](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)를 따른다.
3. 새 `app/build/outputs/apk/debug/app-debug.apk`를 에뮬레이터에 끌어다 놓아 업데이트하거나 Android Studio에서 해당 AVD에 `app`을 Run한다. 화면 제목이 **앞좌측 문 조회**인지 확인한다.
4. Console → `Emulation Controls → Convenience → Door Position`에서 **앞좌측** 문을 Closed로 준비한 뒤 앱의 **앞좌측 문 한 번 조회**를 누른다.
5. 같은 문을 Open으로 준비한 뒤 직접 다시 한 번 조회한다. Console 설정과 앱 결과(접근·보고된 영역·bounds·원시값·콜백 수신 시각)를 함께 기록한다.
6. 아래 항목을 [HYE-30](https://linear.app/hye-seong/issue/HYE-30)에 전달한다. 같은 값이 유지되거나 실패하면 화면 그대로 전달한다. 실제 상태 반영이 확인되기 전까지 정상 동작으로 판정하지 않는다.

| 기록 항목 | 사용자 입력 |
| --- | --- |
| AVD / Android API / serial / 설치 Vehicle Service 버전 | 식별 가능한 범위에서 기록, 나머지는 미확인 |
| Virtual Console 실행·연결 / 설정한 문 영역 | 기록 |
| Closed에서 이번 raw / bounds / 수신 시각 / 오류 | 기록 |
| Open에서 이번 raw / bounds / 수신 시각 / 오류 | 기록 |
| 각 Console·앱 결과 화면 / 일치 여부 | 기록 |

### 값과 요청 처리

- 현재 capability가 READ/READ_WRITE이고 **앞좌측 단독 영역과 정확히 일치**해야 읽는다. 묶음에 포함됐다는 이유만으로 단독 요청을 허용하지 않는다.
- bounds가 `0..1`일 때만 `0=닫힘`, `1=열림`으로 표시한다. 다른 bounds·알 수 없는 raw는 의미 미확인으로 보존한다. null·지원 실패·조회 실패·시간 초과는 정상 상태로 바꾸지 않는다.
- 초기화·capability·GET을 합해 총 **10초**, 동시에 한 요청, 자동 재시도 없음, 늦은 콜백 폐기를 유지한다. 화면 종료 시 취소·SDK release를 수행한다.
- 콜백 수신 시각은 차량 측 측정 시각이나 최신성 보장이 아니다. 초기화 반환과 실제 값 수신, Console 대조는 서로 다른 증거다.
- 앱은 읽기 권한 `CAR_DOORS`, `CAR_WINDOWS`, `CAR_ENERGY`만 선언한다(HYE-35에서 뒤의 둘 추가). HYE-36에서 `FOREGROUND_SERVICE`·`FOREGROUND_SERVICE_SPECIAL_USE`·`POST_NOTIFICATIONS`(차량 권한 아님)를 추가했다. 최종 APK에는 SDK VEHICLE_BINDING과 AndroidX 내부 receiver 권한도 병합된다. `CONTROL_*` 등 제어 권한/API는 추가하지 않는다.

2026-10-27 변경본은 Vehicle **2.3.2** 해석, APK 빌드, Door reader **17개 JVM 검사**, Lint 오류 0 / 기존 Warning 5를 확인했다. 기존 산술 검사는 차량 기능 증거에서 제외한다. 사용자가 기존 앱의 앞좌측 문 Closed에서 raw0, Open에서 raw1을 직접 확인했고, 2026-10-28 공식 샘플 기반 비교 빌드의 같은 대상0/1 화면도 제공했다. 두 앱의 상태 대조와 비교 결과는 HYE-30에 완료 기록됐다.

사용자는 Virtual Console을 켜 두지 않아 AVD 차량 상태 조작이 안 됐다고 정정했고, Console 실행 후에도 Window Lock 값이 바뀌지 않았다고 알려 왔다. Console 미실행은 기존 관측의 환경 누락이지만 Window Lock 불변값의 전체 원인이 해결되었다는 근거는 아니다. 이전 Window Lock/Window Position 결과를 이번 Door Position 통과 증거로 사용하지 않는다.

## 공식 Door 샘플과 비교하기

[고정 소스](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/tree/959981d960ec71144accb27af5ae5d341e72a644) commit은 `959981d960ec71144accb27af5ae5d341e72a644`다. [VehicleClient.kt](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/vehicle/VehicleClient.kt)의 DoorPositionRequests는 Door 전용 typed capability/get을 제공한다. 이번 앱은 이 호출 순서를 참고한 기존 자체 구현을 사용하며 샘플 소스를 직접 복사하지 않았다.

2026-10-28 [비교용 APK](build/reference/vehicle-sample-2.3.2-readonly.apk)를 준비했다. 공식 원본에 아래 네 파일 변경을 적용한 **문 읽기 전용 비교 빌드**이며 원본 배포 APK로 표현하지 않는다. 패키지가 `ai.pleos.playground.sample.vehicle`라 기존 `pleostest`와 나란히 설치할 수 있다.

1. 사용자가 위 APK를 Pleos AVD에 설치하고 **Vehicle SDK Sample**을 연다.
2. 화면의 SDK가 **2.3.2**, Service가 현재 기록한 **2.3.22**인지 확인한다.
3. **Door → Door Position → ROW_1_LEFT**를 선택한다. Check support 후 연결된 Virtual Console의 같은 문을 Closed/Open으로 준비한다.
4. 각각 **Refresh**를 직접 눌러 새 `getCurrentDoorPosition` 콜백을 확인한다. 기능 진입 시 초기 자동 GET을 수동 GET과 구분한다. 제어·구독 진입은 차단했으며 비교에는 Refresh만 사용한다.
5. 두 조회값·대상·지원 범위와 SDK/Service 표시를 HYE-30에 전달한다. 기존 앱의 닫힘0/열림1 기록과 함께 비교한다. 샘플에서도 실제 대조가 확인되어야 비교 완료다.

**비교 완료(2026-10-28):** 사용자가 샘플의 Current value0/1, Area ROW_1_LEFT 두 화면과 닫힘/열림 대응을 확인했다. 현재 user10에 설치된 샘플 APK 해시는 아래 비교 APK 해시와 일치하고 Service2.3.22/code74가 확인됐다. 위 절차는 재현 안내이며 같은 검증을 다시 요구하는 것이 아니다. 화면은 Current value/Area만 포함하므로 관측 시각·전체 API 제목·SDK/Service 표시를 화면에서 직접 확인했다고 확대하지 않는다. 실제 두 AI 앱 호출과 반복 신뢰성·실차 검증은 별도다.

직접 비교에는 property/API·전체 area·실제 해석 SDK·같은 AVD/Service·Virtual Console 실행/연결·조작 범위가 같아야 한다. 원본 카탈로그는 SDK **2.3.1 선언**, README는 **2.3.2 설명**이어서 이 비교 빌드는 카탈로그를 **2.3.2**로 맞췄고 dependencyInsight로 실제 해석을 확인했다. Window용 reflection 탐색기·묶음 인자 제한은 Door typed 경로의 제한으로 일반화하지 않는다.

### 비교 빌드 출처와 검증

- 원본 설정인 AGP8.8.2 / Gradle8.10.2 / Kotlin2.0.0 / 기존 JDK17.0.12 / compile·target35를 사용했다. 별도 wrapper 캐시, SDK Platform35(rev2), Build Tools35.0.0이 빌드 준비 과정에서 추가됐다. 기존 pleostest의 AGP9.4.1 / Gradle9.6.0 / JDK25 / compile37 설정은 변경하지 않았다.
- [고정 소스 ZIP](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/archive/959981d960ec71144accb27af5ae5d341e72a644.zip)을 무시되는 `build/reference/` 아래 별도 프로젝트로 풀었다. 원본64개 파일 중 변경4개는 SDK 카탈로그, 읽기 전용 Manifest, MainViewModel(제어·구독 진입 차단), VehicleClient(typed SET 차단)다. **Door GET 본문은 원본과 동일**함을 비교했다. 현재 제품 소스로 샘플 파일을 편입하지 않았다.
- 상세 파일 SHA256과 출처는 [comparison-provenance.json](build/reference/comparison-provenance.json)에 있다. APK SHA256은 `0E43AA848739A0B0B07094AA3034BC61A090054EB420A90F6497FBFDAAAAD320`이다.
- 첫 검사에서는 제어 권한을 제거한 원본 SET 호출을 Lint가 지적해 exit1이었다. 해당 호출과 제어·구독 진입을 차단한 최종 `assembleDebug lintDebug`는 **exit0**, Lint 오류0/Warning29다. 샘플 snapshot에 JVM/instrumentation 테스트 소스가 없어 그 검사를 통과했다고 쓰지 않는다.
- `aapt2`로 CAR_DOORS / SDK VEHICLE_BINDING / AndroidX 내부 receiver 권한과 런처를 확인했다. 제어 권한은 없다. 설치·SDK 조회·Console 대조는 Agent가 실행하지 않았다.
- 고정 tree에서 LICENSE/COPYING/NOTICE는 확인되지 않았다. 직접 제품 소스 편입 전에는 재사용 조건을 확인한다. 이번 APK는 사용자 요청의 로컬 비교용 변형이다.

다음은 이미 준비한 비교 프로젝트에서 재현하는 명령이다. `local.properties`는 로컬 전용이며 저장소·로그에 공유하지 않는다.

```powershell
Set-Location '.\build\reference\pleos-sample-vehicle-app-959981d960ec71144accb27af5ae5d341e72a644'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat --version
.\gradlew.bat :app:dependencyInsight --configuration debugRuntimeClasspath --dependency ai.pleos.playground:Vehicle --console=plain
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

공식 근거: [단건 호출](https://document.pleos.ai/en/docs/pleos-only/getting-started), [capability](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability), [Door Position 값·샘플 흐름](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app). Context7 resolve/query와 공식 원문·고정 샘플을 대조했다. 실제 Service/앱 실행 검증은 사용자 담당이다.

### 2026-10-28 읽기 확인 환경

현재 `emulator-5554` / `Pleos_Connect_v2.1.0` / Android14(API34), 현재 Android 사용자 **10**이다. Vehicle Service 패키지 `ai.pleos.playground.service.vehicle`는 **2.3.22(code74)**다. 사용자10에 설치된 기존 `com.example.pleostest`의 APK SHA256은 `9B349F6F3A5DB5DD46ED3B710E946B3EFE3F572E542545F73860778C2777DC3B`로 기존 문 조회 빌드와 일치한다. 이 조회는 앱 실행이나 차량 API 호출이 아닌 패키지 메타데이터/파일 해시 읽기다.

Automotive에서는 기본 user0과 실제 사용자10의 설치 상태가 다를 수 있다. `adb shell am get-current-user`로 현재 사용자를 읽고, 설치 경로는 `adb shell pm path --user <현재-user> <패키지>`로 확인한다. 기본 사용자 조회가 비었다고 전체 미설치로 단정하지 않는다.

## AI 앱 MCP 연결 — 사용자 설정 안내 (HYE-31, HYE-34, HYE-35)

연결 구조는 `AI 앱 → 앱별 stdio MCP 프로세스(PC) → PC 127.0.0.1:18765 → 사용자가 만든 adb forward → Android 127.0.0.1:18765 → 앱의 공통 조회기`다(토큰 없음, HYE-34). HYE-35에서 내부 HTTP 계약을 `POST /v2/vehicle-read`로 바꾸고 문·창문·에너지 읽기를 추가했다. HTTP는 앱 내부 연결용이며 MCP HTTP 전송이 아니다. 서버는 자동 재시도·자동 ADB 설정·자동 복구·이전 값 재사용을 하지 않는다.

### MCP 도구 4개 (모두 인자 없음)

| 도구 | 읽는 대상 | 항목 |
| --- | --- | --- |
| `get_door_status` | 문 4영역(ROW_1_LEFT·ROW_1_RIGHT·ROW_2_LEFT·ROW_2_RIGHT) 위치 + 같은 4영역 잠금 | 8항목. 잠금은 위치와 별개로 capability를 확인하며 미지원이면 `AREA_UNSUPPORTED` 등으로 표시 |
| `get_window_status` | 창문 4개 단독 영역 위치 | 4항목. 원시값과 capability 범위를 그대로 표시하며 %나 열림·닫힘으로 변환하지 않음 |
| `get_energy_level` | 연료 잔량(`getFuelPercent`), EV 배터리 잔량(`getEvBatteryStateOfCharge`) | 2항목. 지원되는 쪽만 값을 읽고 단위는 %. 둘 다 area가 없음 |
| `get_front_left_door_position` | 앞좌측 문 위치 1항목 | 기존 결과 형식(`state`·`rawValue`·`capability`·`receivedAt`) 유지. 다른 문·잠금으로 확장되지 않음 |

인자를 생략하거나 빈 객체 `{}`만 허용하며 `null`·배열·추가 key는 Android 호출 전에 거부한다. 목록 조회·도구 등록은 차량 I/O를 하지 않고, 도구 호출마다 Android로 HTTP를 정확히 1회 보낸다.

### SDK 공개 읽기 근거 (HYE-35)

- 선언 확인: Gradle 해석 `ai.pleos.playground:Vehicle:2.3.2` AAR의 공개 클래스를 `javap`로 읽어 `Door.getCurrentDoorPosition`·`getDoorLockState`·`checkDoorPositionCapabilities`·`checkDoorLockCapabilities`, `Window.getCurrentWindowPosition`·`checkWindowPositionCapabilities`, `Fuel.getFuelPercent`·`checkFuelPercentCapabilities`, `EvBattery.getEvBatteryStateOfCharge`·`checkEvBatteryStateOfChargeCapabilities`, `CapabilityConfig`, `AreaBounds`, `DoorValue`/`WindowValue` 선언을 확인했다. getter는 `Int?`(문 잠금은 `Boolean?`)를 `onComplete`로 전달한다. 이것은 선언 확인일 뿐 현재 Service의 지원·값 수신 증거가 아니다.
- 권한: [권한 문서](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)와 Context7 `/websites/document_pleos_ai_en`이 Door→`CAR_DOORS`, Window→`CAR_WINDOWS`, EvBattery·Fuel→`CAR_ENERGY`를 읽기 권한으로 안내한다. 문서에는 normal/dangerous 보호 수준이나 API별 `@RequiresPermission`이 적혀 있지 않아 **보호 수준은 미확인**이며, 문서의 표현은 도메인 단위다.
- 값 의미: API Reference의 [문 잠금](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/get-door-lock-state)·[창문](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-window-position)·[연료](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/get-fuel-percent)·[EV](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-ev-battery-state-of-charge) 페이지는 본문 조회에서 제목·목차만 반환됐다. 그래서 문 잠금 `true=잠김`은 API 이름에 근거한 해석이고(결과 텍스트에도 그렇게 밝힌다), 연료/EV의 `%` 단위와 창문 값 범위는 Issue의 설명과 capability 범위에 의존한다. 연료/EV 값이 0..100 밖이면 `UNKNOWN_VALUE`로 원시값만 보존한다. 창문 값은 원시값·bounds만 표시한다.
- 버전: 문서의 Vehicle 버전은 페이지마다 2.3.1/2.3.2로 다르다. 이 구현이 가정하는 것은 Gradle이 실제로 해석한 **2.3.2** 선언뿐이며 문서·Service(현재 기록 2.3.22)·SDK의 호환성은 보장하지 않는다.
- 실제 Service의 지원 여부, 값, AVD의 Window 값 변화는 이 변경에서 확인하지 않았다([HYE-32](https://linear.app/hye-seong/issue/HYE-32)).

이 절의 단계는 모두 **사용자가 직접** 실행한다. Agent는 설치·ADB·AVD·Console·두 AI 앱 설정을 실행하지 않았다. 아래 경로의 `<프로젝트 경로>`는 이 저장소의 실제 위치로, `<Pleos serial>`은 `adb devices -l`에서 확인한 Pleos 기기로 바꾼다.

### 1. 준비

1. 프로젝트 루트에서 MCP 서버 의존성을 설치한다. Node.js 24.15.0을 기준으로 고정된 lockfile을 사용한다.

   ```powershell
   Set-Location '<프로젝트 경로>\mcp'
   node --version
   npm ci
   ```

2. 새 debug APK를 Pleos AVD에 설치하고 Virtual Console을 실행·연결한다([위 안내](#앞좌측-차-문-조회--사용자-실행-안내) 1–3단계).
3. 앱을 열면 화면이 보이는 동안 Foreground Service가 시작된다(첫 실행에서 알림 권한 요청이 뜨면 허용을 권장한다. 거부해도 서비스는 동작하며 화면의 종료 버튼으로 끌 수 있다). 앱 화면 아래쪽에 **AI 연결 대기 중** 문구가 보이는지 확인한다. **AI 연결을 시작하지 못했습니다**가 보이면 표시된 오류 유형을 기록하고 진행하지 않는다.
4. 이 변경(HYE-35, v2 계약)을 **처음 적용할 때는 새 APK 설치와 Node 서버 파일(`mcp/src/*.mjs`) 갱신을 함께** 하고, 이미 실행 중인 두 AI 앱의 MCP 프로세스를 **완전히 종료한 뒤 다시 시작**한다(도구가 4개로 바뀌므로 목록도 다시 읽혀야 한다). 예전 서버(v1)와 새 APK, 또는 반대 조합은 요청이 `INVALID_REQUEST`로 거부되거나 응답이 `INVALID_RESPONSE`로 버려진다. v1 endpoint와 토큰 복구 fallback은 없다. 이후에는 앱 화면을 닫아도 서비스가 살아 있는 동안 재시작이 필요 없다(아래 4번).

### 2. ADB forward — 선택한 Pleos 기기 하나에만

```powershell
$sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = "$sdkPath\platform-tools\adb.exe"
& $adb devices -l
$deviceSerial = '<Pleos serial>'
& $adb forward --list
& $adb -s $deviceSerial forward --no-rebind tcp:18765 tcp:18765
netstat -ano -p tcp | Select-String ':18765'
```

- `forward --list`에 이미 `tcp:18765`가 있거나 `--no-rebind`가 실패하면 다른 작업이 포트를 쓰는 것이다. 기존 매핑을 덮어쓰거나 다른 포트로 바꾸지 말고 중단·기록한다.
- `netstat` 결과의 LISTENING 주소가 `127.0.0.1:18765`일 때만 계속한다. `0.0.0.0` 또는 `[::]`이면 다른 기기에서도 접근할 수 있으므로 아래 제거 명령으로 이 매핑만 지우고 중단한다. 전역 ADB 설정은 바꾸지 않는다.
- 이 매핑만 제거: `& $adb -s $deviceSerial forward --remove tcp:18765`

### 3. 두 AI 앱에 각각 등록

두 앱에 같은 서버 파일을 **각각** 등록하며 각 앱이 자기 프로세스를 실행한다. 암호·토큰·환경 변수는 필요 없다. 예전 `VEHICLE_BRIDGE_TOKEN`이 남아 있어도 서버는 읽지 않으며 인증 모드로 동작하지 않는다(설정에서 지워도 된다).

**ChatGPT 데스크톱** — Settings → 플러그인 → MCP 서버 추가 → STDIO (HYE-27에서 사용자가 확인한 화면 기준)

| 입력 | 값 |
| --- | --- |
| 실행 명령 | `C:\Program Files\nodejs\node.exe` |
| 인자 | `<프로젝트 경로>\mcp\src\server.mjs` |
| 환경 변수 | 없음 |
| 작업 디렉터리 | `<프로젝트 경로>\mcp` |

**Claude 데스크톱** — `%APPDATA%\Claude\claude_desktop_config.json`의 기존 내용을 유지한 채 `mcpServers`에 항목 하나를 추가하고, 앱을 완전히 종료한 뒤 다시 실행한다. 새 레이아웃에서는 Settings → Desktop app → Extensions → Local MCP servers에서 확인할 수 있다. 대화의 `+` → Connectors에서 도구가 보이는지 확인한다.

```json
{
  "mcpServers": {
    "pleostest-vehicle": {
      "command": "C:\\Program Files\\nodejs\\node.exe",
      "args": ["<프로젝트 경로>\\mcp\\src\\server.mjs"]
    }
  }
}
```

**보안 한계(PoC):** 토큰이 없으므로 `adb forward`가 켜져 있는 동안 이 PC의 **다른 로컬 프로세스도** `127.0.0.1:18765`로 문·창문·에너지 읽기 4가지를 요청할 수 있다. 앱은 127.0.0.1 bind, loopback 상대 확인, 정확한 `Host: 127.0.0.1:18765`, `Origin` 헤더 거부만 검사한다. 이는 브라우저성 요청을 막는 검사일 뿐 **프로세스별 인증이 아니다**. 조회는 읽기 전용 한 가지이며 제어 기능은 없다. **Foreground Service는 화면을 닫아도 계속 살아 있으므로, forward가 켜져 있고 알림이 떠 있는 동안에는 다른 로컬 프로세스도 계속 조회할 수 있다.** 사용하지 않을 때는 앱의 **AI 연결 종료** 버튼 또는 알림의 **중지**로 서비스를 끄고(포트와 SDK 정리), 필요하면 forward도 제거한다.

### 4. 백그라운드 동작, 종료, 화면을 다시 열었을 때

- 앱 화면이 보일 때(onStart) 앱이 Foreground Service(`foregroundServiceType=specialUse`)를 시작하고 연결한다. 서비스가 문 조회기·Pleos SDK·loopback 서버 세션을 하나 소유하며, 화면 버튼과 모든 MCP 도구가 같은 조회 슬롯을 공유한다. **화면을 닫거나 다른 앱으로 가도(onStop) 서비스는 종료되지 않고** 연결만 해제한다. 따라서 화면을 foreground에 둘 필요가 없다. 단 백그라운드에서 새로 시작하지는 않는다. 서비스를 켜려면 화면을 한 번 열어야 한다.
- 실행 중에는 알림 "차량 조회 AI 연결 실행 중"이 표시된다. 부팅 시 자동 시작이나 프로세스 종료 후 재시작(START_STICKY)은 하지 않는다. 시스템이 프로세스를 종료하면 서비스도 사라지며 화면을 다시 열어야 한다.
- **끄는 방법:** 알림의 **중지** 또는 화면의 **AI 연결 종료** 버튼. 연결을 닫고 진행 중 조회를 취소한 뒤 SDK를 release하며 포트가 해제된다. 이후 호출은 `ANDROID_UNREACHABLE`(서비스 미연결) 또는 `APP_NOT_READY`다. 같은 화면에서 종료한 뒤 다시 켜려면 화면을 닫았다 다시 연다.
- 화면을 다시 열면 서비스가 이미 실행 중일 때 기존 세션과 조회기를 그대로 쓰며(조회기는 하나, 중복 release 없음) 새 연결을 만들지 않는다. 설정 수정이나 MCP 재시작은 필요 없다. 이전 호출이 `APP_NOT_READY`·`ANDROID_UNREACHABLE`로 끝났다면 알림이 있는지(또는 화면이 **AI 연결 대기 중**인지) 확인한 뒤 새 명시적 요청으로 다시 호출한다(자동 재시도·자동 복구 없음). 종료 후 다시 켠 새 세션에는 이전 세션의 늦은 결과가 섞이지 않는다.
- **foregroundServiceType 근거(공식 [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)):** `specialUse`는 다른 type이 다루지 않는 용도를 위한 것으로, 런타임 선행 조건과 이 페이지에 명시된 timeout이 없으며 `FOREGROUND_SERVICE_SPECIAL_USE` 권한과 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property가 필요하다. `dataSync`(데이터 전송용)는 용도에 맞지 않고(Android 15 timeout 값은 위 페이지에 없어 미확인), `connectedDevice`는 Bluetooth·Wi-Fi 등 선행 권한이 필요한데 이 앱은 해당 장치를 쓰지 않는다. 제약: Play Console 앱 콘텐츠에 type을 신고하고 property 설명을 심사받아야 한다(PoC 로컬 사용에는 무관). Android 12+는 백그라운드에서의 foreground service 시작을 제한하지만([공식](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)) 보이는 Activity에서의 시작은 예외다. 알림 해제 가능 여부와 시스템 종료 수단은 공식 페이지로 확인하지 못해 미확인이며, 확실한 종료 수단은 위 두 가지다.
- **미검증:** Pleos Service 환경에서 서비스 컨텍스트의 SDK 초기화 성공, 화면을 닫은 뒤의 실제 값 조회와 프로세스 유지, Pleos의 프로세스 정책은 Agent가 확인하지 않았다. 사용자가 AVD에서 [HYE-36](https://linear.app/hye-seong/issue/HYE-36) 수락 기준대로 확인한다. 실패하면 단계와 오류를 그대로 기록한다.

### 5. 결과 코드와 복구

`get_door_status`·`get_window_status`·`get_energy_level`의 전체 결과는 항목별 결과로 계산한다. **COMPLETE**(모든 항목이 해석된 성공)만 정상이고, **PARTIAL**(일부 성공)·**FAILED**(성공 없음)은 MCP 오류(`isError=true`)로 전달하되 성공한 관측과 실패 사유를 모두 보존한다. 항목은 순차로 읽으며 같은 시점의 스냅샷이 아니고, 항목별 수신 시각은 측정 시각이나 최신성 보장이 아니다. 전체 10초 기한을 넘기면 먼저 성공한 항목이 있어도 **TIMEOUT**이며 어떤 항목도 내보내지 않는다. 지원 확인은 속성별 한 번, 지원되는 항목의 조회는 항목당 최대 한 번이다. 연료·EV 중 한쪽만 지원되는 차량은 지원되는 쪽만 값이 나오고 다른 쪽은 사유가 표시되므로 PARTIAL이다.

| code / 항목 상태 | 생성 위치 | 의미와 사용자 조치 |
| --- | --- | --- |
| 성공(`OK`) | Android | 이번 콜백의 원시값과 확인된 의미·단위(문 0=닫힘/1=열림은 bounds 0~1일 때만, 잠금 true/false, 연료·EV %, 창문은 원시값만). 수신 시각은 측정 시각이나 최신성 보장이 아니다. |
| CAPABILITY_FAILED · READ_UNAVAILABLE · AREA_UNSUPPORTED · SUPPORT_UNCONFIRMED · NO_VALUE · UNKNOWN_VALUE · READ_FAILED | Android 조회기 | 항목별 사유다(앱 버튼 결과와 같은 의미). Service·Console 연결을 확인하고 정상 상태로 간주하지 않는다. 값이 없으면 0·닫힘·정상으로 채우지 않는다. |
| TIMEOUT · CANCELLED | Android 또는 PC | 총 10초 초과 또는 취소. 늦은 결과와 부분 결과는 버린다. 새로 명시적으로 요청할 수 있다. |
| BUSY | Android | 버튼이나 다른 AI 앱의 조회가 진행 중이다. 기존 조회는 계속된다. 끝난 뒤 다시 요청한다. |
| APP_NOT_READY | Android | 세션이 닫히는 중이다(서비스 종료 중). 화면을 다시 열고 새로 요청한다(4번). |
| REQUEST_FORBIDDEN | Android | HTTP 403. Host 누락·중복·불일치 또는 Origin 헤더가 있는 요청. 조회기는 호출되지 않았다. MCP 서버 파일로 직접 호출했는지 확인한다. |
| ANDROID_UNREACHABLE | PC | forward가 없거나 앱의 AI 연결 서비스가 꺼져 있다(종료·프로세스 종료). 2번의 `forward --list`와 알림·앱 화면 문구를 확인하고, 꺼져 있으면 화면을 다시 연다. |
| INVALID_REQUEST · INVALID_RESPONSE | Android · PC | 서버 파일과 APK가 같은 v2(HYE-35) 빌드인지 확인한다. 구형 APK의 401/UNAUTHORIZED·v1 응답, 항목 누락·중복·다른 속성/영역·잘못된 단위/의미/시각/HTTP status 조합도 이 코드로 거부한다. |

### 6. 검증 인계와 정리

- 실제 4건(ChatGPT 성공·오류, Claude 성공·오류)과 기록 양식은 [HYE-32](https://linear.app/hye-seong/issue/HYE-32)를 따른다. 오류 사례는 `forward --remove tcp:18765`로 만들고 같은 `--no-rebind` 명령으로 복구한다. 이 README 단계나 아래 로컬 검사는 실제 AI 앱 성공 증거가 아니다.
- 테스트 후 정리: 위 제거 명령으로 이 매핑만 지우고, 앱의 **AI 연결 종료** 버튼(또는 알림의 중지)으로 서비스를 끈다. 화면만 닫으면 서비스는 계속 실행된다. 필요하면 두 앱 설정에서 `pleostest-vehicle` 항목을 지운다.

### 로컬 검사 범위

`npm test`는 가짜 HTTP bridge와 실제 stdio subprocess로 legacy(2025-11-25)·modern(2026-07-28) 협상, 도구 4개 노출, 인자 거부, v2 결과(항목 집합·타입·단위·의미·시각·HTTP status 조합) 검증, 토큰·Authorization 없는 요청, 기한·취소, 도구 호출당 HTTP 1회를 검사한다. 고정 포트 18765가 이미 사용 중(예: adb forward)이면 subprocess 호출 테스트 1건은 skip된다. Android의 `DoorBridgeServerTest`는 JVM에서 실제 loopback socket과 가짜 SDK 경계로 Host/Origin 거부·BUSY·기한·재시작 세대·종료·8항목 문 조회를 검사하고, `BridgeSessionTest`는 Android 없이 세션 시작·종료 순서(admission → server → SDK release)·중복 start/stop·재시작을 가짜 경계로 검사하고, `VehicleStatusReaderTest`·`VehicleDomainAdaptersTest`는 도메인별 성공·미지원·실패·시간초과·단일 슬롯과 SDK 모델 매핑을 가짜 SDK로 검사한다. 둘 다 AVD listener, ADB forward, Vehicle Service, 실제 AI 앱 호출을 검증하지 않는다.

## 빌드와 검증 명령

프로젝트 루트에서 PowerShell로 실행한다. 기존 설치를 사용하며 전역 PATH/JAVA_HOME을 변경할 필요는 없다. 다음 JAVA_HOME은 현재 PowerShell 프로세스에만 적용된다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

MCP 서버 검사는 `mcp` 폴더에서 실행한다.

```powershell
Set-Location '.\mcp'
npm ci
npm test
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- JVM 테스트: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint: `app/build/reports/lint-results-debug.html` 및 같은 위치의 XML/SARIF
- Kotlin/AGP 해석 확인: `.\gradlew.bat buildEnvironment --offline --console=plain`
- 의존성 연결 후 버전 확인: `.\gradlew.bat :app:dependencyInsight --configuration debugRuntimeClasspath --dependency ai.pleos.playground:Vehicle`

Codex sandbox에서 사용자 Gradle 캐시의 `.lck` 접근이 거부될 수 있다. 이번에는 승인된 실행으로 기존 캐시를 사용해 검증했다. 이 오류를 Gradle 설정 문제로 간주해 버전을 바꾸거나 캐시를 삭제하지 않는다.

### 기기가 필요한 검증

Android SDK 경로는 로컬 `local.properties`의 `sdk.dir`를 확인한다. 이 PC의 설치 경로를 사용하는 예:

```powershell
$sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
& "$sdkPath\emulator\emulator.exe" -list-avds
& "$sdkPath\platform-tools\adb.exe" devices -l
```

기존 AVD는 Android Studio Device Manager에서 실행할 수 있다. CLI의 `emulator -avd Pleos_Connect_v2.1.0` 실행도 가능하지만 이번에는 실행하지 않았다. 부팅 후 대상 serial과 `sys.boot_completed`를 확인하고, 여러 기기가 있으면 명시적으로 serial을 선택한다.

```powershell
$deviceSerial = '<adb devices에서 확인한 Pleos serial>'
& "$sdkPath\platform-tools\adb.exe" -s $deviceSerial shell getprop sys.boot_completed
& "$sdkPath\platform-tools\adb.exe" -s $deviceSerial shell getprop ro.build.version.sdk
$env:ANDROID_SERIAL = $deviceSerial
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

`ANDROID_SERIAL`은 해당 PowerShell의 기기 선택 설정이다. `connectedDebugAndroidTest`는 테스트 APK를 기기에 설치·실행하므로 연결 대상을 먼저 확인한다. 현재 instrumentation 테스트는 패키지명 확인뿐이며 차량 API 테스트가 아니다.

기존 adb·Gradle instrumentation·UIAutomator로 설치/테스트/화면 조회 자동화를 확장할 수 있으나, 도구나 스크립트를 추가하지 않았다. HYE-29에서 런처 Activity를 추가했으며, 실제 실행은 위 사용자 안내를 따른다. Pleos 상태 검증에는 [Virtual Console 설정 가이드](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)를 따른다.

## Linear 연결과 작업 관리

- 현재 **Codex 데스크톱 세션의 Linear 연결은 읽기 검증 완료**: workspace URL의 slug `hye-seong`, Team `Hye_seong` (`HYE`). 접근 가능한 Team 1개·Project 6개를 전체 조회했고 Vehicle/Pleos 관련 Project는 확인되지 않았다. `Vehicle`, `Pleos` Issue 검색도 결과가 없었다. 다른 Workspace 접근 여부는 확인하지 않았다.
- 실제 상태: `Backlog`, `Todo`, `In Progress`, `In Review`, `Done`, `Canceled`, `Duplicate`. 별도 상태를 만들지 않는다.
- `get_agent_context`는 서버가 `Tool not found`를 반환했다. 이 선택 기능은 사용하지 않아도 일반 Team/Project/Issue 조회가 가능했다. 쓰기 권한/실행은 미검증이며 아무 리소스도 변경하지 않았다.
- 데스크톱 연결과 독립 CLI 설정은 별개다. `codex mcp list --json`에는 `node_repl`, `rea`, `penpot`, `cua_repl`, 비활성 `codex_app`이 보였으며 Linear·Context7 서버는 없었다. 이 세션에서는 이미 작동하는 연결을 사용하므로 전역 MCP 설정을 추가하지 않았다.
- 별도 CLI 사용이 필요해지면 [Linear 공식 MCP 안내](https://linear.app/docs/mcp)와 [Codex 공식 MCP 안내](https://developers.openai.com/codex/mcp/)를 확인하고 `codex mcp add linear --url https://mcp.linear.app/mcp`, 필요 시 `codex mcp login linear`로 사용자 OAuth 인증을 진행한다. 이번에는 실행하지 않았다. 기존 문서의 실험적 feature flag를 현재 CLI에 무조건 추가하지 않는다.

Issue ID/URL을 Codex에 전달하면 **Issue·수락 기준 조회 → 코드·공식 문서 조사 → 짧은 계획 → 범위 내 변경 → 빌드/테스트 → 결과 보고 → 승인된 Linear 업데이트** 순서로 작업한다. 계획은 Issue 본문/댓글, 명령·결과·미검증·commit/PR 링크는 결과 댓글에 남긴다. 게시 전에는 대화에 초안을 제시한다. 승인과 완료 기준, Git/PR 생성 시점은 [AGENTS.md](AGENTS.md#linear-중심-작업-절차)를 따른다. GitHub Issue로 같은 백로그를 복제하지 않는다.

### 외부 생성 제안 — 미승인, 미생성

최소 Project **`Vehicle MCP — 최소 PoC v0.1`** 1개를 `HYE` Team에 만들고, 처음에는 아래 Issue 1개만 둔다. Initiative·Milestone·새 Team은 필요하지 않다. 생성 후 ID/URL은 Linear를 원본으로 사용하고 이 초안을 별도 백로그로 유지하지 않는다.

**첫 Issue: `Vehicle SDK 버전 확정 및 기존 app 모듈 의존성 연결`**

- 목적/범위: 공식 문서의 2.3.1/2.3.2 차이를 확인하고 선택 근거를 기록한 뒤, 기존 프로젝트에 Pleos Maven과 Vehicle SDK 의존성만 연결한다.
- 예상 변경: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`. 버전 선택에 필요한 근거를 먼저 확인한다.
- 수락 기준: 공식 출처·문서 버전·선택 이유를 Issue에 기록하고 `dependencyInsight`로 해석된 버전을 확인한다. APK·JVM 테스트·Lint가 통과하며, Emulator/Service 호환성 및 API 런타임 검증 여부를 별도로 보고한다.
- 제외: UI·차량 API 호출·차량 제어·OpenAI·MCP 기능, AGP/Gradle/JDK 변경.

## 공식 문서와 Context7

API 사용 전 Context7에서 library ID를 resolve하고 정확한 API·권한·버전을 query한 다음, 반환된 공식 URL 원문을 직접 읽는다. 현재 확인한 ID는 **`/websites/document_pleos_ai_en`**이다. 요청 예: `Vehicle SDK getCurrentDoorPosition의 area, onComplete/onFailed 타입과 CAR_DOORS 권한 예제를 찾아줘. 문서 버전과 공식 원문 URL도 확인해줘.`

| 사용할 때 | 공식 출처 |
| --- | --- |
| 문서 입구 / 앱 개발 | [Pleos 문서](https://document.pleos.ai/), [시작 가이드](https://document.pleos.ai/en/docs/pleos-only/getting-started), [개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/intro) |
| Vehicle SDK | [소개·버전](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [의존성](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [권한](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [API Reference](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/entry) |
| NaviHelper | [소개·버전](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [초기화](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [Listener](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners) |
| 지원 여부 / 버전 변경 | [API 호환성](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability), [SDK 릴리스 노트](https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-261015) |
| 앱 심사 / 배포 준비 | [공식 앱 심사 제출 가이드](https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide) — 한글 원문 직접 확인 |
| 사용 예제 | [Vehicle 샘플 가이드](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app), [공식 Vehicle 저장소](https://github.com/Pleos-Playground/pleos-sample-vehicle-app), [공식 NaviHelper 저장소](https://github.com/Pleos-Playground/pleos-sample-navi-app) |

### 2026-10-26 검색 검증과 한계

- **등록·실제 검색 성공:** Pleos resolve 결과 879 code snippets. Vehicle 의존성/권한 검색, Door Position 조회 예제 검색, NaviHelper 초기화/Listener 검색을 수행했다. 라이브러리 전체 API의 정확성이나 완전성을 검증한 수치는 아니다.
- **공식 원문 대조:** 시작 가이드의 `getCurrentDoorPosition(area = DoorArea(DoorValue.ROW_1_LEFT), onComplete = { pos: Int? -> ... }, onFailed = { e: Exception -> ... })` 호출 형태·콜백 타입 및 `pleos.car.permission.CAR_DOORS`가 Context7 결과와 일치했다. 반환 타입을 포함한 정식 함수 선언 전체나 SDK 바이너리를 검증한 것은 아니다. 상세 API Reference 페이지는 텍스트 조회에서 제목/목차만 반환되어, 필요 시 브라우저에서 본문을 추가 확인해야 한다.
- **NaviHelper 대조:** `initialize(onConnected, onFailed)`와 인자 없는 `initialize()`, 동일 Listener 인스턴스 해제 후 `release()` 순서가 공식 초기화/Listener 원문과 일치했다. 소개 문서의 기준은 3.0.5이며 로컬 앱에는 미도입이다.
- **버전 고정 한계:** resolve 응답에 선택 가능한 `Versions` 목록이 없었다. SDK 버전별 검색을 정확히 고정할 수 있다고 검증하지 못했다. 시작 가이드·2026-10-19 릴리스 노트는 Vehicle **2.3.1**, Vehicle 소개·가져오기·샘플 가이드는 **2.3.2**였다. Service 기준도 소개 2.3.21 / 샘플 2.3.22로 다르다. 어느 것도 이 PC에서 확인한 설치 버전으로 취급하지 않는다.
- **샘플도 대조 필요:** 공식 Vehicle 저장소 `main` 조회에서 README는 SDK 2.3.2, `gradle/libs.versions.toml`은 2.3.1이었다. 두 파일의 blob SHA는 각각 `59d2b23eea415567dbfaa2cdbfb546c31e04e581`, `8125bf4a231ba1179a3f3ebbab482dc48e468276`. 적용 시 선택한 commit의 실제 의존성을 확인한다. 별도 샘플 library resolve에서는 해당 저장소 전용 ID가 반환되지 않았다. 등록되지 않았다고 단정할 수는 없다.
- **웹사이트 색인 가능:** 기존 영문 사이트 ID에서 조회가 이미 성공했다. 미등록 사이트는 [공식 Website 추가 API](https://context7.com/docs/api-reference/add-library/add-a-website)의 `websiteUrl`/`websiteBaseUrl`로 신청할 수 있으나 인증이 필요하다. 이번에 등록·재색인을 신청하지 않았다.
- **한글·샘플 색인:** [Context7 색인 안내](https://context7.com/docs/adding-libraries)는 주로 영어 문서를 처리하며 다른 언어 페이지를 건너뛴다고 설명한다. 코드 없는 심사·정책 문서는 원문 직접 조회를 기본으로 한다. 샘플 별도 색인은 사용 예제 탐색에 도움이 될 수 있으나 현재는 공식 GitHub 관련 파일 직접 조회로 충분하다. 문서 우선 색인과 소스 기반 예제 생성은 바이너리 분석이 아니며 API 명세를 대신하지 않는다.
- **최신성:** [갱신 안내](https://context7.com/docs/library-updates)에 따르면 사용 시 인기도/경과 시간에 따른 백그라운드 갱신이 가능하지만 현재 요청은 기존 색인을 반환할 수 있다. Pleos 색인의 마지막 갱신 시각·전체 페이지 수는 이번에 확인하지 못했다. 업데이트 직후에는 공식 원문을 직접 확인한다. 수동 refresh/외부 등록은 승인 후 수행한다.

## 이번 Harness 검증 결과

| 구분 | 실제 결과 |
| --- | --- |
| 빌드·로컬 검사 | `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: exit 0, BUILD SUCCESSFUL |
| JVM 테스트 | 기존 산술 테스트 1개, 실패/오류/skip 0. 차량 기능 검증 아님 |
| Lint | 오류 0, 기존 의존성 업데이트 경고 5개. 이번 범위에서 의존성은 변경하지 않음 |
| 기기 연결 / Pleos 실행 | adb 조회 성공, 연결 기기 0. instrumentation·UI·SDK/Service/API 실행 미검증 |
| MCP | 이 데스크톱 세션에서 Linear 일반 조회·Context7 docs 조회 성공. Linear 쓰기는 미실행 |
| AGENTS 적용 | 파일 내용·명령·링크를 읽어 확인하고 현재 작업에 적용. 다음 새 대화의 자동 주입은 별도 미검증; [공식 로딩 규칙](https://developers.openai.com/codex/guides/agents-md/) 참고 |
| 기존 파일 보존 | 문서 작성 전후 기존 파일 37개의 SHA-256 동일. 추가한 프로젝트 파일은 AGENTS.md·README.md 두 개뿐 |

로컬 개발 지침과 문서 검색·빌드 경로는 준비되었다. Linear 기준의 정식 착수에는 위 Project/첫 Issue 생성 승인이, GitHub 변경 이력 연결에는 Git 초기화와 원격 저장소 선택이 남아 있다. Vehicle SDK/API의 실제 동작은 이후 Issue에서 검증한다.
