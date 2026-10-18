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
| 1 | 문서, `tools/lg_tv_apps.py`, 빈 앱(WebView + 화면 골격), Actions 빌드 | 진행 |
| 2 | `WebOsClient` + 브리지: IP 입력 → 인증 → 앱 실행·볼륨·전원·방향키 | 예정 |
| 3 | `TvDiscovery`: TV 자동 찾기 | 예정 |
| 이후 | 다른 제조사(삼성 등)는 `TvClient` 인터페이스로 확장 | 미정 |

## 3. 브리지 API (2단계에서 구현, 이름은 이 문서 기준으로 고정)

### JS → Kotlin (`window.AndroidBridge`)
모든 메서드는 즉시 반환하고, 결과는 이벤트로 돌려준다. 인자와 결과는 JSON 문자열.

| 메서드 | 인자 | 결과 이벤트 |
|---|---|---|
| `appInfo()` | 없음 | 동기 반환: `{"version":"0.1.0","build":N}` |
| `discover()` | 없음 | `tvFound` (여러 번), `discoverDone` |
| `connect(ip)` | TV IP | `state` |
| `disconnect()` | 없음 | `state` |
| `request(reqId, uri, payloadJson)` | ssap 요청 | `response` |
| `button(name)` | `UP`, `ENTER` 등 | 없음 (실패 시 `error`) |
| `forget(ip)` | TV IP | 저장된 키 삭제 |

### Kotlin → JS (`window.onBridge(type, dataJson)`)

| type | data |
|---|---|
| `state` | `{"state":"connecting"|"prompt"|"pin"|"connected"|"closed"|"failed","ip":"...","message":"..."}` |
| `response` | `{"reqId":"...","ok":true,"payload":{...}}` / `{"reqId":"...","ok":false,"error":"..."}` |
| `tvFound` | `{"ip":"...","name":"...","model":"..."}` |
| `discoverDone` | `{"count":N}` |
| `error` | `{"message":"..."}` |

## 4. 저장 데이터 (SharedPreferences, 앱 내부)
- `tv_keys`: `{ip → client-key}`
- `tv_cert`: `{ip → TV 인증서 SHA-256}` (처음 접속 때 저장, 이후 다르면 경고 = TOFU 고정)
- `last_ip`: 마지막 TV
- 화면 설정(즐겨찾기 앱 순서 등)은 WebView `localStorage`

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
