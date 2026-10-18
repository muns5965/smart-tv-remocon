# -*- coding: utf-8 -*-
"""
SSDP로 같은 Wi-Fi의 LG webOS TV 찾기 확인용 (표준 라이브러리만 사용)

사용법
  python ssdp_find_tv.py          -> LG TV만 찾기
  python ssdp_find_tv.py all      -> 응답하는 모든 기기 보기 (LG TV가 안 나올 때)

앱 3단계(TvDiscovery)에 넣기 전에, 실제 TV가 어떤 응답을 주는지 확인하는 디버그 도구.
결과는 WEBOS_API.md 6절에 반영한다.
"""
import re
import socket
import sys
import time
import urllib.request

GROUP = ("239.255.255.250", 1900)
ST_LG = "urn:lge-com:service:webos-second-screen:1"
WAIT = 3.0


def my_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))       # 실제 전송 없음, 경로만 확인
        return s.getsockname()[0]
    except Exception:
        return "?"
    finally:
        s.close()


def search(st):
    msg = ("M-SEARCH * HTTP/1.1\r\n"
           "HOST: 239.255.255.250:1900\r\n"
           'MAN: "ssdp:discover"\r\n'
           "MX: 2\r\n"
           "ST: %s\r\n\r\n" % st).encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    s.bind(("", 0))
    s.settimeout(0.3)
    # UDP는 유실될 수 있어 3번 보낸다
    for _ in range(3):
        s.sendto(msg, GROUP)
        time.sleep(0.1)
    found = {}
    end = time.time() + WAIT
    while time.time() < end:
        try:
            data, addr = s.recvfrom(4096)
        except socket.timeout:
            continue
        except OSError:
            break
        text = data.decode("utf-8", "replace")
        headers = {}
        for line in text.split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().upper()] = v.strip()
        key = (addr[0], headers.get("ST", ""), headers.get("LOCATION", ""))
        if key not in found:
            found[key] = {"ip": addr[0], "headers": headers, "raw": text}
    s.close()
    return list(found.values())


def describe(location):
    """LOCATION의 기기 설명 XML에서 이름·모델 읽기"""
    try:
        with urllib.request.urlopen(location, timeout=3) as r:
            xml = r.read().decode("utf-8", "replace")
    except Exception as e:
        return {"error": str(e)}
    out = {}
    for tag in ("friendlyName", "manufacturer", "modelName", "modelNumber"):
        m = re.search(r"<%s>(.*?)</%s>" % (tag, tag), xml, re.S)
        if m:
            out[tag] = m.group(1).strip()
    return out


def main():
    want_all = len(sys.argv) > 1 and sys.argv[1].lower() == "all"
    st = "ssdp:all" if want_all else ST_LG
    print("내 IP: %s" % my_ip())
    print("검색: %s (%.0f초)\n" % (st, WAIT))
    res = search(st)
    if not res:
        print("응답 없음.")
        print("- 폰과 TV가 같은 Wi-Fi인지, TV가 켜져 있는지 확인")
        if not want_all:
            print("- 'all' 옵션으로 다른 기기라도 응답하는지 확인해 보세요 (공유기가 멀티캐스트를 막는지 판별)")
        return

    seen_loc = set()
    for n, r in enumerate(sorted(res, key=lambda x: x["ip"]), 1):
        h = r["headers"]
        print("[%d] %s" % (n, r["ip"]))
        for k in ("ST", "LOCATION", "SERVER", "USN"):
            if k in h:
                print("    %-12s %s" % (k, h[k]))
        loc = h.get("LOCATION")
        if loc and loc not in seen_loc:
            seen_loc.add(loc)
            d = describe(loc)
            for k, v in d.items():
                print("    %-12s %s" % (k, v))
        print()

    lg = [r for r in res if ST_LG in r["headers"].get("ST", "")]
    if lg:
        ips = sorted({r["ip"] for r in lg})
        print("LG webOS TV 발견: %s" % ", ".join(ips))
        print("→ 이 IP로 wss://IP:3001 접속 (lg_tv_apps.py로 확인 가능)")

    if "-raw" in sys.argv:
        print("\n===== 원본 응답 =====")
        for r in res:
            print(r["raw"])


if __name__ == "__main__":
    main()
