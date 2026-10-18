# -*- coding: utf-8 -*-
"""
LG webOS TV 설치 앱 ID 조회 (표준 라이브러리만 사용, pip 설치 불필요)

사용법
  python lg_tv_apps.py              -> IP를 물어봄
  python lg_tv_apps.py 192.168.0.10
  python lg_tv_apps.py 192.168.0.10 pin   -> PIN 방식 등록 (리모컨 없이)

PC(PyCharm)나 안드로이드 Pydroid 3 에서 그대로 실행하면 된다.
처음 실행 때 TV 화면에 뜨는 연결 요청에서 '허용'을 누른다.
"""
import base64
import json
import os
import socket
import ssl
import struct
import sys

DEFAULT_IP = ""   # 자주 쓰면 TV IP를 넣어 두세요 (예: "192.168.0.10")
PORT = 3001
PERMS = ["LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
         "CONTROL_INPUT_MEDIA_PLAYBACK", "UPDATE_FROM_REMOTE_APP", "CONTROL_POWER",
         "READ_INSTALLED_APPS", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
         "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TV", "READ_INPUT_DEVICE_LIST",
         "READ_NETWORK_STATE", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
         "READ_SETTINGS", "CONTROL_TV_SCREEN", "CONTROL_INPUT_TEXT",
         "CONTROL_MOUSE_AND_KEYBOARD", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS",
         "READ_POWER_STATE"]
OTT_WORDS = ["tving", "wavve", "pooq", "coupang", "disney", "netflix", "youtube",
             "watcha", "laftel", "amazon", "prime", "apple", "spotify",
             "티빙", "웨이브", "쿠팡", "디즈니", "넷플릭스", "유튜브", "왓챠", "라프텔"]


def base_dir():
    try:
        d = os.path.dirname(os.path.abspath(__file__))
        if os.access(d, os.W_OK):
            return d
    except Exception:
        pass
    return os.getcwd()


KEY_FILE = os.path.join(base_dir(), "lg_tv_key.json")
OUT_FILE = os.path.join(base_dir(), "lg_tv_apps.json")


# ---------------- 최소 WebSocket 클라이언트 ----------------
class WS:
    def __init__(self, host, port, timeout=10):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE          # TV는 자체 서명 인증서
        raw = socket.create_connection((host, port), timeout=timeout)
        self.s = ctx.wrap_socket(raw, server_hostname=host)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET / HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
               "Sec-WebSocket-Version: 13\r\n\r\n" % (host, port, key))
        self.s.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.s.recv(1)
            if not chunk:
                raise ConnectionError("핸드셰이크 중 연결 끊김")
            head += chunk
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise ConnectionError("WebSocket 거부: " + head.split(b"\r\n", 1)[0].decode("latin1"))

    def _recv_n(self, n):
        buf = b""
        while len(buf) < n:
            c = self.s.recv(n - len(buf))
            if not c:
                raise ConnectionError("연결 끊김")
            buf += c
        return buf

    def _send_frame(self, opcode, data):
        hdr = bytes([0x80 | opcode])
        n = len(data)
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        body = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.s.sendall(hdr + mask + body)

    def send(self, obj):
        self._send_frame(0x1, json.dumps(obj).encode("utf-8"))

    def recv(self, timeout=None):
        self.s.settimeout(timeout)
        msg = b""
        while True:
            b1, b2 = self._recv_n(2)
            op, n = b1 & 0x0F, b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv_n(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv_n(8))[0]
            mask = self._recv_n(4) if b2 & 0x80 else None
            data = self._recv_n(n)
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if op == 0x9:                       # ping -> pong
                self._send_frame(0xA, data)
                continue
            if op == 0x8:
                raise ConnectionError("TV가 연결을 닫음")
            if op in (0x0, 0x1, 0x2):
                msg += data
                if b1 & 0x80:
                    return json.loads(msg.decode("utf-8"))

    def close(self):
        try:
            self._send_frame(0x8, b"")
            self.s.close()
        except Exception:
            pass


# ---------------- 키 저장 ----------------
def load_key(ip):
    try:
        with open(KEY_FILE, encoding="utf-8") as f:
            return json.load(f).get(ip)
    except Exception:
        return None


def save_key(ip, key):
    data = {}
    try:
        with open(KEY_FILE, encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        pass
    data[ip] = key
    try:
        with open(KEY_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f)
    except Exception as e:
        print("  (키 저장 실패: %s — 다음에 다시 허용을 눌러야 함)" % e)


# ---------------- webOS 절차 ----------------
_seq = [0]


def request(ws, uri, payload=None):
    _seq[0] += 1
    rid = "req_%d" % _seq[0]
    m = {"id": rid, "type": "request", "uri": uri}
    if payload is not None:
        m["payload"] = payload
    ws.send(m)
    while True:
        r = ws.recv(timeout=10)
        if r.get("id") == rid:
            if r.get("type") == "error":
                raise RuntimeError(r.get("error"))
            p = r.get("payload") or {}
            if p.get("returnValue") is False:
                raise RuntimeError(p.get("errorText") or "오류")
            return p


def register(ws, ip, mode="PROMPT"):
    """mode: PROMPT = TV에서 '허용' 누르기, PIN = TV에 뜬 숫자를 여기 입력 (리모컨 불필요)
    PIN 모드는 저장된 키를 쓰지 않고 새로 등록한다 (PIN 지원 여부 확인용)."""
    payload = {"forcePairing": False, "pairingType": mode,
               "manifest": {"manifestVersion": 1, "appVersion": "1.0", "permissions": PERMS}}
    key = load_key(ip) if mode == "PROMPT" else None
    if key:
        payload["client-key"] = key
        print("저장된 키로 인증 중…")
    elif mode == "PIN":
        print("PIN 방식으로 등록 요청 → TV 화면에 숫자가 뜨는지 보세요 (60초 대기)")
    else:
        print(">>> TV 화면에서 '허용'을 눌러 주세요 (60초 대기)")
    ws.send({"id": "register_0", "type": "register", "payload": payload})
    got = ""                                  # TV가 실제로 고른 방식 (PIN / PROMPT)
    while True:
        r = ws.recv(timeout=60)
        rid, t = r.get("id"), r.get("type")
        p = r.get("payload") or {}
        if mode == "PIN":
            print("  [TV 응답] %s" % json.dumps(r, ensure_ascii=False)[:300])   # 디버그: PIN 흐름 확인용
        if rid == "pin_0":
            if t == "error" or p.get("returnValue") is False:
                print("PIN이 맞지 않습니다 (%s)" % (r.get("error") or p.get("errorText")))
                pin = input(">>> PIN 다시 입력 (엔터 = 중단): ").strip()
                if not pin:
                    raise RuntimeError("PIN 입력 중단")
                ws.send({"id": "pin_0", "type": "request", "uri": "ssap://pairing/setPin",
                         "payload": {"pin": pin}})
            continue
        if rid != "register_0":
            continue
        if t == "registered":
            k = p.get("client-key")
            if k and k != key:
                save_key(ip, k)
            if mode == "PIN":
                print("인증 완료 — %s\n" % ("PIN 방식 성공" if got == "PIN"
                                            else "TV가 '허용' 방식으로 처리함 (PIN 미지원 가능성)"))
            else:
                print("인증 완료\n")
            return
        if t == "response" and p.get("pairingType"):
            got = p.get("pairingType")
        if t == "response" and p.get("pairingType") == "PIN":
            pin = input(">>> TV 화면에 뜬 PIN 입력: ").strip()
            ws.send({"id": "pin_0", "type": "request", "uri": "ssap://pairing/setPin",
                     "payload": {"pin": pin}})
            continue
        if t == "response" and p.get("pairingType") == "PROMPT" and mode == "PIN":
            print("※ TV가 PIN 대신 '허용' 창을 띄웠습니다 → 이 TV는 PIN 방식을 지원하지 않을 수 있음")
            continue
        if t == "error":
            if key:
                save_key(ip, None)
                raise RuntimeError("저장된 키가 거부됨(%s). 다시 실행해서 허용을 눌러 주세요." % r.get("error"))
            raise RuntimeError("TV가 거부함: %s" % r.get("error"))


def get_apps(ws):
    try:
        p = request(ws, "ssap://com.webos.applicationManager/listLaunchPoints")
        items = p.get("launchPoints") or []
    except Exception as e:
        print("listLaunchPoints 실패(%s) → listApps 시도" % e)
        p = request(ws, "ssap://com.webos.applicationManager/listApps")
        items = [a for a in (p.get("apps") or []) if a.get("visible", True)]
    seen, apps = set(), []
    for a in items:
        i = a.get("id")
        if i and i not in seen:
            seen.add(i)
            apps.append({"title": a.get("title") or "", "id": i})

    def is_ott(a):
        s = (a["id"] + " " + a["title"]).lower()
        return any(w in s for w in OTT_WORDS)

    apps.sort(key=lambda a: (not is_ott(a), a["title"].lower()))
    for a in apps:
        a["ott"] = is_ott(a)
    return apps


def main():
    args = [a for a in sys.argv[1:] if a.lower() != "pin"]
    ip = args[0] if args else ""
    if not ip:
        ip = input("TV IP [%s]: " % DEFAULT_IP).strip() or DEFAULT_IP
    if not ip:
        print("TV IP를 입력하세요 (TV 설정 → 네트워크에서 확인)")
        return
    mode = "PIN" if "pin" in [a.lower() for a in sys.argv[1:]] else ""
    if not mode:
        sel = input("등록 방식 [1 = TV에서 허용(기본), 2 = PIN 입력]: ").strip()
        mode = "PIN" if sel == "2" else "PROMPT"
    print("접속: wss://%s:%d (%s)" % (ip, PORT, mode))
    try:
        ws = WS(ip, PORT)
    except Exception as e:
        print("접속 실패: %s\n- 폰/PC와 TV가 같은 Wi-Fi인지, TV가 켜져 있는지 확인하세요." % e)
        return
    try:
        register(ws, ip, mode)
        apps = get_apps(ws)
        print("%-4s %-28s %s" % ("번호", "이름", "앱 ID"))
        print("-" * 70)
        for n, a in enumerate(apps, 1):
            print("%-4d %-28s %s%s" % (n, a["title"][:28], a["id"], "   ★" if a["ott"] else ""))
        print("\n★ = OTT로 보이는 앱, 총 %d개" % len(apps))
        try:
            with open(OUT_FILE, "w", encoding="utf-8") as f:
                json.dump(apps, f, ensure_ascii=False, indent=2)
            print("저장: %s" % OUT_FILE)
        except Exception as e:
            print("파일 저장 실패: %s" % e)

        while True:
            s = input("\nTV에서 실행해 볼 번호 (엔터 = 종료): ").strip()
            if not s:
                break
            if not s.isdigit() or not 1 <= int(s) <= len(apps):
                print("번호를 다시 입력하세요")
                continue
            a = apps[int(s) - 1]
            try:
                request(ws, "ssap://system.launcher/launch", {"id": a["id"]})
                print("실행 요청: %s (%s)" % (a["title"], a["id"]))
            except Exception as e:
                print("실행 실패: %s" % e)
    except Exception as e:
        print("오류: %s" % e)
    finally:
        ws.close()


if __name__ == "__main__":
    main()
