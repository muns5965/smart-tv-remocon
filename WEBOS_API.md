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

- `pairingType: "PIN"`이 오는 TV도 있음 → `ssap://pairing/setPin` `{"pin":"..."}`로 응답 (실TV 미확인, 공개 앱 코드에서 확인)
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
| 볼륨 조회 | `ssap://audio/getVolume` | 없음 | 공개 앱 코드 |
| 음소거 | `ssap://audio/setMute` | `{"mute":true}` | 공개 앱 코드 |
| 볼륨 업/다운 | `ssap://audio/volumeUp`, `ssap://audio/volumeDown` | 없음 | 공개 앱 코드 |
| 전원 끄기 | `ssap://system/turnOff` | 없음 | 공개 앱 코드 |
| 전원 상태 | `ssap://com.webos.service.tvpower/power/getPowerState` | 없음 | 공개 앱 코드 |
| 재생/일시정지 | `ssap://media.controls/play`, `.../pause` | 없음 | 공개 앱 코드 |
| 외부입력 목록 | `ssap://tv/getExternalInputList` | 없음 | 공개 앱 코드 |
| 외부입력 전환 | `ssap://tv/switchInput` | `{"inputId":"HDMI_1"}` | 공개 앱 코드 |
| 문자 입력 | `ssap://com.webos.service.ime/insertText` | `{"text":"...","replace":0}` | 공개 앱 코드 |
| 포인터 소켓 | `ssap://com.webos.service.networkinput/getPointerInputSocket` | 없음 | 공개 앱 코드 (아래 4절) |

"공개 앱 코드" = 플레이스토어 리모컨 앱(com.eltacrew.tvremote) 분석에서 확인. 실TV 확인 후 ✅로 바꾼다.

## 4. 방향키·확인·뒤로 (포인터 소켓)

1. `getPointerInputSocket` 응답의 `payload.socketPath`(wss 주소)로 **두 번째 WebSocket**을 연다.
2. 버튼은 JSON이 아닌 **줄바꿈 텍스트**로 보낸다:
```
type:button
name:UP

```
- 버튼 이름: `UP` `DOWN` `LEFT` `RIGHT` `ENTER` `BACK` `HOME` `EXIT` `MENU` `INFO` `LIST` `0`~`9` `DASH` `RED` `GREEN` `YELLOW` `BLUE` (공개 앱 코드)
- 클릭: `type:click\n\n`, 스크롤: `type:scroll\ndx:0\ndy:<n>\n\n`

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

## 6. TV 찾기 (SSDP) — 3단계에서 구현 예정

- `M-SEARCH * HTTP/1.1` 을 `239.255.255.250:1900`로 멀티캐스트
- `ST: urn:lge-com:service:webos-second-screen:1`
- 응답 헤더의 `LOCATION` 호스트 = TV IP
- Android는 `WifiManager.MulticastLock` 과 `CHANGE_WIFI_MULTICAST_STATE` 권한 필요
- (공개 앱 코드에서 확인, 실TV 미확인)
