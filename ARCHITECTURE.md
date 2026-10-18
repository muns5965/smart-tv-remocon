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
                 │ 같은 Wi-Fi
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
| `connect(id)` | 저장된 TV id | `state` |
| `connectIp(ip)` | IP 직접 입력 | `state` |
| `disconnect()` | 없음 | `state: closed` |
| `request(reqId, uri, payloadJson)` | ssap 요청 | `response` |
| `button(name)` | `UP` `DOWN` `LEFT` `RIGHT` `ENTER` `BACK` `HOME` … | 동기: 전송/대기열 성공 여부 |
| `sendPin(pin)` | PIN 방식 TV | 실패 시 `state: pin` |
| `acceptCert(id)` | 인증서 바뀐 TV를 같은 TV로 인정 | 저장된 지문 삭제 |
| `forget(id)` | TV 삭제 | — |

### Kotlin → JS (`window.onBridge(type, dataJson)`)

| type | data |
|---|---|
| `state` | `{"state":"connecting"\|"prompt"\|"pin"\|"connected"\|"closed"\|"failed","id","ip","name","message","code"}` — `code:"cert_changed"`이면 인증서 경고 |
| `response` | `{"reqId","ok":true,"payload":{...}}` / `{"reqId","ok":false,"error":"..."}` |
| `tvFound` | `{"id","ip","name","model","uuid","known"}` |
| `discoverDone` | `{"count", "error"?}` |
| `error` | `{"message"}` |

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

## 6. 공개 저장소 주의
- 이 저장소는 public이다. TV IP, client-key, keystore, Secrets 값은 커밋하지 않는다 (`.gitignore`).
- 다른 회사 앱을 디컴파일한 코드는 넣지 않는다. 프로토콜 사실만 WEBOS_API.md에 정리한다.
