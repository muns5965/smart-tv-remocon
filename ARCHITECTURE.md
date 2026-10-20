# ARCHITECTURE.md — 구조와 브리지 API

> 코드를 고치기 전에 이 문서와 [WEBOS_API.md](WEBOS_API.md)를 먼저 읽는다.
> 구조나 브리지 API가 바뀌면 이 문서를 같은 커밋에서 고친다.

## 1. 전체 구조

```
┌──────────────── Android 앱 (com.muns.tvremocon) ────────────────┐
│                                                                  │
│  WebView  ── assets/index.html (화면: TV 선택, 앱 타일, 방향키)   │
│     │  window.AndroidBridge.xxx()      ▲ window.onBridge(event)  │
│     ▼                                  │                         │
│  AndroidBridge (JS ↔ Kotlin)  ─────────┘                         │
│     │                                                            │
│     ├─ WebOsClient   wss://TV:3001  (OkHttp WebSocket)           │
│     │                 + 포인터 소켓 (방향키)                      │
│     ├─ TvDiscovery   SSDP 멀티캐스트로 TV 찾기                    │
│     └─ TvStore       TV별 client-key·인증서 지문 저장            │
│                      (SharedPreferences, 앱 전용·암호화 안 함)  │
└──────────────────────────────────────────────────────────────────┘
                 │ SSDP·wss가 통하는 로컬 네트워크 (IP 대역이 달라도 될 수 있음, WEBOS_API.md 6절)
                 ▼
            LG webOS TV
```

### 왜 이 구조인가
- WebView(웹 코드)는 TV의 자체 서명 인증서로 `wss://` 접속을 못 한다 (WEBOS_API.md 1절).
- 그래서 **통신은 전부 Kotlin**, 화면은 HTML로 나눈다. (WebView + JS 브리지 패턴)
- `index.html`은 `WebViewAssetLoader`로 `https://appassets.androidplatform.net/assets/index.html` 에서 연다.

## 2. 단계

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 문서, `tools/lg_tv_apps.py`, 빈 앱(WebView + 화면 골격), Actions 빌드 | 완료 |
| 2 | `WebOsClient` + 브리지: PIN 등록 → 앱 실행·볼륨·음소거·재생·전원 끄기·방향키·확인·뒤로·홈 | 완료 — **앱으로 실TV 확인** (2026-10-19) |
| 3 | `TvDiscovery`(SSDP): TV 자동 찾기 (2단계에 합침) | 완료 — 확인 도구로 실TV 확인, 앱 화면 표시는 확인 중 |
| 3+ | 설정 시트 정리, 터치패드(커서 이동·탭 클릭·스크롤) | 구현·자동 테스트 완료 — 포인터 형식은 확인 도구로 실TV 확인, **앱 터치패드는 실TV 확인 중** |
| 안정화 | 포인터 상태·요청 ID·FIFO, 세션 단일 잠금, 공유 OkHttp, 형식 검사 (검토 반영) | 구현·자동 테스트 완료 |
| 이후 | 앱 전체 목록, 문자 입력, 외부입력, 전원 켜기(WoL), 다른 제조사 | 미정 |

### 파일

| 파일 | 역할 | 안드로이드 의존 |
|---|---|---|
| `WebOsClient.kt` | wss 접속, 인증서 지문(TOFU), register, ssap 요청, 포인터 소켓 | 없음 (단위 테스트) |
| `SsdpSearch.kt` | SSDP 검색·응답 파싱·기기 설명 XML | 없음 (단위 테스트) |
| `TvDiscovery.kt` | SsdpSearch + 백그라운드 스레드 + MulticastLock | 있음 |
| `TvStore.kt` | TV별 키·인증서·이름 저장 (SharedPreferences) | 있음 |
| `TvMerge.kt` | 검색 결과를 저장 목록에 합치는 판단 (키 이전 규칙) | 없음 (단위 테스트) |
| `AndroidBridge.kt` | JS ↔ Kotlin, 위 클래스 연결 | 있음 |
| `assets/index.html` | 화면, 자동 연결 흐름 | — |

### 화면 구성 (index.html)
- 상단 한 줄: TV 이름 · 상태 · ⚙. 상태를 누르면 끊겼을 때 다시 연결, 연결돼 있으면 설정
- ⚙ 설정 시트: 저장된 TV(연결 / 두 번 눌러 등록 해제), TV 찾기, IP 직접 입력, 터치패드 커서 속도, 버전
- 가운데: [방향키 | 터치패드] 전환 (선택은 localStorage `ctrl_mode`)
  - 터치패드: 드래그 = 이동(속도 × 가속, 20ms마다 모아 전송), 탭 = 이동 1 → 30ms → 클릭, 두 손가락 위아래 24px마다 스크롤 ±10 (아래로 끌기 = 위쪽 내용)
  - 커서 속도 기본 1.5 (localStorage `cursor_speed`, 0.6~3.0)

### 자동 연결 흐름 (index.html)
1. 마지막 TV(`lastTv()`)가 있으면 바로 `connect(id)`
2. 실패하면 자동으로 `discover()` → 1대면 바로 연결, 여러 대면 선택 시트, 0대면 선택 시트(IP 직접 입력 안내)
3. 처음 실행이면 2번부터
4. 앱으로 돌아왔을 때 끊겨 있으면 다시 연결

## 3. 브리지 API (구현 기준)

### JS → Kotlin (`window.AndroidBridge`)
결과는 대부분 이벤트로 돌려준다. 인자·결과는 문자열(JSON).

| 메서드 | 인자 | 반환/결과 이벤트 |
|---|---|---|
| `appInfo()` | 없음 | 동기: `{"version","build","stage"}` |
| `savedTvs()` | 없음 | 동기: `[{"id","ip","name","model","known"}]` (키·인증서 값은 내보내지 않음) |
| `lastTv()` | 없음 | 동기: 마지막 TV id 또는 `""` |
| `discover()` | 없음 | `tvFound` (여러 번), `discoverDone` |
| `connect(id)` | 저장된 TV id. 새 등록이면 **PIN** 방식 | `state` |
| `connectPrompt(id)` | PIN이 안 뜨는 TV: 'TV에서 허용' 방식으로 다시 연결 | `state` |
| `connectIp(ip)` | IP 직접 입력 (IPv4·IPv6 표기만, 호스트 이름 불가) | `state` |
| `disconnect()` | 없음 | `state: closed` |
| `request(reqId, uri, payloadJson)` | ssap 요청 | `response` |
| `button(name)` | `UP` `DOWN` `LEFT` `RIGHT` `ENTER` `BACK` `HOME` … 포인터가 열리기 전이면 **FIFO 대기(최대 5, 차면 false)** | 동기: 전송/대기 성공 여부 |
| `pointerMove(dx, dy)` | 터치패드 커서 이동 (TV 단위, 화면 가로 ≈ 600~650). **대기하지 않음** — 포인터가 안 열렸으면 false | 동기 |
| `pointerTap()` | 탭 = 클릭. Kotlin이 **같은 세션·같은 포인터 연결**에서 이동(1,0) → 30ms → 클릭. 그 사이 TV가 바뀌면 클릭 안 함 | 동기 |
| `pointerClick()` | 단독 클릭 (TV가 무시하므로 보통 `pointerTap` 사용) | 동기 |
| `pointerScroll(dy)` | 스크롤. 10 단위, 양수 = 위. 대기하지 않음 | 동기 |
| `sendPin(pin)` | PIN 방식 TV | 실패 시 `state: pin` |
| `acceptCert(id)` | 인증서 바뀐 TV 다시 등록 | 저장된 **지문과 client-key 모두 삭제** → 다음 `connect`는 PIN 새 등록 |
| `forget(id)` | TV 삭제 | — |

### Kotlin → JS (`window.onBridge(type, dataJson)`)

| type | data |
|---|---|
| `state` | `{"state":"connecting"\|"prompt"\|"pin"\|"connected"\|"closed"\|"failed","id","ip","name","message","code"}` — `code:"cert_changed"`이면 인증서 경고 |
| `response` | `{"reqId","ok":true,"payload":{...}}` / `{"reqId","ok":false,"error":"..."}` |
| `tvFound` | `{"id","ip","name","model","uuid","known"}` |
| `discoverDone` | `{"count", "error"?}` |
| `error` | `{"message"}` — 방향키 소켓 실패도 여기로 |

### 연결 세션과 저장 (검토 반영)
- `WebOsClient`의 모든 콜백은 `connect()`에 준 **tag(=TV id)**를 돌려준다. 브리지는 키·지문 저장에 이 tag만 쓴다.
  → A에 연결 중 B로 바꿔도 A의 늦은 결과가 B에 저장되지 않는다. 화면도 현재 TV가 아닌 id의 `state`는 무시.
- 인증서 지문이 다르면 TLS 단계에서 끊으므로 저장된 client-key는 상대에게 전송되지 않는다.
- `response` 이벤트에도 TV id를 넣고, 화면은 TV를 바꿀 때 대기 요청을 정리하며 다른 TV의 응답은 무시한다.

### 위협 모델과 한계 (검토 반영, 정정)
- 대상: **일반 가정용 네트워크**. 같은 네트워크의 기기는 SSDP 응답을 얼마든지 흉내 낼 수 있으므로 SSDP 위조 대응은 '낮음' — 형식 검사로 잘못된 응답만 거른다.
- **PIN**: TV 화면을 직접 보는 사용자에게 실용적인 확인 수단이지만, 상대의 신원을 암호학적으로 보장하지는 않는다.
  **첫 등록 순간**에 가짜 기기가 앱과 진짜 TV 사이에서 중계하면, 진짜 TV에 PIN이 정상적으로 떠도 키가 넘어갈 수 있다 (비교할 지문이 아직 없음).
- **인증서 지문 고정(TOFU)**: 등록 **이후**에 상대가 바뀌는 것을 막는다. 바뀌면 키를 보내지 않고 사용자 확인 후 PIN으로 재등록.
- **포인터 소켓**: TV가 알려 준 주소의 스킴(wss)·호스트·포트가 현재 세션과 같을 때만 연다. 다르면 그 세션에서는 포인터를 차단(오류 1회).
- **SSDP 응답**: 첫 줄 `HTTP/1.x 200`, `ST` 정확히 일치, uuid는 `[0-9A-Za-z-]{1,64}` — 어긋나면 uuid 없이 `ip:` 새 기기로 취급(기존 TV의 키와 묶지 않음, `TvMerge`). LOCATION 256자 초과는 버리고 TV는 유지.
- **IP 직접 입력**은 사용자가 TV를 지목한 것이므로 같은 IP의 기존 항목을 쓴다 (상대가 바뀌었으면 지문 고정이 막음).

### 포인터 소켓 상태 (전체 소스 점검 반영)
- `IDLE → REQUESTING(주소 요청) → CONNECTING(소켓 여는 중) → OPEN`. IDLE일 때만 주소를 요청하고, REQUESTING일 때만 주소 응답을 처리 → 중복 응답에도 소켓 1개.
- 주소 요청마다 고유 ID(`ptr_1`, `ptr_2`, …)를 쓰고, **지금 기다리는 ID와 같은 응답만** 처리 → 이전 요청의 늦은 응답·중복 응답 무시.
- 포인터 연결마다 번호(`ptrGen`)를 붙이고, 열림·종료 콜백은 **현재 세션의 현재 번호**일 때만 상태를 바꿈 → 이전 소켓의 늦은 종료가 새 연결을 지우지 않음.
- 대기 버튼은 OPEN 전환과 **같은 잠금 안에서** 먼저 전송 큐에 넣음 → 열리는 순간 들어온 새 버튼이 대기 버튼을 앞지르지 않음 (OkHttp `send()`는 비차단 큐잉).
- TV가 포인터를 닫으면 IDLE로 돌아가고, 다음 버튼·이동 때 다시 요청.
- `request()`는 세션 확인과 전송을 한 번의 잠금 안에서 처리.
- OkHttp: 공유 기본 클라이언트 1개에서 세션마다 `newBuilder()`로 인증서 검사만 바꿔 파생 (디스패처·스레드·연결 풀 공유). 테스트: 닫힌 포트로 연결·해제를 50회 반복한 뒤 활성 스레드 증가가 10 미만인지 확인 (`Thread.activeCount()` 비교 — 모든 자원 해제를 증명하는 것은 아님).

### 기기 설명(LOCATION) 조회
- 안드로이드는 HTTP(cleartext)를 기본 차단한다. 앱 전체 정책은 차단 그대로 두고, 설명 XML만 **소켓으로 직접 GET**.
- 조건: `http`만, 호스트는 IP 표기이고 **SSDP 응답을 보낸 IP와 같을 때만**, 헤더 8KB·본문 64KB, 리디렉션 따르지 않음. 실패해도 TV는 이름 없이 목록에 남는다.
- 시간: 연결·헤더·본문을 합쳐 **전체 2초** (단조 시계 `nanoTime` 기준). 하나의 마감 시각으로 대기 시간을 남은 시간만큼만 주고, 공유 스케줄러(데몬 1개)에 "마감 시 소켓 닫기"를 예약해 절대 마감을 보장. 예약은 `finally`에서 취소.
- 검색은 3초 동안 응답만 모으고, 설명 XML은 그 뒤에 TV별로 받는다.

## 4. 저장 데이터 (SharedPreferences, 앱 내부)
- `tv_store/tvs`: `{id → {ip, name, model, uuid, key, cert}}`
  - id는 SSDP로 찾으면 `uuid:<값>`(IP가 바뀌어도 유지), 수동 입력이면 `ip:<IP>` → 나중에 SSDP로 찾으면 uuid 항목으로 키를 옮김
  - `cert`: 처음 접속 때 TV 인증서 SHA-256 저장, 이후 다르면 접속 중단 후 사용자 확인 (TOFU)
- `tv_store/last`: 마지막으로 연결된 TV id
- 화면 설정(이후 즐겨찾기 순서 등)은 WebView `localStorage`

## 5. 빌드와 서명
- 로컬 Android 환경 없이 **GitHub Actions**(`.github/workflows/build.yml`)로 빌드한다.
- Secrets 2개가 있으면 그 키로 서명한 release APK, 없으면 debug APK를 만든다.
  - `KEYSTORE_BASE64` (keystore 파일을 base64로), `KEYSTORE_PASSWORD`
  - alias는 `tvremocon`(워크플로에 고정), 키 비밀번호는 keystore 비밀번호와 같다.
- 이 앱 전용 키다 (다른 앱과 공유하지 않음). 원본 keystore는 저장소 밖에 백업한다.
- 서명 키를 고정해야 업데이트가 덮어쓰기로 설치된다. debug APK와 release APK는 서로 덮어쓸 수 없다.
- 결과 APK는 Actions 실행 화면의 Artifacts에서 받는다.
- `versionCode`는 `GITHUB_RUN_NUMBER`. **워크플로 파일을 새로 만들거나 이름을 바꾸면 번호가 1부터 다시 시작**해 덮어쓰기 설치가 막힌다 → 그럴 때는 `app/build.gradle.kts`에서 기준값을 더해 준다.
- 빌드 전에 단위 테스트(`testDebugUnitTest`)를 돌리고, 실패하면 원인을 Actions 주석(annotation)으로 남긴다.

## 6. 공개 저장소 주의
- 이 저장소는 public이다. TV IP, client-key, keystore, Secrets 값은 커밋하지 않는다 (`.gitignore`).
- 다른 회사 앱을 디컴파일한 코드는 넣지 않는다. 프로토콜 사실만 WEBOS_API.md에 정리한다.
