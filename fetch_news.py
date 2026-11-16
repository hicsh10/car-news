#!/usr/bin/env python3
"""자동차 뉴스 수집기.

Google 뉴스 RSS에서 국내 10개, 해외 10개 자동차 뉴스를 모아 news.json으로 저장합니다.
해외 기사 제목은 한국어로 번역해 함께 저장합니다(번역 실패 시 원문 제목 사용).
외부 라이브러리 없이 파이썬 기본 기능만 사용하며, GitHub Actions가 매시간 실행합니다.
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from html import unescape

KST = timezone(timedelta(hours=9))
MAX_AGE_HOURS = 48
PER_CATEGORY = 10

KO = {"hl": "ko", "gl": "KR", "ceid": "KR:ko", "lang": "ko"}
EN = {"hl": "en-US", "gl": "US", "ceid": "US:en", "lang": "en"}

# 카테고리별 검색어. 앞에 있는 검색어일수록 먼저 뽑힙니다.
FEEDS = {
    "domestic": [
        ("현대차 OR 기아 OR 제네시스", KO),
        ("자동차 업계", KO),
        ("전기차 OR 자율주행 OR 배터리", KO),
        ("수입차 OR 신차 OR 완성차", KO),
    ],
    # 해외는 신뢰도 높은 매체 위주로 검색
    "global": [
        ("automaker OR carmaker site:reuters.com", EN),
        ("automaker OR carmaker OR autos site:cnbc.com OR site:bloomberg.com OR site:apnews.com", EN),
        ("electric vehicle OR EV site:electrek.co OR site:insideevs.com OR site:theverge.com OR site:techcrunch.com", EN),
        ("automaker OR carmaker site:autonews.com OR site:caranddriver.com OR site:motor1.com", EN),
        ("automaker OR carmaker", EN),
    ],
}

# 할인·광고성·상시 정보성 기사 제외
EXCLUDE = re.compile(
    r"할인|프로모션|이벤트|특가|쿠폰|혜택|\[포토\]|\[광고\]"
    r"|lease deal|best deals|coupon|who owns|every major|^\d+ (best|cheapest|most)|news roundup|weekly",
    re.I)


def feed_url(query, p):
    q = urllib.parse.quote(f"{query} when:2d")
    return f"https://news.google.com/rss/search?q={q}&hl={p['hl']}&gl={p['gl']}&ceid={p['ceid']}"


def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (car-news-app)"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read()


def _google(text, getter):
    url = ("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ko&dt=t&q="
           + urllib.parse.quote(text))
    data = json.loads(getter(url))
    return "".join(seg[0] for seg in data[0] if seg and seg[0]).strip()


def _mymemory(text, getter):
    url = ("https://api.mymemory.translated.net/get?langpair=en|ko&q=" + urllib.parse.quote(text))
    data = json.loads(getter(url))
    if int(data.get("responseStatus", 0)) != 200:
        raise RuntimeError(data.get("responseDetails") or data.get("responseStatus"))
    return unescape(data["responseData"]["translatedText"]).strip()


def translate_ko(text, getter=http_get, errors=None):
    """영어 제목을 한국어로 번역. 구글 번역 → MyMemory 순서로 시도하고, 모두 실패하면 None."""
    for name, fn in (("google", _google), ("mymemory", _mymemory)):
        try:
            out = fn(text, getter)
            if out and re.search(r"[가-힣]", out):
                return out
            raise RuntimeError("한국어 결과 없음")
        except Exception as e:
            if errors is not None and len(errors) < 6:
                errors.append(f"번역({name}): {type(e).__name__} {e}"[:200])
    return None


def load_cache(path):
    """이전에 번역해 둔 제목을 재사용해 번역 요청 수를 줄임."""
    try:
        with open(path, encoding="utf-8") as f:
            old = json.load(f)
        return {x["title"]: x["title_ko"]
                for x in old.get("categories", {}).get("global", []) if x.get("translated")}
    except Exception:
        return {}


def clean_title(title, source):
    title = unescape(title or "").strip()
    src = (source or "").strip().lower()
    while True:
        m = re.search(r"\s+[-–|]\s*([^-–|]*)$", title)
        tail = m.group(1).strip().lower() if m else None
        if m and (not tail or (src and (tail == src or (len(tail) >= 3 and (tail in src or src in tail))))):
            title = title[: m.start()].strip()
        else:
            break
    return title


def bigrams(title):
    s = re.sub(r"[\W_]+", "", title.lower())
    return {s[i:i + 2] for i in range(len(s) - 1)}


def is_dup(title, seen):
    """제목 글자 조합이 60% 이상 겹치면 같은 기사로 판단."""
    b = bigrams(title)
    if not b:
        return True
    for other in seen:
        if len(b & other) / len(b | other) >= 0.6:
            return True
    return False


def parse_feed(xml_bytes, category, lang):
    items = []
    root = ET.fromstring(xml_bytes)
    for it in root.iter("item"):
        src_el = it.find("source")
        source = (src_el.text or "").strip() if src_el is not None else ""
        title = clean_title(it.findtext("title"), source)
        link = (it.findtext("link") or "").strip()
        pub = it.findtext("pubDate")
        if not title or not link or not pub:
            continue
        try:
            dt = parsedate_to_datetime(pub)
        except (TypeError, ValueError):
            continue
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        items.append({
            "title": title,
            "link": link,
            "source": source,
            "published": dt.astimezone(KST).isoformat(),
            "category": category,
            "lang": lang,
        })
    return items


def collect(getter=http_get, cache=None):
    cache = cache or {}
    now = datetime.now(KST)
    cutoff = now - timedelta(hours=MAX_AGE_HOURS)
    seen = []
    result = {}
    errors = []
    for category, feeds in FEEDS.items():
        # 각 검색어 결과(구글 뉴스 중요도 순)를 번갈아 가며 뽑음
        queues = []
        for query, p in feeds:
            try:
                queues.append(parse_feed(getter(feed_url(query, p)), category, p["lang"]))
            except Exception as e:  # 한 피드가 실패해도 나머지는 계속
                errors.append(f"{query}: {e}")
        picked = []
        while len(picked) < PER_CATEGORY and any(queues):
            for q in queues:
                while q:
                    item = q.pop(0)
                    if (EXCLUDE.search(item["title"]) or is_dup(item["title"], seen)
                            or datetime.fromisoformat(item["published"]) < cutoff):
                        continue
                    seen.append(bigrams(item["title"]))
                    picked.append(item)
                    break
                if len(picked) >= PER_CATEGORY:
                    break
        picked.sort(key=lambda x: x["published"], reverse=True)
        result[category] = picked

    # 해외 기사 제목 한국어 번역
    for item in result.get("global", []):
        ko = cache.get(item["title"])
        if not ko:
            ko = translate_ko(item["title"], getter, errors)
            time.sleep(0.5)
        item["title_ko"] = ko or item["title"]
        item["translated"] = bool(ko)
    return {"updated": now.isoformat(), "categories": result, "errors": errors}


def main(out="news.json"):
    data = collect(cache=load_cache(out))
    total = sum(len(v) for v in data["categories"].values())
    if total == 0:
        # 수집이 전부 실패하면 기존 파일을 덮어쓰지 않음
        print("뉴스를 하나도 받지 못했습니다:", data["errors"], file=sys.stderr)
        sys.exit(1)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    counts = {k: len(v) for k, v in data["categories"].items()}
    print(f"저장 완료 {counts} ({data['updated']})")
    if data["errors"]:
        print("일부 실패:", data["errors"])


if __name__ == "__main__":
    main(*sys.argv[1:])
