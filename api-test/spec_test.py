"""API 명세 v1.0 기준 실제 서버 테스트 (A: catalog·timeseries, B: spatial·insight).

떠 있는 서버에 HTTP로 요청해 명세 1장(공통 규약)·2장(코드값)·3장(엔드포인트 필드)·5장(빈 상태)·7장(응답 예시)을 확인한다.
표준 라이브러리만 쓴다 (Python 3.9+).

    python api-test/spec_test.py                                      # 로컬 (catalog :8081, timeseries :8082)
    python api-test/spec_test.py --base https://burnline-api.unzipp.cloud   # 터널 (경로별로 알아서 나뉨)
    python api-test/spec_test.py --sweep                              # + 모든 구역을 돌며 5xx·코드값 검사

결과: 화면 요약 + api-test/report.md
"""
import argparse
import concurrent.futures as cf
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CATALOG = ("meta", "regions", "geo")
TIMESERIES = ("series", "baseline", "judgement", "export.csv")
B_ENDPOINTS = ("map", "events", "official-fires", "insight", "top", "landing")

UISEONG = "91817680B75376031789242"
GOSEONG = "91817680B50485812736392"
BUTTE = "52423323B88547646561946"

ENUMS = {
    "level": {"nat", "prov", "dist"},
    "chip": {"record", "above", "normal", "below", None},
    "region_type": {"sufficient", "sparse", "none"},
    "judge_unit": {"self", "province", "nearby_province"},
    "quality": {"high", "medium", "low", None},
    "estimated": {True, False},
    "source": {"NOAA-20", "S-NPP→NOAA-20", "MODIS→NOAA-20", "Terra→NOAA-20", None},
    "status": {"final", "partial", "nrt"},
    "basis": {"harmonized_25y", "viirs_2012"},
    "reason": {"PRE_2012_DISTRICT", "NO_DETECTION", "ZERO_BASELINE", "IN_PROGRESS", "NO_RECORD", None},
}


# --------------------------------------------------------------------------- HTTP
class Resp:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.raw = status, {k.lower(): v for k, v in headers.items()}, body

    @property
    def text(self):
        return self.raw.decode("utf-8")

    def json(self):
        return json.loads(self.raw.decode("utf-8"))


class Client:
    def __init__(self, base, catalog, timeseries, spatial, insight, timeout=20, b_enabled=True):
        self.base, self.timeout, self.b_enabled = base, timeout, b_enabled
        self.hosts = {"catalog": catalog, "timeseries": timeseries, "spatial": spatial, "insight": insight}
        self.calls = 0

    def url(self, path):
        if self.base:
            return self.base.rstrip("/") + path
        seg = path.split("?")[0].split("/")
        name = seg[4] if len(seg) > 4 else ""
        if name in TIMESERIES or path.startswith("/internal/judge"):
            host = self.hosts["timeseries"]
        elif name in ("map", "events", "official-fires"):
            host = self.hosts["spatial"]
        elif name in ("insight", "top", "landing"):
            host = self.hosts["insight"]
        else:
            host = self.hosts["catalog"]
        return host.rstrip("/") + path

    def get(self, path, headers=None):
        self.calls += 1
        parts = urllib.parse.urlsplit(self.url(path))
        url = urllib.parse.urlunsplit((parts.scheme, parts.netloc, urllib.parse.quote(parts.path),
                                       urllib.parse.quote(parts.query, safe="=&"), ""))
        req = urllib.request.Request(url, headers=dict(headers or {}, **{"User-Agent": "burnline-spec-test"}))
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return Resp(r.status, dict(r.headers), r.read())
        except urllib.error.HTTPError as e:
            return Resp(e.code, dict(e.headers), e.read())


# --------------------------------------------------------------------------- 결과 기록
class Report:
    def __init__(self):
        self.rows = []   # (section, name, status, detail)

    def add(self, section, name, ok, detail=""):
        self.rows.append((section, name, "PASS" if ok else "FAIL", detail))

    def skip(self, section, name, detail):
        self.rows.append((section, name, "SKIP", detail))

    def note(self, section, name, detail):
        """명세 예시가 명세 규칙과 어긋나서 규칙 쪽을 따른 곳"""
        self.rows.append((section, name, "NOTE", detail))

    def check(self, section, name, fn):
        try:
            detail = fn()
            self.add(section, name, True, detail or "")
        except AssertionError as e:
            self.add(section, name, False, str(e))
        except Exception as e:  # noqa: BLE001
            self.add(section, name, False, "%s: %s" % (type(e).__name__, e))

    def counts(self):
        c = {"PASS": 0, "FAIL": 0, "SKIP": 0, "NOTE": 0}
        for r in self.rows:
            c[r[2]] += 1
        return c


def eq(actual, expected, what):
    assert actual == expected, "%s: 기대 %r, 실제 %r" % (what, expected, actual)


def has_keys(obj, keys, what):
    missing = [k for k in keys if k not in obj]
    assert not missing, "%s: 필드 없음 %s" % (what, missing)


def ok_json(r, what):
    assert r.status == 200, "%s: HTTP %s %s" % (what, r.status, r.text[:200])
    return r.json()


def decimals(x):
    s = repr(float(x))
    return 0 if "e" in s else len(s.split(".")[1].rstrip("0"))


def coords(node, out):
    if isinstance(node, list) and node and isinstance(node[0], (int, float)):
        out.extend(node)
    elif isinstance(node, list):
        for n in node:
            coords(n, out)
    return out


def enum_errors(obj, path=""):
    """응답 안의 코드값 필드가 명세 2장 값인지 (중첩까지)"""
    errs = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in ENUMS and not isinstance(v, (dict, list)) and v not in ENUMS[k]:
                errs.append("%s%s=%r" % (path, k, v))
            errs += enum_errors(v, "%s%s." % (path, k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj[:400]):
            errs += enum_errors(v, "%s[%d]." % (path, i))
    return errs


# --------------------------------------------------------------------------- 테스트
# 뒤집힌 월 범위(from > to): 어떤 달이든 400 INVALID_PARAM. 같은 해, 해 넘김, 한 달 차이, 전체 범위, 세 나라
REVERSED = [("KOR", "2025-05", "2025-01"), ("KOR", "2025-01", "2024-12"), ("KOR", "2025-12", "2001-01"),
            ("CAL", "2026-11", "2026-10"), ("BC", "2020-07", "2019-07")]


def reversed_range(c, make_path, same_path):
    def check():
        bad = []
        for cc, f, t in REVERSED:
            r = c.get(make_path(cc, f, t))
            code = r.json().get("code") if r.status >= 400 else None
            if (r.status, code) != (400, "INVALID_PARAM"):
                bad.append("%s %s→%s: %s %s" % (cc, f, t, r.status, code))
        assert not bad, "뒤집힌 범위가 400이 아님: " + "; ".join(bad)
        eq(c.get(same_path).status, 200, "같은 달 범위")
        return "%d가지 뒤집힌 범위 모두 400 INVALID_PARAM, 같은 달은 200" % len(REVERSED)
    return check


def run(c, rep, sweep):
    A = "/api/v1"

    # ---------------- 1장 공통 규약
    def errors_format():
        cases = [("%s/JPN/meta" % A, 404, "COUNTRY_NOT_READY"),
                 ("%s/KOR/regions/NOPE" % A, 404, "REGION_NOT_FOUND"),
                 ("%s/KOR/judgement/NOPE?month=2025-03" % A, 404, "REGION_NOT_FOUND"),
                 ("%s/KOR/judgement/KOR?month=1999-01" % A, 400, "MONTH_OUT_OF_RANGE"),
                 ("%s/KOR/series/KOR?from=2030-01" % A, 400, "MONTH_OUT_OF_RANGE")]
        for path, status, code in cases:
            r = c.get(path)
            eq(r.status, status, path + " 상태")
            body = r.json()
            eq(sorted(body), ["code", "message"], path + " 본문 키")
            eq(body["code"], code, path + " code")
        return "%d가지 에러가 {code, message} + 상태" % len(cases)
    rep.check("1 공통", "에러 형식·코드 (REGION_NOT_FOUND, MONTH_OUT_OF_RANGE, COUNTRY_NOT_READY)", errors_format)

    rep.check("1 공통·3 #5", "/series 뒤집힌 범위 (from > to) → INVALID_PARAM",
              reversed_range(c, lambda cc, f, t: "%s/%s/series/%s?from=%s&to=%s" % (A, cc, cc, f, t),
                             "%s/KOR/series/KOR?from=2025-03&to=2025-03" % A))

    def etag():
        r = c.get("%s/KOR/meta" % A)
        tag = r.headers.get("etag")
        assert tag, "ETag 없음"
        version = r.json()["data_version"]
        assert version in tag, "ETag(%s)에 data_version(%s)이 없음" % (tag, version)
        r2 = c.get("%s/KOR/meta" % A, {"If-None-Match": tag})
        eq(r2.status, 304, "If-None-Match 재요청")
        return "ETag %s → 304" % tag
    rep.check("1 공통", "캐싱: ETag = data_version, 304", etag)

    def cache_control():
        past = c.get("%s/KOR/judgement/KOR?month=2025-03" % A).headers.get("cache-control", "")
        recent = c.get("%s/CAL/judgement/CAL?month=2026-11" % A).headers.get("cache-control", "")
        age = lambda h: int(re.search(r"max-age=(\d+)", h).group(1))
        assert age(past) > age(recent), "과거 달(%s)이 최근 달(%s)보다 길어야 함" % (past, recent)
        return "과거 달 %s / 진행 중인 달 %s" % (past, recent)
    rep.check("1 공통", "캐싱: 과거 달은 길게, 진행 중인 달은 짧게", cache_control)

    def json_utf8():
        r = c.get("%s/KOR/regions/%s" % (A, UISEONG))
        ct = r.headers.get("content-type", "")
        assert "application/json" in ct, ct
        eq(r.json()["label_ko"], "의성군", "한글 응답")
        return ct
    rep.check("1 공통", "응답 JSON, 한글 깨짐 없음", json_utf8)

    def three_countries():
        for cc in ("KOR", "CAL", "BC"):
            eq(ok_json(c.get("%s/%s/meta" % (A, cc)), cc)["country"], cc, cc)
        return "KOR, CAL, BC"
    rep.check("1 공통", "세 지역 모두 결과 있음", three_countries)

    def region_ids():
        kor = ok_json(c.get("%s/KOR/regions" % A), "KOR")["items"]
        ids = {x["id"]: x for x in kor}
        assert "KOR" in ids and ids["KOR"]["level"] == "nat", "나라 ID KOR"
        assert "경북" in ids and ids["경북"]["level"] == "prov", "시·도 ID = 한글 이름 (경북)"
        assert UISEONG in ids, "시·군·구 ID = shapeID"
        assert "PATCH-KOR-Yeonggwang" in ids, "영광군 = PATCH-KOR-Yeonggwang"
        cal = {x["id"] for x in ok_json(c.get("%s/CAL/regions" % A), "CAL")["items"]}
        assert "CAL" in cal and BUTTE in cal, "CAL 주 전체 ID·Butte shapeID"
        bc = {x["id"] for x in ok_json(c.get("%s/BC/regions" % A), "BC")["items"]}
        assert "BC" in bc and "BC-RD-60" in bc, "BC 주 전체 ID·BC-RD-60"
        return "KOR·경북·shapeID·PATCH-KOR-Yeonggwang·CAL·BC·BC-RD-60"
    rep.check("1 공통", "지역 ID 형식", region_ids)

    def coords_3():
        items = ok_json(c.get("%s/KOR/regions" % A), "regions")["items"]
        bad = [x["id"] for x in items for v in (x["center"] or []) + (x["bbox"] or []) if decimals(v) > 3]
        geo = ok_json(c.get("%s/KOR/geo?parent=%s" % (A, "경북")), "geo")
        pts = coords([f["geometry"]["coordinates"] for f in geo["features"]], [])
        badg = [v for v in pts if decimals(v) > 3]
        assert not bad and not badg, "소수점 3자리 초과: regions %s, geo %d개" % (bad[:3], len(badg))
        lon, lat = items[0]["center"]
        assert 120 < lon < 135 and 30 < lat < 40, "[경도, 위도] 순서가 아님: %s" % items[0]["center"]
        return "center·bbox %d곳, 경계 좌표 %d개" % (len(items), len(pts))
    rep.check("1 공통", "좌표 [경도, 위도], 소수점 3자리", coords_3)

    def number_digits():
        months = ok_json(c.get("%s/KOR/series/KOR" % A), "series")["months"]
        bad_v = [m["month"] for m in months if m["value"] is not None and decimals(m["value"]) > 1]
        bad_r = [m["month"] for m in months if m["ratio_to_median"] is not None and decimals(m["ratio_to_median"]) > 2]
        assert not bad_v and not bad_r, "화재 칸 1자리 초과 %s, 비율 2자리 초과 %s" % (bad_v[:3], bad_r[:3])
        return "%d개월" % len(months)
    rep.check("1 공통", "숫자: 화재 칸 소수 한 자리, 비율 두 자리", number_digits)

    def cal_bc_names():
        # 해외 구역: label_ko는 한국어 음역, name_en은 원래(영어) 이름. 영어 화면은 name_en을 쓴다
        for cc, rid, ko, en in (("CAL", BUTTE, "뷰트", "Butte"), ("BC", "BC-RD-60", "피스 리버", "Peace River")):
            r = ok_json(c.get("%s/%s/regions/%s" % (A, cc, rid)), rid)
            eq((r["label_ko"], r["name_en"]), (ko, en), cc + " label_ko·name_en")
        return "뷰트/Butte, 피스 리버/Peace River"
    rep.check("1 공통", "캘리포니아·BC 구역 이름: label_ko 한국어, name_en 영어", cal_bc_names)

    # ---------------- 2장 코드값·나라별 이름표
    labels_spec = {
        "KOR": ("대한민국", "시·도", "시·군·구", False, "산림청", [3, 10, 30], 229),
        "CAL": ("캘리포니아", "주", "카운티", True, "CAL FIRE", [3, 8, 18], 58),
        "BC": ("브리티시컬럼비아", "주", "지역구", True, "BC Wildfire Service", [2, 8, 47], 28),
    }
    for cc, (country, lv1, lv2, single, official, bins, n) in labels_spec.items():
        def labels(cc=cc, country=country, lv1=lv1, lv2=lv2, single=single, official=official, bins=bins, n=n):
            lab = ok_json(c.get("%s/%s/meta" % (A, cc)), cc)["labels"]
            for k, v in (("country", country), ("lv1", lv1), ("lv2", lv2), ("single", single),
                         ("official", official), ("bins", bins)):
                eq(lab.get(k), v, "labels." + k)
            dist = ok_json(c.get("%s/%s/regions?level=dist" % (A, cc)), cc)["items"]
            eq(len(dist), n, "하위 구역 수")
            prov = ok_json(c.get("%s/%s/regions?level=prov" % (A, cc)), cc)["items"]
            eq(len(prov), 0 if single else 17, "level prov 수 (single이면 0)")
            return "하위 구역 %d" % n
        rep.check("2 코드값", "나라별 이름표 %s" % cc, labels)

    def basis_rule():
        prov = ok_json(c.get("%s/KOR/regions?level=prov" % A), "prov")["items"]
        b = {p["id"]: ok_json(c.get("%s/KOR/regions/%s" % (A, p["id"])), p["id"])["basis"] for p in prov}
        long_ = sorted(k for k, v in b.items() if v == "harmonized_25y")
        eq(long_, sorted(["경기", "경북", "충남"]), "25년 시·도")
        eq(ok_json(c.get("%s/KOR/regions/KOR" % A), "KOR")["basis"], "harmonized_25y", "나라 basis")
        return "25년: %s, 나머지 viirs_2012" % ", ".join(long_)
    rep.check("2 코드값", "basis: 나라와 경기·경북·충남은 25년, 나머지는 2012~", basis_rule)

    def estimated_rule():
        nat = {m["month"]: m for m in ok_json(c.get("%s/KOR/series/KOR" % A), "nat")["months"]}
        eq(nat["2012-01"]["estimated"], True, "나라 2012-01")
        eq(nat["2012-02"]["estimated"], False, "나라 2012-02")
        prov = {m["month"]: m for m in ok_json(c.get("%s/KOR/series/경북" % A), "경북")["months"]}
        eq(prov["2018-03"]["estimated"], True, "시·도 2018-03")
        eq(prov["2018-04"]["estimated"], False, "시·도 2018-04")
        return "나라 2012-02, 시·도 2018-04 기준"
    rep.check("2 코드값", "estimated: 2012-02 전 (시·도는 2018-04 전)", estimated_rule)

    def source_by_month():
        nat = {m["month"]: m["source"] for m in ok_json(c.get("%s/KOR/series/KOR" % A), "nat")["months"]}
        eq(nat["2025-03"], "NOAA-20", "2025-03")
        eq(nat["2015-03"], "S-NPP→NOAA-20", "2015-03")
        assert nat["2005-03"] in ("MODIS→NOAA-20", "Terra→NOAA-20"), "2005-03: %s" % nat["2005-03"]
        return "2005 %s / 2015 S-NPP / 2025 NOAA-20" % nat["2005-03"]
    rep.check("2 코드값", "source: 달별 실제 관측 위성", source_by_month)

    # ---------------- 3장 엔드포인트 필드 + 7장 예시
    def meta_fields():
        m = ok_json(c.get("%s/KOR/meta" % A), "meta")
        has_keys(m, ["country", "data_version", "generated_at", "data_end", "unit", "data_range",
                     "judgement_params", "calibration", "validation", "sources", "labels"], "/meta")
        assert re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?[+-]\d{2}:\d{2}$", m["generated_at"]), \
            "generated_at 시각 없음: %s" % m["generated_at"]
        has_keys(m["data_range"], ["modis", "viirs_snpp", "noaa20", "noaa21"], "data_range")
        jp = m["judgement_params"]
        eq(jp["band_q"], [0.15, 0.85], "band_q")
        eq(jp["band_noise"], 2, "band_noise")
        eq(jp["min_annual"], 11.1, "min_annual")
        eq(jp["risk_share"], 0.5, "risk_share")
        eq(m["data_end"], "2026-11-11", "data_end")
        eq(m["calibration"]["modis_to_snpp_by_month"]["3"], 1.721, "modis_to_snpp_by_month.3")
        eq(m["validation"]["break_test_ratio"], 0.99, "break_test_ratio")
        eq(m["validation"]["break_test_ci95"], [0.92, 1.05], "break_test_ci95")
        assert any("FIRMS" in s for s in m["sources"]), "sources에 NASA FIRMS"
        return "data_version %s" % m["data_version"]
    rep.check("3·7-1", "/meta 필드와 예시 값", meta_fields)

    def status_nrt():
        # 2장 status: 진행 중인 달 partial, NOAA-20 SP가 끝난 뒤 달 nrt, 나머지 final
        got = {}
        for cc, mo in (("KOR", "2026-08"), ("KOR", "2026-09"), ("CAL", "2026-07"), ("CAL", "2026-08"), ("CAL", "2026-11")):
            got[cc + " " + mo] = ok_json(c.get("%s/%s/judgement/%s?month=%s" % (A, cc, cc, mo)), cc)["status"]
        eq(list(got.values()), ["final", "nrt", "final", "nrt", "partial"], "status")
        return ", ".join("%s %s" % kv for kv in got.items())
    rep.check("2 코드값", "status: partial / nrt (SP 끝난 뒤) / final", status_nrt)

    def meta_filters():
        m = ok_json(c.get("%s/KOR/meta" % A), "meta")
        assert "filters" in m, "3장 #1 '필터 단계별 건수'가 응답에 없음"
        return "filters %d단계" % len(m["filters"])
    rep.check("3·7-1", "/meta 필터 단계별 건수 (3장 #1)", meta_filters)

    def cal_meta():
        m = ok_json(c.get("%s/CAL/meta" % A), "CAL meta")
        eq(m["data_end"], "2026-11-14", "CAL data_end")
        assert "official_break" in m["validation"], "validation.official_break 없음 (7-13)"
        return "official_break %s" % m["validation"]["official_break"]
    rep.check("3·7-13", "/meta CAL: data_end, validation.official_break", cal_meta)

    region_fields = ["id", "label_ko", "name_en", "level", "province", "region_type", "center", "bbox"]

    def regions_list():
        items = ok_json(c.get("%s/KOR/regions" % A), "regions")["items"]
        for x in items:
            has_keys(x, region_fields, x.get("id"))
        q = ok_json(c.get("%s/KOR/regions?q=%s" % (A, "의성")), "q")["items"]
        eq([x["id"] for x in q], [UISEONG], "q=의성")
        lv = ok_json(c.get("%s/KOR/regions?level=nat" % A), "level")["items"]
        eq([x["id"] for x in lv], ["KOR"], "level=nat")
        return "%d곳, q·level 필터" % len(items)
    rep.check("3 #2", "/regions?level=&q= 필드·검색", regions_list)

    def region_detail():
        r = ok_json(c.get("%s/KOR/regions/%s" % (A, UISEONG)), "의성")
        has_keys(r, region_fields + ["neighbors", "ref_province", "judge_unit", "basis", "risk_months",
                                      "risk_share", "risk_from"], "/regions/{id}")
        for k, v in (("label_ko", "의성군"), ("name_en", "Uiseong-gun"), ("level", "dist"), ("province", "경북"),
                     ("region_type", "sparse"), ("judge_unit", "province"), ("ref_province", "경북"),
                     ("basis", "viirs_2012"), ("risk_from", "province")):
            eq(r[k], v, k)
        nb = {n["label_ko"]: n for n in r["neighbors"]}
        assert "안동시" in nb, "이웃 안동시"
        eq(nb.get("군위군", {}).get("province"), "대구", "이웃 군위군 province")
        return "risk_months %s (명세 예시 [3])" % r["risk_months"]
    rep.check("3 #3·7-2", "/regions/{id} 필드와 의성군 예시", region_detail)

    def risk_months_example():
        eq(ok_json(c.get("%s/KOR/regions/%s" % (A, UISEONG)), "의성")["risk_months"], [3], "의성 risk_months")
    rep.check("7-2", "의성 risk_months = [3]", risk_months_example)

    def geo():
        g = ok_json(c.get("%s/KOR/geo" % A), "geo")
        eq(g["type"], "FeatureCollection", "type")
        eq(len(g["features"]), 229, "시·군·구 경계 수")
        for f in g["features"]:
            eq(sorted(f["properties"]), ["id", "label_ko", "province"], "속성")
            assert f["geometry"]["type"] in ("Polygon", "MultiPolygon"), f["geometry"]["type"]
        prov = ok_json(c.get("%s/KOR/geo?level=prov" % A), "geo prov")["features"]
        eq(len(prov), 17, "시·도 경계 수")
        par = ok_json(c.get("%s/KOR/geo?parent=%s" % (A, "경북")), "parent")["features"]
        assert par and all(f["properties"]["province"] == "경북" for f in par), "parent=경북 필터"
        size = len(c.get("%s/KOR/geo" % A).raw)
        return "229 + 시·도 17, 경북 %d곳, %.0f KB" % (len(par), size / 1024)
    rep.check("3 #4", "/geo?level=&parent= 단순화 GeoJSON", geo)

    def series():
        s = ok_json(c.get("%s/KOR/series/KOR?from=2025-01&to=2025-03" % A), "series")
        eq(s["id"], "KOR", "id")
        eq(s["level"], "nat", "level")
        eq(len(s["months"]), 3, "달 수")
        for m in s["months"]:
            has_keys(m, ["month", "value", "source", "quality", "estimated", "status", "rank", "n",
                         "ratio_to_median"], m["month"])
        exp = [("2025-01", 21.0, 13, 1.0), ("2025-02", 43.0, 21, 0.49), ("2025-03", 1093.0, 1, 6.28)]
        for m, (month, value, rank, ratio) in zip(s["months"], exp):
            eq((m["month"], m["value"], m["rank"], m["ratio_to_median"]), (month, value, rank, ratio), month)
            eq((m["source"], m["quality"], m["estimated"], m["status"], m["n"]),
               ("NOAA-20", "high", False, "final", 25), month + " 나머지")
        return "7-3 세 달 일치"
    rep.check("3 #5·7-3", "/series 필드와 예시 값", series)

    def baseline():
        b = ok_json(c.get("%s/KOR/baseline/KOR" % A), "baseline")
        has_keys(b, ["id", "period", "by_month", "heat_bins"], "/baseline")
        eq(b["period"], [2001, 2025], "period")
        eq(len(b["by_month"]), 12, "by_month 12")
        m3 = b["by_month"][2]
        eq((m3["month"], m3["median"], m3["band_low"], m3["band_high"]), (3, 174.0, 95.6, 294.8), "3월")
        hb = b["heat_bins"]
        assert len(hb) == 3 and hb == sorted(hb) and all(v > 0 for v in hb), "heat_bins 50·75·90%%: %s" % hb
        return "heat_bins %s" % hb
    rep.check("3 #6·7-4", "/baseline 필드와 예시 값", baseline)

    judgement_fields = ["id", "month", "value", "chip", "judge_unit", "rank", "ratio_to_median", "quality",
                        "estimated", "reason"]

    def judge_nat():
        j = ok_json(c.get("%s/KOR/judgement/KOR?month=2025-03" % A), "judgement")
        has_keys(j, judgement_fields + ["n", "rank_period", "median", "band_low", "band_high", "ytd_sum", "status"], "자체 판정")
        for k, v in (("value", 1093.0), ("chip", "record"), ("judge_unit", "self"), ("rank", 1), ("n", 25),
                     ("rank_period", [2001, 2025]), ("median", 174.0), ("band_low", 95.6), ("band_high", 294.8),
                     ("ratio_to_median", 6.28), ("ytd_sum", 1157.0), ("quality", "high"), ("estimated", False),
                     ("status", "final"), ("reason", None)):
            eq(j[k], v, k)
    rep.check("3 #7·7-5", "/judgement 자체 판정 (전국 2025-03)", judge_nat)

    def judge_sparse():
        j = ok_json(c.get("%s/KOR/judgement/%s?month=2025-03" % (A, UISEONG)), "의성")
        for k, v in (("value", 384.0), ("region_type", "sparse"), ("chip", None), ("judge_unit", "province"),
                     ("rank", None), ("ratio_to_median", None), ("estimated", False), ("reason", None)):
            eq(j[k], v, k)
        # 명세 2장 "평년 5칸 미만이면 한 단계 낮춤": 의성 3월 평년 0칸 → high에서 medium (7-5 예시는 high로 적혀 있음)
        eq(j["quality"], "medium", "quality (2장 규칙)")
        ref = j["ref"]
        for k, v in (("id", "경북"), ("value", 948.0), ("chip", "record"), ("rank", 1), ("n", 25),
                     ("ratio_to_median", 72.64), ("basis", "harmonized_25y")):
            eq(ref[k], v, "ref." + k)
    rep.check("3 #7·7-5", "/judgement 드문 지역, 시·도 판정 빌림 (의성 2025-03)", judge_sparse)

    def judge_none():
        j = ok_json(c.get("%s/KOR/judgement/%s?month=2012-02" % (A, GOSEONG)), "고성")
        for k, v in (("value", 0.0), ("rank", None), ("ratio_to_median", None), ("reason", "NO_DETECTION")):
            eq(j[k], v, k)
        return "region_type %s, chip %s (명세 예시: sufficient, normal)" % (j["region_type"], j["chip"])
    rep.check("3 #7·7-5", "/judgement 감지 없음 (고성 2012-02)", judge_none)

    def judge_none_example():
        j = ok_json(c.get("%s/KOR/judgement/%s?month=2012-02" % (A, GOSEONG)), "고성")
        eq((j["region_type"], j["chip"], j["judge_unit"], j["median"]), ("sufficient", "normal", "self", 0.0),
           "명세 7-5 고성 region_type·chip·judge_unit·median")
    rep.check("7-5", "고성 예시의 region_type·chip·judge_unit·median", judge_none_example)

    def judge_butte():
        j = ok_json(c.get("%s/CAL/judgement/%s?month=2018-11" % (A, BUTTE)), "Butte")
        for k, v in (("label_ko", "뷰트"), ("value", 1162.0), ("region_type", "sufficient"), ("chip", "record"),
                     ("judge_unit", "self"), ("rank", 1), ("n", 14), ("rank_period", [2012, 2025]),
                     ("median", 39.0), ("band_low", 17.9), ("band_high", 62.3), ("quality", "high"),
                     ("estimated", False), ("reason", None)):
            eq(j[k], v, k)
        return "ratio_to_median %s" % j["ratio_to_median"]
    rep.check("7-13", "/judgement Butte 2018-11 (Camp Fire)", judge_butte)

    ratio = ok_json(c.get("%s/CAL/judgement/%s?month=2018-11" % (A, BUTTE)), "Butte")["ratio_to_median"]
    if ratio == 29.79:
        rep.note("7-13", "Butte ratio_to_median",
                 "명세 예시 29.8, 응답 29.79 = 1162/39 = 29.795를 1장 '비율은 두 자리'로 반올림. 규칙을 따름")
    else:
        rep.add("7-13", "Butte ratio_to_median", ratio == 29.8, "응답 %s" % ratio)

    def official_break():
        exp = {"KOR": (2.67, 1.04, False), "CAL": (2.54, 1.25, False), "BC": (None, 2.14, True)}
        got = {}
        for cc, (before, after, inconclusive) in exp.items():
            ob = ok_json(c.get("%s/%s/meta" % (A, cc)), cc)["validation"]["official_break"]
            if before is not None:
                eq(ob["before"], before, cc + " before")
            eq((ob["after"], ob["inconclusive"]), (after, inconclusive), cc + " after·inconclusive")
            got[cc] = "%s→%s%s" % (ob["before"], ob["after"], " (inconclusive)" if ob["inconclusive"] else "")
        return ", ".join("%s %s" % kv for kv in got.items())
    rep.check("7-10·7-13", "공식 기록 대비 단절 검정 (KOR 2.67→1.04, CAL 2.54→1.25, BC inconclusive)", official_break)

    def export_csv():
        r = c.get("%s/KOR/export.csv?id=KOR" % A)
        eq(r.status, 200, "상태")
        assert "text/csv" in r.headers.get("content-type", ""), r.headers.get("content-type")
        text = r.text.lstrip("\ufeff")
        eq(text.splitlines()[0], "region,year,month,fire_cells,median,band_low,band_high,rank,years_compared", "머리줄")
        line = "대한민국 전체,2025,03,1093,174,95.6,294.8,1,25"
        assert line in text.splitlines(), "7-11 줄 없음: " + line
        return "%d줄" % (len(text.splitlines()) - 1)
    rep.check("3 #14·7-11", "/export.csv 머리줄과 예시 줄", export_csv)

    def export_csv_etag():
        # 1장 캐싱: ETag = data_version, 304. CSV는 최근 달까지 들어 있어 짧은 캐시
        r = c.get("%s/KOR/export.csv?id=KOR" % A)
        tag = r.headers.get("etag")
        assert tag, "ETag 없음"
        cc = r.headers.get("cache-control", "")
        r2 = c.get("%s/KOR/export.csv?id=KOR" % A, {"If-None-Match": tag})
        eq(r2.status, 304, "If-None-Match 재요청")
        return "ETag %s → 304, %s" % (tag, cc)
    rep.check("1 공통·3 #14", "/export.csv 캐싱: ETag = data_version, 304", export_csv_etag)

    # ---------------- 5장 빈 상태
    def pre2012():
        j = ok_json(c.get("%s/KOR/judgement/%s?month=2010-05" % (A, UISEONG)), "의성 2010-05")
        eq((j["value"], j["reason"]), (None, "PRE_2012_DISTRICT"), "value·reason")
    rep.check("5 빈 상태", "PRE_2012_DISTRICT: 시·군·구 2012-02 전 → value null", pre2012)

    def in_progress():
        j = ok_json(c.get("%s/CAL/judgement/CAL?month=2026-11" % A), "CAL 2026-11")
        eq((j["status"], j["reason"]), ("partial", "IN_PROGRESS"), "status·reason")
        assert j["chip"] is not None, "진행 중인 달도 칩은 계산"
        return "chip %s" % j["chip"]
    rep.check("5 빈 상태", "IN_PROGRESS: 진행 중인 달 → partial, 칩은 계산", in_progress)

    def no_record():
        items = ok_json(c.get("%s/KOR/regions?level=dist" % A), "dist")["items"]
        none = [x for x in items if x["region_type"] == "none"]
        assert none, "region_type none 구역이 없음"
        j = ok_json(c.get("%s/KOR/judgement/%s?month=2025-03" % (A, none[0]["id"])), none[0]["label_ko"])
        eq((j["chip"], j["reason"]), (None, "NO_RECORD"), none[0]["label_ko"] + " chip·reason")
        return "%s 외 %d곳" % (none[0]["label_ko"], len(none) - 1)
    rep.check("5 빈 상태", "NO_RECORD: region_type none → chip null", no_record)

    def sparse_ref():
        j = ok_json(c.get("%s/KOR/judgement/%s?month=2025-03" % (A, UISEONG)), "의성")
        assert j["chip"] is None and j["ref"] and j["ref"]["chip"], "chip null, ref.chip 채움"
        b = ok_json(c.get("%s/KOR/baseline/%s" % (A, UISEONG)), "의성 baseline")
        assert all(m["band_low"] is None and m["band_high"] is None for m in b["by_month"]), "드문 지역 band null"
    rep.check("5 빈 상태", "sparse: chip null + ref 채움, band null", sparse_ref)

    def zero_baseline():
        # 평년이 0인 달에 불이 난 자체 판정 구역을 찾는다
        for cc in ("KOR", "CAL", "BC"):
            regions = [x for x in ok_json(c.get("%s/%s/regions" % (A, cc)), cc)["items"] if x["region_type"] == "sufficient"]
            for x in regions:
                base = {m["month"]: m["median"] for m in ok_json(c.get("%s/%s/baseline/%s" % (A, cc, x["id"])), "b")["by_month"]}
                zero = [m for m, med in base.items() if med == 0]
                if not zero:
                    continue
                for m in ok_json(c.get("%s/%s/series/%s" % (A, cc, x["id"])), "s")["months"]:
                    if int(m["month"][5:]) in zero and (m["value"] or 0) > 0:
                        j = ok_json(c.get("%s/%s/judgement/%s?month=%s" % (A, cc, x["id"], m["month"])), "j")
                        eq((j["reason"], j["ratio_to_median"]), ("ZERO_BASELINE", None), "%s %s" % (x["label_ko"], m["month"]))
                        return "%s %s" % (x["label_ko"], m["month"])
        return "해당 사례 없음 (평년 0인 달에 불이 난 자체 판정 구역이 없다)"
    rep.check("5 빈 상태", "ZERO_BASELINE: 평년 0 → ratio null", zero_baseline)

    # ---------------- 내부 API
    def internal():
        j = ok_json(c.get("/internal/judge?country=KOR&region_id=KOR&month=2025-03"), "/internal/judge")
        eq((j["value"], j["chip"]), (1093.0, "record"), "내부 판정")
        if not c.base:
            r = ok_json(c.get("/internal/regions/KOR/%s" % UISEONG), "/internal/regions")
            eq(r["label_ko"], "의성군", "내부 구역")
    if c.base:
        def internal_hidden():
            for p in ("/internal/judge?country=KOR&region_id=KOR&month=2025-03", "/health/ready", "/metrics"):
                r = c.get(p)
                assert r.status == 404, "%s 가 밖에서 보임 (HTTP %s)" % (p, r.status)
            return "/internal, /health, /metrics → 404"
        rep.check("공개 범위", "내부 API·헬스·메트릭은 밖에서 안 보임", internal_hidden)
    else:
        rep.check("내부 API", "/internal/judge, /internal/regions (B가 부름)", internal)

    # ---------------- B 파트 (spatial·insight)
    if c.b_enabled:
        b_tests(c, rep)
    else:
        for name in B_ENDPOINTS:
            rep.skip("B 파트", "/%s" % name, "--no-b")

    # ---------------- 전수 검사
    if sweep:
        sweep_all(c, rep)


def b_tests(c, rep):
    """B 파트: 명세 3장 #8~#13, 7-6 ~ 7-10, 7-13"""
    A = "/api/v1"
    S = "3 #8·7-6"

    def map_kor():
        m = ok_json(c.get("%s/KOR/map?month=2025-03" % A), "/map")
        has_keys(m, ["month", "regions", "points"], "/map")
        has_keys(m["points"], ["sensor", "count", "items"], "points")
        eq(m["points"]["sensor"], "NOAA-20", "sensor")
        regions = {r["id"]: r for r in m["regions"]}
        eq((regions[UISEONG]["value"], regions[UISEONG]["map_bin"]), (384.0, 4), "의성 value·map_bin")
        bins = [3, 10, 30]
        bad = [r["id"] for r in m["regions"] if r["value"] is not None and
               r["map_bin"] != (0 if r["value"] == 0 else 1 + sum(r["value"] >= b for b in bins))]
        assert not bad, "map_bin 규칙(0 / 1–2 / 3–9 / 10–29 / 30+) 어긋남: %s" % bad[:3]
        items = m["points"]["items"]
        eq(m["points"]["count"], len(items), "4,000개 이하 달은 count = 보낸 수")
        p = items[0]
        assert len(p) == 4 and isinstance(p[3], str) and 1 <= p[2] <= 31, "점 = [경도, 위도, 일, region_id]: %s" % p
        bad_c = [x for x in items if decimals(x[0]) > 3 or decimals(x[1]) > 3]
        assert not bad_c, "점 좌표 소수점 3자리 초과 %d개" % len(bad_c)
        return "구역 %d, 점 %d (명세 예시 count 1093)" % (len(m["regions"]), m["points"]["count"])
    rep.check(S, "/map 한국 2025-03: 구역 색·점", map_kor)

    def map_sample():
        m = ok_json(c.get("%s/CAL/map?month=2020-09" % A), "CAL /map")
        pts = m["points"]
        assert pts["count"] > 4000, "count %s" % pts["count"]
        eq((pts.get("shown"), len(pts["items"])), (4000, 4000), "4,000개 표본 (shown)")
        return "count %d (명세 예시 32941), shown 4000" % pts["count"]
    rep.check(S, "/map 4,000개 넘는 달은 고르게 뽑은 4,000개 + count·shown (CAL 2020-09)", map_sample)

    def map_parent():
        m = ok_json(c.get("%s/KOR/map?month=2025-03&parent=%s" % (A, "경북")), "/map parent")
        ids = {r["id"] for r in m["regions"]}
        assert UISEONG in ids and len(ids) < 229, "parent=경북 필터: %d곳" % len(ids)
        return "경북 %d곳" % len(ids)
    rep.check(S, "/map?parent= 시·도 필터", map_parent)

    def top():
        t = ok_json(c.get("%s/KOR/top?month=2025-03" % A), "/top")
        has_keys(t, ["month", "parent", "items"], "/top")
        eq(t["parent"], "KOR", "parent")
        eq(len(t["items"]), 3, "3곳")
        for x in t["items"]:
            has_keys(x, ["id", "label_ko", "value", "rank", "n"], x.get("id"))
        exp = [(UISEONG, "경북 의성군", 384.0), ("91817680B16014600177045", "경북 안동시", 220.0),
               ("91817680B91067436457746", "경북 영덕군", 168.0)]
        eq([(x["id"], x["label_ko"], x["value"]) for x in t["items"]], exp, "7-7 순서·이름·값")
        eq([(x["rank"], x["n"]) for x in t["items"]], [(1, 14)] * 3, "rank·n")
    rep.check("3 #9·7-7", "/top 2025-03", top)

    def top_neighbors():
        t = ok_json(c.get("%s/KOR/top?month=2025-03&parent=%s" % (A, UISEONG)), "/top 시·군·구")
        vals = [x["value"] for x in t["items"]]
        assert t["items"] and vals == sorted(vals, reverse=True), "H 순 정렬: %s" % vals
        return "%d곳: %s" % (len(t["items"]), ", ".join(x["label_ko"] for x in t["items"][:4]))
    rep.check("3 #9", "/top?parent=시·군·구 → 맞닿은 지역 H 순", top_neighbors)
    t = ok_json(c.get("%s/KOR/top?month=2025-03&parent=%s" % (A, UISEONG)), "/top")
    if any(x["id"] == UISEONG for x in t["items"]):
        rep.note("3 #9", "/top?parent=시·군·구에 자기 자신 포함",
                 "명세는 '맞닿은 지역을 H 순'. 응답은 의성군 자신도 들어 있고 3곳이 아니라 %d곳 — 의도인지 확인" % len(t["items"]))

    def insight():
        r = ok_json(c.get("%s/KOR/insight/%s?month=2025-03" % (A, UISEONG)), "/insight")
        has_keys(r, ["id", "month", "label_ko", "head", "ref", "calendar", "years", "season", "official", "map",
                     "neighbors"], "/insight")
        h = r["head"]
        for k, v in (("value", 384.0), ("chip", None), ("region_type", "sparse"), ("judge_unit", "province"),
                     ("fire_days", 7), ("dmax_date", "2025-03-26"), ("annual", 2.6), ("annual_all", 30.0)):
            eq(h[k], v, "head." + k)
        eq((r["ref"]["id"], r["ref"]["chip"], r["ref"]["ratio_to_median"]), ("경북", "record", 72.64), "ref")
        mc = r["calendar"]["main_cluster"]
        eq((mc["start"], mc["end"], mc["days"], mc["cells"], mc["peak_cells"], mc["peak_date"]),
           ("2025-03-22", "2025-03-29", 8, 919, 551, "2025-03-26"), "main_cluster")
        eq((r["calendar"]["clusters"]["events"], r["calendar"]["clusters"]["big_events"]), (3, 1), "clusters")
        eq(r["calendar"]["daily"].get("26"), r["head"]["dmax"], "daily[26] = dmax")
        y = r["years"]
        eq((y["band_low"], y["band_high"], y["yrs_fire"]), (None, None, 3), "years band·yrs_fire")
        eq((y["longterm"]["fire_cells_2001_2025"], y["longterm"]["annual_avg"], y["longterm"]["quality"]),
           (477.3, 19.1, "high"), "years.longterm")
        s = r["season"]
        eq((len(s["own_mt"]), len(s["ref_mt"]), s["risk_months"], s["risk_from"]), (12, 12, [3], "province"), "season")
        first = r["official"][0] if isinstance(r["official"], list) else r["official"]["items"][0]
        eq((first["date"], first["place"], first["cause"], first["area_ha"]),
           ("2025-03-22", "경북 의성 안계", "농산폐기물 태우기", 52707.3), "official[0]")
        assert r["map"]["points"] and len(r["map"]["points"][0]) == 3, "map.points [lon, lat, day]"
        assert r["map"]["rec"] and {"lat", "lon", "y"} <= set(r["map"]["rec"][0]), "map.rec {lat, lon, y}"
        nb = {n["label_ko"]: n for n in r["neighbors"]}
        eq(nb["안동시"]["v"], 220.0, "neighbors 안동시 v")
        return "dmax %s (사이트 111), near50 %s (사이트 330)" % (h.get("dmax"), r["calendar"]["near50"])
    rep.check("3 #10·4·7-8", "/insight 의성 2025-03 카드 1~9", insight)

    def insight_years_median():
        r = ok_json(c.get("%s/KOR/insight/%s?month=2025-03" % (A, UISEONG)), "/insight")
        eq(r["years"]["median"], 0.0, "years.median (명세 7-8: 0.0)")
    rep.check("7-8", "/insight 드문 지역 years.median", insight_years_median)

    def insight_head_rank():
        h = ok_json(c.get("%s/KOR/insight/%s?month=2025-03" % (A, UISEONG)), "/insight")["head"]
        eq((h["rank"], h["n"]), (1, 14), "head.rank·n (명세 7-8: 1, 14)")
    rep.check("7-8", "/insight 드문 지역 head.rank·n", insight_head_rank)

    def insight_errors():
        r = c.get("%s/KOR/insight/NOPE?month=2025-03" % A)
        eq((r.status, r.json().get("code")), (404, "REGION_NOT_FOUND"), "없는 구역")
        r = c.get("%s/KOR/insight/%s?month=2025-3" % (A, UISEONG))
        eq((r.status, r.json().get("code")), (400, "INVALID_PARAM"), "잘못된 월")
    rep.check("1 공통", "/insight 에러 형식", insight_errors)

    def events():
        e = ok_json(c.get("%s/KOR/events?region=%s&from=2025-03&to=2025-03" % (A, UISEONG)), "/events")
        main = [x for x in e["items"] if x.get("id") == 2686]
        assert main, "id 2686 묶음 없음: %s" % [x.get("id") for x in e["items"]]
        x = main[0]
        has_keys(x, ["start", "end", "days", "cells", "peak_cells", "peak_date", "lat", "lon", "frp_mw", "regions"], "event")
        eq((x["start"], x["end"], x["days"], x["cells"], x["peak_cells"], x["peak_date"], x["lat"], x["lon"], x["frp_mw"]),
           ("2025-03-22", "2025-03-29", 8, 919, 551, "2025-03-26", 36.436, 128.891, 17699.3), "7-9 값")
        eq(len(x["regions"]), 5, "regions 5곳")
        return "%d건" % len(e["items"])
    rep.check("3 #11·7-9", "/events 의성 2025-03", events)
    rep.check("1 공통·3 #11", "/events 뒤집힌 범위 (from > to) → INVALID_PARAM",
              reversed_range(c, lambda cc, f, t: "%s/%s/events?from=%s&to=%s" % (A, cc, f, t),
                             "%s/KOR/events?from=2025-03&to=2025-03" % A))

    def official_bc():
        o = ok_json(c.get("%s/BC/official-fires?region=BC-RD-60&month=2023-05" % A), "/official-fires")
        eq((o["source"], o["count"]), ("BC Wildfire Service", 29), "source·count")
        donnie = [x for x in o["items"] if x["place"] == "Donnie Creek"]
        assert donnie, "Donnie Creek 없음"
        x = donnie[0]
        eq((x["date"], x["cause"], x["area_ha"], x["region_id"]), ("2023-05-11", "번개", 619072.5, "BC-RD-60"), "Donnie Creek")
        return "29건, 정렬: %s 먼저" % o["items"][0]["place"]
    rep.check("3 #12·7-13", "/official-fires BC Peace River 2023-05", official_bc)
    o = ok_json(c.get("%s/BC/official-fires?region=BC-RD-60&month=2023-05" % A), "/official-fires")
    if o["items"] and o["items"][0]["place"] != "Donnie Creek":
        rep.note("7-13", "/official-fires 정렬 순서",
                 "명세 예시 앞 2건(Donnie Creek, Stoddart Creek)은 면적 순, 응답은 날짜 순(%s 먼저). 화면 요구에 맞춰 정하기"
                 % o["items"][0]["place"])

    def official_kor():
        o = ok_json(c.get("%s/KOR/official-fires?region=%s&month=2025-03" % (A, UISEONG)), "/official-fires KOR")
        eq(o["source"], "산림청", "source")
        places = [x["place"] for x in o["items"]]
        assert "경북 의성 안계" in places and "경북 의성 안평" in places, places[:5]
        return "%d건" % o["count"]
    rep.check("3 #12", "/official-fires 한국 (산림청, 장소 이름으로 구역 맞춤)", official_kor)

    def landing_kor():
        l = ok_json(c.get("%s/KOR/landing" % A), "/landing")
        # /landing 키는 세 나라 공통 이름 (7-13 기준). B 담당이 명세 7-10 예시의 kosis_check·burned_area_2025·
        # march_2025_ratio를 official_break·burned_area_record·case_month_ratio로 바꿨다 (값은 그대로)
        has_keys(l, ["stats", "annual", "official_break", "break_test", "detect_by_size"], "/landing")
        st = {s["key"]: s for s in l["stats"]}
        ba = st["burned_area_record"]
        eq((ba["year"], ba["value"], ba["unit"], ba["source"]), (2025, 105099, "ha", "KOSIS"), "burned_area_record")
        cm = st["case_month_ratio"]
        eq((cm["month"], cm["value"], cm["rank"], cm["n"]), ("2025-03", 6.28, 1, 25), "case_month_ratio")
        eq((st["risk_share"]["value"], st["risk_share"]["months"]), (0.64, [2, 3, 4]), "risk_share (1장 비율 두 자리)")
        eq((l["official_break"]["before"], l["official_break"]["after"]), (2.67, 1.04), "official_break")
        bt = l["break_test"]
        eq((bt["before"]["ratio"], bt["before"]["ci95"], bt["after"]["ratio"], bt["after"]["ci95"]),
           (0.92, [0.86, 0.98], 0.99, [0.92, 1.05]), "break_test")
        big = [d for d in l["detect_by_size"] if d["bin"].startswith("100 ha")]
        eq((big[0]["n"], big[0]["detected"]), (35, 0.91), "100 ha 이상 감지율")
        eq(len(l["annual"]["before"]), len(l["annual"]["years"]), "annual before·years 길이")
        return "risk_share %s (명세 예시 0.64)" % st["risk_share"]["value"]
    rep.check("3 #13·7-10", "/landing 한국", landing_kor)

    def landing_bc():
        l = ok_json(c.get("%s/BC/landing" % A), "/landing BC")
        st = {s["key"]: s for s in l["stats"]}
        eq((st["burned_area_record"]["year"], st["burned_area_record"]["value"]), (2023, 2896225), "burned_area_record")
        eq((st["case_month_ratio"]["month"], st["case_month_ratio"]["value"], st["case_month_ratio"]["rank"]),
           ("2023-05", 22.03, 1), "case_month_ratio")
        eq((st["risk_share"]["value"], st["risk_share"]["months"]), (0.51, [7, 8]), "risk_share")
        ob = l["official_break"]
        eq((ob["before"], ob["after"], ob["inconclusive"]), (4.99, 2.14, True), "official_break")
    rep.check("7-13", "/landing BC", landing_bc)

    def landing_matches_meta():
        diffs = []
        for cc in ("CAL", "BC"):
            a = ok_json(c.get("%s/%s/meta" % (A, cc)), cc)["validation"]["official_break"]
            b = ok_json(c.get("%s/%s/landing" % (A, cc)), cc)["official_break"]
            if a is None or b is None:
                diffs.append("%s meta %s / landing %s" % (cc, a, b))
                continue
            if (a["before"], a["after"], a["inconclusive"]) != (b["before"], b["after"], b["inconclusive"]):
                diffs.append("%s meta %s / landing %s" % (cc, a, b))
        assert not diffs, "; ".join(diffs)
        return "CAL·BC 같음"
    rep.check("A↔B", "/meta와 /landing의 official_break가 같은 값", landing_matches_meta)


def sweep_all(c, rep):
    A = "/api/v1"
    jobs = []
    for cc in ("KOR", "CAL", "BC"):
        meta = ok_json(c.get("%s/%s/meta" % (A, cc)), cc)
        last = meta["data_end"][:7]
        for x in ok_json(c.get("%s/%s/regions" % (A, cc)), cc)["items"]:
            rid = urllib.parse.quote(x["id"])
            for p in ("/regions/%s" % rid, "/baseline/%s" % rid, "/series/%s" % rid,
                      "/judgement/%s?month=%s" % (rid, last), "/judgement/%s?month=2020-08" % rid):
                jobs.append("%s/%s%s" % (A, cc, p))
            jobs.append("%s/%s/export.csv?id=%s" % (A, cc, rid))

    fails, enum_bad = [], []

    def one(path):
        r = c.get(urllib.parse.unquote(path))
        if r.status != 200:
            return path, "HTTP %s %s" % (r.status, r.text[:120]), []
        if path.split("?")[0].endswith(".csv"):
            return path, None, []
        return path, None, enum_errors(r.json())

    started = time.time()
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        for path, err, bad in ex.map(one, jobs):
            if err:
                fails.append("%s → %s" % (path, err))
            if bad:
                enum_bad.append("%s → %s" % (path, bad[:3]))
    took = time.time() - started
    rep.add("전수", "모든 구역 × (구역·평년·시계열·판정 2달·CSV) 200 응답", not fails,
            "%d건 %.0f초%s" % (len(jobs), took, "" if not fails else "; 실패 %d: %s" % (len(fails), fails[:3])))
    rep.add("전수", "응답 안의 코드값이 모두 명세 2장 값", not enum_bad,
            "%d건 검사%s" % (len(jobs), "" if not enum_bad else "; %s" % enum_bad[:3]))


# --------------------------------------------------------------------------- 출력
def write(rep, args, client, out):
    c = rep.counts()
    lines = ["# API 명세 v1.0 테스트 결과", "",
             "- 대상: %s" % (args.base or "로컬 catalog %s · timeseries %s · spatial %s · insight %s" % (args.catalog, args.timeseries, args.spatial, args.insight)),
             "- 시각: %s" % time.strftime("%Y-%m-%d %H:%M:%S"),
             "- 결과: **PASS %d · FAIL %d · NOTE %d · SKIP %d** (요청 %d건)" % (c["PASS"], c["FAIL"], c["NOTE"], c["SKIP"], client.calls), "",
             "| 명세 | 항목 | 결과 | 내용 |", "|---|---|---|---|"]
    icon = {"PASS": "✅", "FAIL": "❌", "SKIP": "⏭️", "NOTE": "📝"}
    for sec, name, st, detail in rep.rows:
        lines.append("| %s | %s | %s %s | %s |" % (sec, name, icon[st], st, detail.replace("|", "\\|").replace("\n", " ")))
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", help="한 주소로 모두 (예: https://burnline-api.unzipp.cloud)")
    ap.add_argument("--catalog", default="http://localhost:8081")
    ap.add_argument("--timeseries", default="http://localhost:8082")
    ap.add_argument("--spatial", default="http://localhost:8083")
    ap.add_argument("--insight", default="http://localhost:8084")
    ap.add_argument("--no-b", action="store_true", help="B 파트(spatial·insight) 검사 건너뛰기")
    ap.add_argument("--sweep", action="store_true", help="모든 구역 전수 검사")
    ap.add_argument("--out", default=str(Path(__file__).with_name("report.md")))
    args = ap.parse_args()

    client = Client(args.base, args.catalog, args.timeseries, args.spatial, args.insight, b_enabled=not args.no_b)
    rep = Report()
    run(client, rep, args.sweep)
    write(rep, args, client, Path(args.out))

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    for sec, name, st, detail in rep.rows:
        if st != "PASS":
            print("%-4s [%s] %s — %s" % (st, sec, name, detail))
    c = rep.counts()
    print("\nPASS %d · FAIL %d · NOTE %d · SKIP %d  (요청 %d건) → %s"
          % (c["PASS"], c["FAIL"], c["NOTE"], c["SKIP"], client.calls, args.out))
    sys.exit(1 if c["FAIL"] else 0)


if __name__ == "__main__":
    main()
