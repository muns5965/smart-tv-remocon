"""단위 테스트 실패를 Actions annotation으로 출력 (JUnit XML 결과 읽기)"""
import glob
import xml.etree.ElementTree as ET

for path in glob.glob("app/build/test-results/**/*.xml", recursive=True):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        for f in list(case.iter("failure")) + list(case.iter("error")):
            msg = (f.get("message") or f.text or "").replace("\n", " ")[:400]
            print("::error::TEST %s.%s — %s" % (case.get("classname", "").split(".")[-1], case.get("name"), msg))
