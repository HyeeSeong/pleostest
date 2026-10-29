# Vehicle MCP — Coding Agent 지침

## 목적과 범위
- 현대자동차그룹 Pleos 공모전용 최소 PoC v0.1. 장기 목표는 GPT가 Pleos 차량 데이터를 조회하고 그 결과로 응답할 수 있는지 검증하는 것이다.
- 명시적으로 요청받은 Linear Issue 범위만 구현한다.
- 환경·문서 조회 경로·연결 상태는 [README.md](README.md)를 참고한다. 계획과 수락 기준의 원본은 Linear이며 저장소에 별도 작업 목록을 복제하지 않는다.

## 코드 위치와 환경
- `app/`: 유일한 Android 모듈. `app/src/main/AndroidManifest.xml`, `res/`, 향후 `java/com/example/pleostest/`에 앱 코드를 둔다. 현재 main Kotlin/Java 소스와 Activity는 없다.
- `app/src/test/`: JVM 테스트. `app/src/androidTest/`: 실제 Android 기기가 필요한 instrumentation 테스트.
- `gradle/libs.versions.toml`: 의존성 버전. `settings.gradle.kts`: 저장소·모듈. `gradle/wrapper/`: Gradle 실행 기준.
- 기존 AGP 9.4.1 / Gradle 9.6.0 / JDK 25 설정을 유지한다. AGP 내장 Kotlin을 사용하므로 `org.jetbrains.kotlin.android`를 임의로 추가하지 않는다. Gradle 내장 Kotlin 버전과 앱 Kotlin 컴파일러 버전을 구분한다.
- Android/Pleos 도구를 재설치하거나 프로젝트를 다시 생성하지 않는다. SDK 설치와 앱의 SDK 의존성 연결은 별개다.

## Linear 중심 작업 절차
1. 사용자에게 받은 Issue 식별자(`HYE-번호`) 또는 URL로 Issue 본문·연결된 요구사항·수락 기준을 읽는다. `P-HYE-번호`는 Project다. Issue가 없으면 범위를 이 대화에 정리하고 생성 승인을 받는다.
2. Git 상태(저장소가 없으면 그 사실), 사용자 변경, 관련 코드와 공식 문서를 확인한다. 변경 예정 파일·작업 순서·검증 방법을 짧게 보고한다. 계획은 Issue 본문/댓글에 기록하며, 게시 승인이 없으면 대화에 초안을 남긴다.
3. 승인된 범위만 수정한다. 사용자 변경을 초기화·덮어쓰지 않는다. 범위 확대가 필요하면 먼저 설명한다.
4. 아래 명령과 변경에 맞는 검증을 수행한다. 오류가 환경 때문인지 코드 때문인지 구분하고, 실패한 검증을 통과로 기록하지 않는다.
5. 결과는 `변경 파일/이유 → 명령·종료 코드·결과 → 미검증·남은 일 → commit/PR 링크(있을 때)` 순서로 보고한다. SDK 사용 시 확인한 공식 URL·문서 버전·해석된 SDK 버전도 기록한다.
6. 승인받은 경우에만 Issue 상태/댓글을 업데이트한다. 실제 Team 상태를 조회하여 `Todo → In Progress → In Review → Done`을 사용한다. 수락 기준과 필요한 실행 검증·사용자 확인이 모두 충족되어야 Done이다. 기기 검증이 수락 기준인데 수행하지 못했으면 미완료로 남긴다.

## 공식 문서 사용
- Pleos API를 작성·수정하기 전에 공식 문서를 확인한다. [README의 문서 경로](README.md#공식-문서와-context7)를 출발점으로 사용한다.
- Context7에서 Pleos를 resolve한 후 반환된 `/websites/document_pleos_ai_en`의 docs를 query한다. API 이름·권한·버전을 구체적으로 질문하고, 반환된 공식 `document.pleos.ai` 원문을 직접 읽어 대조한다.
- 문서 버전, Gradle에서 해석된 SDK 버전, 에뮬레이터/Service 버전, 차량·area 지원 조건을 구분한다. 현재 공식 문서에도 Vehicle 2.3.1/2.3.2 차이가 있으므로 임의로 최신/호환을 단정하지 않는다.
- Context7이 실패하거나 필요한 내용이 없으면 공식 영문/한글 웹 문서와 공식 샘플의 관련 파일을 직접 읽는다. 샘플은 사용 패턴의 참고이며 버전·Gradle 설정 전체를 복사하지 않는다.
- 조회 결과가 호출 예제뿐이면 정식 함수 선언 전체를 검증했다고 쓰지 않는다. 확인할 수 없는 API·권한·최신성은 미확인으로 남긴다. 공개 문서 조회를 SDK 바이너리 분석이나 런타임 검증으로 표현하지 않는다.
- Context7은 기술 문서 검색용이다. 프로젝트 계획·Issue 관리에 사용하지 않는다. 문서 검색에 비밀정보나 비공개 코드를 보내지 않는다.

## Kotlin / Android / Pleos
- 기존 Kotlin DSL·패키지·모듈 구조를 존중한다. 의존성과 권한은 필요한 최소 범위로 추가한다.
- 차량 API는 지원 여부와 area, 최소 읽기 권한, 비동기 실패·null/unavailable을 처리한다. 실제 데이터가 없을 때 임의의 정상값으로 대체하지 않는다.
- SDK 초기화 반환, Service 연결, capability 성공, 값 수신은 서로 다른 결과다. UI 스레드를 막지 않고 Android 수명주기에 맞춰 listener 해제와 SDK release를 수행한다.
- 이번 PoC의 데이터 조회 목적을 벗어나는 차량 제어 API/권한은 추가하지 않는다. 실제 차량 조작·쓰기 테스트는 별도 승인을 받는다.

## 빌드와 검증
프로젝트 루트 PowerShell에서 실행한다. JDK 선택과 기기 명령은 [README.md](README.md#빌드와-검증-명령)를 따른다.

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

- APK 빌드, JVM 테스트, Lint, instrumentation, Pleos Emulator의 UI/Service/API 동작을 각각 보고한다. 빌드 성공으로 차량 조회 성공을 주장하지 않는다.
- 기기 검증은 연결된 대상 serial·AVD/API·Service/SDK 버전과 실행 항목을 기록한다. 일반 휴대전화 AVD를 Pleos 검증 대상으로 사용하지 않는다.
- 현재 예제 JVM 테스트는 산술 1건이다. PoC 기능 검증으로 계산하지 않는다. 문서만 바꿀 때는 링크·명령·기존 파일 보존을 확인하고 불필요한 반복 빌드/테스트를 추가하지 않는다.

## 승인과 변경 경계
- 사전 승인 없이 Linear Project/Issue/댓글/상태 등 외부 리소스를 생성·변경하지 않는다. 승인 범위가 명확하면 그 범위 안에서 진행하고 중복 확인하지 않는다.
- Git 초기화·원격 연결은 사용자 요청 후 진행한다. Git 사용 시 Issue 기반 branch를 쓰고, 검증·diff 확인 후 요청/승인된 시점에 commit한다. commit/PR에 Issue 식별자 또는 URL을 넣고 Linear에는 해당 commit/PR을 연결한다. push·PR 생성·merge·배포는 별도 승인 범위를 확인한다.
- SDK/AGP/Gradle/JDK 버전 변경, 대규모 리팩터링, CI/CD, Skill/Hook/Subagent/Compound Engineering 도입은 별도 요청 없이 수행하지 않는다.
- API key·토큰·keystore·개인 경로 설정을 저장소나 결과 로그에 넣지 않는다. `local.properties`는 기존 ignore를 유지한다. 전역 Codex/Claude 설정과 다른 프로젝트 설정을 임의로 변경하지 않는다.
