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
│     └─ KeyStore      TV별 client-key, TV 인증서 지문 저장         │
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
| 2 | `WebOsClient` + 브리지: 인증 → 앱 실행·볼륨·음소거·재생·전원·방향키 | 진행 (실TV 확인 대기) |
| 3 | `TvDiscovery`(SSDP): TV 자동 찾기 — 실TV 응답 확인 후 2단계에 합침 | 진행 (실TV 확인 대기) |
| 3+ | 설정 시트 정리, 터치패드(커서 이동·클릭·스크롤) — 포인터 형식은 `tools/lg_pointer_test.py`로 실TV 확인 | 진행 (앱 실TV 확인 대기) |
| 이후 | 앱 전체 목록, 문자 입력, 외부입력, 전원 켜기(WoL), 다른 제조사 | 미정 |

### 파일

| 파일 | 역할 | 안드로이드 의존 |
|---|---|---|
| `WebOsClient.kt` | wss 접속, 인증서 지문(TOFU), register, ssap 요청, 포인터 소켓 | 없음 (단위 테스트) |
| `SsdpSearch.kt` | SSDP 검색·응답 파싱·기기 설명 XML | 없음 (단위 테스트) |
| `TvDiscovery.kt` | SsdpSearch + 백그라운드 스레드 + MulticastLock | 있음 |
| `TvStore.kt` | TV별 키·인증서·이름 저장 (SharedPreferences) | 있음 |
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
| `button(name)` | `UP` `DOWN` `LEFT` `RIGHT` `ENTER` `BACK` `HOME` … | 동기: 전송/대기열 성공 여부 |
| `pointerMove(dx, dy)` | 터치패드 커서 이동 (TV 단위, 화면 가로 ≈ 600~650) | 동기: 성공 여부 |
| `pointerClick()` | 클릭. **단독으로는 TV가 무시** → 화면이 `pointerMove(1,0)` 후 30ms 뒤 호출 | 동기 |
| `pointerScroll(dy)` | 스크롤. 10 단위, 양수 = 위 | 동기 |
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
  다시 등록하면 **진짜 TV 화면에만** PIN이 뜨므로, 숫자가 안 뜨면 다른 기기로 판단할 수 있다.

### 기기 설명(LOCATION) 조회
- 안드로이드는 HTTP(cleartext)를 기본 차단한다. 앱 전체 정책은 차단 그대로 두고, 설명 XML만 **소켓으로 직접 GET**.
- 조건: `http`만, 호스트는 IP 표기이고 **SSDP 응답을 보낸 IP와 같을 때만**, 2초·64KB 제한. 실패해도 TV는 이름 없이 목록에 남는다.
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
