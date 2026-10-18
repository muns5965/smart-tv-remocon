# smart-tv-remocon

같은 Wi-Fi에 있는 스마트 TV(우선 **LG webOS**)를 휴대폰으로 조작하는 안드로이드 앱.
IR(적외선)이 아니라 TV가 열어 둔 **네트워크 제어 API**(`wss://TV:3001`)를 쓴다.

- 구조와 브리지 API: [ARCHITECTURE.md](ARCHITECTURE.md)
- 확인된 프로토콜·앱 ID: [WEBOS_API.md](WEBOS_API.md)

## 지금 되는 것

### 1. 앱 ID 조회 도구 (`tools/lg_tv_apps.py`)
TV에 설치된 앱의 이름과 ID를 출력하고, 번호를 고르면 TV에서 그 앱을 실행한다.
표준 라이브러리만 쓰므로 pip 설치가 필요 없다.

- **PC**: `python tools/lg_tv_apps.py 192.168.0.10`
- **안드로이드**: Pydroid 3(무료, 시작 시 뜨는 Premium 화면은 닫으면 됨)에서 파일을 열고 실행

처음 실행 때 TV 화면에 뜨는 연결 요청에서 **허용**을 누른다. 키는 `lg_tv_key.json`에 저장되어 다음부터는 허용 없이 붙는다.

TV IP 확인: TV 설정 → 전체 설정 → 네트워크 → 연결된 Wi-Fi → 고급 설정.
`https://TV_IP:3001`을 브라우저로 열어 `Hello world`가 나오면 TV 쪽은 준비된 것이다.

### TV 찾기 확인 도구 (`tools/ssdp_find_tv.py`)
SSDP로 같은 Wi-Fi의 LG TV를 찾아 IP·이름·모델을 출력한다. 앱 3단계(자동 찾기) 전에 실제 TV 응답을 확인하는 디버그 도구.
안 나오면 `all` 옵션으로 다른 기기의 응답 여부를 본다 (공유기가 멀티캐스트를 막는지 판별).

### 2. 안드로이드 앱
- 켜면 마지막 TV에 자동 연결, 처음이면 같은 Wi-Fi에서 TV를 자동으로 찾는다 (IP 입력 불필요).
- 처음 연결 때 TV 화면에서 **허용** 한 번.
- 앱 바로 실행(YouTube·Netflix·TVING·wavve·쿠팡플레이·Disney+), 볼륨(길게 누르면 반복), 음소거, 재생/일시정지, 방향키·확인·뒤로·홈, 전원 끄기(두 번 눌러 확인).
- 첫 등록은 TV 화면에 뜬 숫자(PIN)만 입력하면 됨 (리모컨 불필요).
- **터치패드 모드**: 드래그로 TV 커서 이동, 탭으로 클릭, 두 손가락으로 스크롤 (매직 리모컨처럼).
- ⚙ 설정: 저장된 TV 연결·등록 해제, TV 찾기, IP 직접 입력, 커서 속도.
- `main`에 push하면 GitHub Actions가 단위 테스트 후 APK를 빌드한다 → Actions 실행 화면 → Artifacts에서 받기.

## 단계
1. 문서 · 조회 도구 · 빈 앱 · Actions 빌드 ✅
2. TV 연결: 앱 실행 · 볼륨 · 전원 · 방향키 ← 실TV 확인 중
3. TV 자동 찾기 (IP 입력 없이) ← 2단계에 합침, 실TV 확인 중
