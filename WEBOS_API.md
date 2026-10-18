# WEBOS_API.md — LG webOS TV 제어 프로토콜 (확인된 사실만)

> 이 문서는 **실제 TV에서 확인했거나 공개 앱의 동작에서 확인한 내용만** 적는다.
> 추측은 적지 않는다. 새로 확인하면 "확인 방법"과 날짜를 같이 남긴다.
> 코드를 고치기 전에 이 문서와 [ARCHITECTURE.md](ARCHITECTURE.md)를 먼저 읽는다.

## 1. 접속

| 항목 | 값 | 비고 |
|---|---|---|
| 포트 | **3001** (`wss://`) | 최신 TV는 3000(`ws://`)을 닫아 둠. 3001만 응답 (2026-10-19 실TV 확인) |
| 인증서 | TV 자체 서명 인증서 | 일반 CA 검증 불가 → 클라이언트에서 검증을 끄거나 TV 인증서를 고정(pin)해야 함 |
| 동작 확인 | `https://TV_IP:3001` 접속 시 `Hello world` 응답 | 브라우저로 간단히 확인 가능 |
| 경로 | `/` | WebSocket 업그레이드 |

### 브라우저에서 직접 접속은 안 됨
- 로컬 HTML 파일에서 `wss://TV_IP:3001` 접속은 인증서 예외를 수락한 뒤에도 실패함 (2026-10-19, PC·모바일 크롬).
- 그래서 앱에서는 **통신을 Kotlin 쪽에서** 하고 WebView는 화면만 담당한다.

## 2. 인증 (register)

### 요청
```json
{
  "id": "register_0",
  "type": "register",
  "payload": {
    "forcePairing": false,
    "pairingType": "PROMPT",
    "client-key": "<저장된 키가 있으면>",
    "manifest": {
      "manifestVersion": 1,
      "appVersion": "1.0",
      "permissions": ["LAUNCH", "READ_INSTALLED_APPS", "CONTROL_AUDIO", "..."]
    }
  }
}
```
- 서명되지 않은 manifest(권한 목록만)로도 등록됨 (2026-10-19 실TV 확인).
- 사용한 권한 전체 목록은 [`tools/lg_tv_apps.py`](tools/lg_tv_apps.py)의 `PERMS`.

### 응답 흐름
1. `{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT"}}` → TV 화면에 허용 창이 뜸
2. 사용자가 TV에서 허용 → `{"type":"registered","id":"register_0","payload":{"client-key":"..."}}`
3. `client-key`를 저장해 두면 다음 접속부터 허용 창 없이 바로 `registered`

### PIN 방식 (리모컨 없이 등록) — 실TV 확인 (2026-10-19, OLED42C3KNA)
- 요청 payload의 `pairingType`을 `"PIN"`으로 보내면 TV 화면에 **숫자**가 뜬다 ('허용' 창 대신)
- TV 응답: `{"type":"response","id":"register_0","payload":{"pairingType":"PIN","returnValue":true}}`
- 앱이 `{"id":"pin_0","type":"request","uri":"ssap://pairing/setPin","payload":{"pin":"<숫자>"}}` 전송
- 맞으면 곧바로 `{"type":"registered","id":"register_0","payload":{"client-key":"..."}}`
- 확인 당시 PIN은 **8자리**. 다른 모델은 길이가 다를 수 있으므로 앱은 길이 제한을 두지 않는다
- 틀린 PIN 응답 형식은 실TV 미확인 (앱은 `type:error` 또는 `returnValue:false`를 둘 다 "틀림"으로 처리)
- **앱의 새 등록 기본값은 PIN**, PIN이 안 뜨는 TV는 PROMPT로 전환
- 저장된 키가 거부되면 `type: "error"` → 키를 지우고 다시 register

## 3. 요청 (ssap)

### 형식
```json
{"id": "req_1", "type": "request", "uri": "ssap://...", "payload": { }}
```
응답은 같은 `id`로 `{"type":"response","payload":{"returnValue":true, ...}}`.
실패는 `type: "error"` 또는 `payload.returnValue: false`.

### 확인된 URI

| 기능 | URI | payload | 확인 |
|---|---|---|---|
| 앱 목록(런처) | `ssap://com.webos.applicationManager/listLaunchPoints` | 없음 | 실TV ✅ 응답 `launchPoints[].id/title` |
| 앱 목록(전체) | `ssap://com.webos.applicationManager/listApps` | 없음 | 공개 앱 코드 |
| 앱 실행 | `ssap://system.launcher/launch` | `{"id":"<앱ID>"}` | 실TV ✅ |
| 볼륨 조회 | `ssap://audio/getVolume` | 없음 | 실TV ✅ (앱, 음소거 토글에 사용) |
| 음소거 | `ssap://audio/setMute` | `{"mute":true}` | 실TV ✅ (앱, 켜기·끄기) |
| 볼륨 업/다운 | `ssap://audio/volumeUp`, `ssap://audio/volumeDown` | 없음 | 실TV ✅ (앱) |
| 전원 끄기 | `ssap://system/turnOff` | 없음 | 실TV ✅ (앱) |
| 전원 상태 | `ssap://com.webos.service.tvpower/power/getPowerState` | 없음 | 공개 앱 코드 |
| 재생/일시정지 | `ssap://media.controls/play`, `.../pause` | 없음 | 실TV ✅ (앱) |
| 외부입력 목록 | `ssap://tv/getExternalInputList` | 없음 | 공개 앱 코드 |
| 외부입력 전환 | `ssap://tv/switchInput` | `{"inputId":"HDMI_1"}` | 공개 앱 코드 |
| 문자 입력 | `ssap://com.webos.service.ime/insertText` | `{"text":"...","replace":0}` | 공개 앱 코드 |
| 포인터 소켓 | `ssap://com.webos.service.networkinput/getPointerInputSocket` | 없음 | 실TV ✅ (앱, 아래 4절) |

"공개 앱 코드" = 플레이스토어 리모컨 앱(com.eltacrew.tvremote) 분석에서 확인. 실TV 확인 후 ✅로 바꾼다.
"실TV ✅ (앱)" = 2026-10-19 이 앱 release-11로 OLED42C3KNA에서 확인.

## 4. 방향키·확인·뒤로 (포인터 소켓)

1. `getPointerInputSocket` 응답의 `payload.socketPath`(wss 주소)로 **두 번째 WebSocket**을 연다.
2. 버튼은 JSON이 아닌 **줄바꿈 텍스트**로 보낸다:
```
type:button
name:UP

```
- 실TV ✅ (2026-10-19, 앱): `UP` `DOWN` `LEFT` `RIGHT` `ENTER` `BACK` `HOME` 동작 확인
- 버튼 이름: `UP` `DOWN` `LEFT` `RIGHT` `ENTER` `BACK` `HOME` `EXIT` `MENU` `INFO` `LIST` `0`~`9` `DASH` `RED` `GREEN` `YELLOW` `BLUE` (공개 앱 코드)
- 클릭: `type:click\n\n` **단독으로는 실TV 무반응** (2026-10-19). 직전에 `type:move`를 보내야 동작:
  `type:move\ndx:1\ndy:0\ndown:0\n\n` → 30ms 뒤 `type:click\n\n` — 실TV ✅ (`lg_pointer_test.py`의 `click2`)
  → 앱은 탭할 때 항상 이 순서로 보낸다 (커서가 숨겨진 상태에서 클릭이 무시되는 것으로 추정)
- 커서가 보이는 상태에서 `type:button\nname:ENTER\n\n`(확인 버튼)도 커서 위치를 선택 — 실TV ✅ (`enter`)
  → 리모컨의 '확인'은 커서가 보일 때 클릭과 같은 효과 (매직 리모컨과 동일)
- 스크롤: `type:scroll\ndx:0\ndy:<n>\n\n` — 실TV ✅ (2026-10-19)
  - `dy:3`은 무반응, **`dy:10`에서 반응** → 작은 값은 무시되는 것으로 보임. 앱은 10 단위로 보낸다
  - **양수 = 위쪽**(리모컨 휠을 앞으로 돌린 것과 같음), 음수 = 아래쪽
- 이동 배율: 150씩 4번(=600)에 화면 가로 거의 끝까지 → 화면 가로 ≈ 600~650 단위 (실TV)
- 커서 이동: `type:move\ndx:<n>\ndy:<n>\ndown:0\n\n` — 실TV ✅ (2026-10-19, `tools/lg_pointer_test.py`: 10씩 15번 → 네모 그림)
- 실TV의 `socketPath` 형식: `wss://<TV_IP>:3001/resources/<고유값>/netinput.pointer.sock` (같은 3001 포트, 별도 경로)

## 5. 앱 ID (2026-10-19 실TV `listLaunchPoints` 결과)

| 앱 | ID |
|---|---|
| YouTube | `youtube.leanback.v4` |
| YouTube Kids | `youtube.leanback.kids.v4` |
| Netflix | `netflix` |
| Disney+ | `com.disney.disneyplus-prod` |
| TVING | `cj.eandm` |
| wavve | `pooq` |
| Coupang Play | `coupangplay` |
| Prime Video | `amazon` |
| Apple TV | `com.apple.appletv` |
| Spotify | `spotify-beehive` |
| Melon | `lg-tv.melon.com` |
| 인터넷(브라우저) | `com.webos.app.browser` |
| 현재 방송 | `com.webos.app.livetv` |
| HDMI 입력 n | `com.webos.app.hdmi<n>` (입력 이름을 바꿔도 ID는 그대로) |

앱 ID는 TV·지역·펌웨어마다 다를 수 있다. **앱에서는 고정값 대신 `listLaunchPoints` 결과를 기준**으로 하고, 위 표는 즐겨찾기 기본값으로만 쓴다.

## 6. TV 찾기 (SSDP) — 실TV 확인 (2026-10-19, `tools/ssdp_find_tv.py`)

- `M-SEARCH * HTTP/1.1` 을 `239.255.255.250:1900`로 멀티캐스트 (MX: 2, 3번 반복 전송)
- `ST: urn:lge-com:service:webos-second-screen:1`
- TV는 요청 보낸 포트로 **유니캐스트 응답** → 응답의 송신 주소 = TV IP
- 실제 응답 (OLED42C3KNA):
```
ST: urn:lge-com:service:webos-second-screen:1
LOCATION: http://192.168.8.219:1474/
SERVER: WebOS/4.1.0 UPnP/1.0
USN: uuid:<기기 고유값>::urn:lge-com:service:webos-second-screen:1
```
- `LOCATION`의 XML에서 `friendlyName` = `[LG] webOS TV OLED42C3KNA`, `modelNumber` = `OLED42C3KNA`
- LOCATION 포트(1474)는 TV마다 다를 수 있으므로 고정값으로 쓰지 않는다.
- `USN`의 uuid는 TV 고유값 → IP가 바뀌어도 같은 TV인지 구별하는 키로 쓸 수 있다.

### 주의: 폰과 TV의 IP 대역이 다를 수 있음
- 확인 당시 폰 IP는 `10.0.0.50`, TV는 `192.168.8.219` (대역이 다른데도 SSDP·wss 모두 동작)
- 따라서 "폰 IP 기준 같은 /24 대역 훑기"는 TV를 못 찾을 수 있다. 보조 수단은 **마지막 IP 기억 + 직접 입력**을 우선한다.

### Android 구현 메모 (공개 앱 코드 기준, 앱에서 재확인 필요)
- 응답이 유니캐스트라 `MulticastLock` 없이도 받을 가능성이 높지만, 기기별 차이를 막기 위해 검색 동안만 잡는다.
- `CHANGE_WIFI_MULTICAST_STATE` 권한은 매니페스트에 이미 있음.
