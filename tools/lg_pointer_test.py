# -*- coding: utf-8 -*-
"""
LG webOS TV 커서(포인터) 이동 확인용 디버그 도구 (표준 라이브러리만 사용)

사용법
  python lg_pointer_test.py              -> IP를 물어봄
  python lg_pointer_test.py 192.168.0.10

1) 저장된 키(lg_tv_key.json, lg_tv_apps.py가 만든 것)로 접속. 없으면 PIN으로 새로 등록
2) 자동 테스트: 커서를 네모 모양으로 이동
3) 수동 테스트: d/a/w/s 거리, wheel 양, click, q

확인할 것: 커서가 뜨는지, dx 1이 화면에서 얼마인지, 스크롤 방향.
결과는 WEBOS_API.md 4절과 앱 터치패드 모드에 반영한다.
"""
import base64
import json
import os
import socket
import ssl
import struct
import sys
import time
from urllib.parse import urlparse

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


def key_paths():
    """lg_tv_apps.py가 저장한 키 파일 후보 (스크립트 폴더, 현재 폴더)"""
    out = []
    try:
        out.append(os.path.dirname(os.path.abspath(__file__)))
    except Exception:
        pass
    out.append(os.getcwd())
    return [os.path.join(d, "lg_tv_key.json") for d in out]


def load_key(ip):
    for p in key_paths():
        try:
            with open(p, encoding="utf-8") as f:
                k = json.load(f).get(ip)
                if k:
                    return k
        except Exception:
            pass
    return None


def save_key(ip, key):
    for p in key_paths():
        try:
            data = {}
            if os.path.exists(p):
                with open(p, encoding="utf-8") as f:
                    data = json.load(f)
            data[ip] = key
            with open(p, "w", encoding="utf-8") as f:
                json.dump(data, f)
            return
        except Exception:
            continue


class WS:
    """최소 WebSocket 클라이언트 (ws:// 와 wss:// 둘 다, 경로 지정 가능)"""

    def __init__(self, url, timeout=10):
        u = urlparse(url)
        host, port = u.hostname, u.port or (443 if u.scheme == "wss" else 80)
        path = (u.path or "/") + (("?" + u.query) if u.query else "")
        raw = socket.create_connection((host, port), timeout=timeout)
        if u.scheme == "wss":
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE          # TV 자체 서명 인증서
            raw = ctx.wrap_socket(raw, server_hostname=host)
        self.s = raw
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
               "Sec-WebSocket-Version: 13\r\n\r\n" % (path, host, port, key))
        self.s.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            c = self.s.recv(1)
            if not c:
                raise ConnectionError("핸드셰이크 중 연결 끊김")
            head += c
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

    def send_text(self, text):
        data = text.encode("utf-8")
        hdr = bytes([0x81])
        n = len(data)
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        self.s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def send(self, obj):
        self.send_text(json.dumps(obj))

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
            if op == 0x9:
                hdr = bytes([0x8A, 0x80 | len(data)])
                m = os.urandom(4)
                self.s.sendall(hdr + m + bytes(b ^ m[i % 4] for i, b in enumerate(data)))
                continue
            if op == 0x8:
                raise ConnectionError("TV가 연결을 닫음")
            msg += data
            if b1 & 0x80:
                return json.loads(msg.decode("utf-8"))

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


def register(ws, ip):
    key = load_key(ip)
    payload = {"forcePairing": False, "pairingType": "PIN",
               "manifest": {"manifestVersion": 1, "appVersion": "1.0", "permissions": PERMS}}
    if key:
        payload["client-key"] = key
        print("저장된 키로 인증 중…")
    ws.send({"id": "register_0", "type": "register", "payload": payload})
    while True:
        r = ws.recv(timeout=60)
        rid, t, p = r.get("id"), r.get("type"), r.get("payload") or {}
        if rid == "pin_0" and (t == "error" or p.get("returnValue") is False):
            pin = input("PIN이 틀림. 다시 입력: ").strip()
            ws.send({"id": "pin_0", "type": "request", "uri": "ssap://pairing/setPin", "payload": {"pin": pin}})
            continue
        if rid != "register_0":
            continue
        if t == "registered":
            k = p.get("client-key")
            if k and k != key:
                save_key(ip, k)
            print("인증 완료\n")
            return
        if t == "response" and p.get("pairingType") == "PIN":
            pin = input(">>> TV 화면에 뜬 숫자 입력: ").strip()
            ws.send({"id": "pin_0", "type": "request", "uri": "ssap://pairing/setPin", "payload": {"pin": pin}})
        elif t == "response" and p.get("pairingType") == "PROMPT":
            print(">>> TV 화면에서 '허용'을 눌러 주세요")
        elif t == "error":
            raise RuntimeError("TV가 거부함: %s" % r.get("error"))


def pointer_socket(ws):
    ws.send({"id": "ptr_0", "type": "request",
             "uri": "ssap://com.webos.service.networkinput/getPointerInputSocket"})
    while True:
        r = ws.recv(timeout=10)
        if r.get("id") == "ptr_0":
            print("  [TV 응답] %s" % json.dumps(r, ensure_ascii=False)[:300])
            path = (r.get("payload") or {}).get("socketPath")
            if not path:
                raise RuntimeError("포인터 소켓 주소를 받지 못함")
            return WS(path)


def move(p, dx, dy, steps=1, delay=0.03):
    """한 번에 크게 보내지 않고 나눠서 보냄 (손가락 드래그와 비슷하게)"""
    sx, sy = dx / float(steps), dy / float(steps)
    ax = ay = 0.0
    for _ in range(steps):
        ax += sx
        ay += sy
        ix, iy = int(round(ax)), int(round(ay))
        ax -= ix
        ay -= iy
        p.send_text("type:move\ndx:%d\ndy:%d\ndown:0\n\n" % (ix, iy))
        time.sleep(delay)


def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else ""
    if not ip:
        ip = input("TV IP [%s]: " % DEFAULT_IP).strip() or DEFAULT_IP
    if not ip:
        print("TV IP를 입력하세요")
        return
    print("접속: wss://%s:%d" % (ip, PORT))
    ws = WS("wss://%s:%d/" % (ip, PORT))
    p = None
    try:
        register(ws, ip)
        p = pointer_socket(ws)
        print("포인터 소켓 연결됨\n")

        input("자동 테스트: 엔터를 누르고 TV 화면을 보세요 (커서가 네모를 그림)")
        for dx, dy, name in ((150, 0, "오른쪽"), (0, 150, "아래"), (-150, 0, "왼쪽"), (0, -150, "위")):
            print("  %s 150" % name)
            move(p, dx, dy, steps=15)
            time.sleep(0.4)
        print("자동 테스트 끝. 커서가 네모를 그렸나요?\n")

        print("수동 테스트 명령:")
        print("  d 50 / a 50 / s 50 / w 50  → 오른쪽/왼쪽/아래/위로 50 이동")
        print("  wheel 3 / wheel -3          → 스크롤 (방향 확인)")
        print("  click                       → 현재 위치 클릭 (주의)")
        print("  enter                       → 확인 버튼 (커서가 보일 때 커서 위치 선택되는지)")
        print("  press                       → 누름·뗌 방식 클릭 (move down:1 → down:0)")
        print("  click2                      → click을 move 직후에 보냄")
        print("  q                           → 종료")
        dirs = {"d": (1, 0), "a": (-1, 0), "s": (0, 1), "w": (0, -1)}
        while True:
            cmd = input("> ").strip().split()
            if not cmd or cmd[0] == "q":
                break
            c = cmd[0].lower()
            n = int(cmd[1]) if len(cmd) > 1 and cmd[1].lstrip("-").isdigit() else 50
            if c in dirs:
                move(p, dirs[c][0] * n, dirs[c][1] * n, steps=max(1, abs(n) // 10))
            elif c == "wheel":
                p.send_text("type:scroll\ndx:0\ndy:%d\n\n" % n)
            elif c == "click":
                p.send_text("type:click\n\n")
            elif c == "enter":
                p.send_text("type:button\nname:ENTER\n\n")
            elif c == "press":
                p.send_text("type:move\ndx:0\ndy:0\ndown:1\n\n")
                time.sleep(0.08)
                p.send_text("type:move\ndx:0\ndy:0\ndown:0\n\n")
            elif c == "click2":
                p.send_text("type:move\ndx:1\ndy:0\ndown:0\n\n")
                time.sleep(0.03)
                p.send_text("type:click\n\n")
            else:
                print("모르는 명령")
    except Exception as e:
        print("오류: %s" % e)
    finally:
        if p:
            p.close()
        ws.close()


if __name__ == "__main__":
    main()
