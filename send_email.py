#!/usr/bin/env python3
"""매일 아침 자동차 뉴스 이메일 발송.

news.json의 국내 10개·해외 10개를 이메일로 보냅니다.
GitHub 저장소 Settings → Secrets and variables → Actions 에 아래 값이 있어야 합니다.
  GMAIL_USER          보내는 Gmail 주소
  GMAIL_APP_PASSWORD  Gmail 앱 비밀번호(16자리)
  MAIL_TO             받는 주소 (여러 명이면 쉼표로 구분)
값이 없으면 아무것도 보내지 않고 조용히 끝납니다.
"""
import json
import os
import smtplib
import sys
from datetime import datetime, timedelta, timezone
from email.header import Header
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import formataddr
from html import escape

KST = timezone(timedelta(hours=9))
APP_URL = os.environ.get("APP_URL", "https://hicsh10.github.io/car-news/")
WEEKDAY = "월화수목금토일"


def date_label(now):
    return f"{now.month}월 {now.day}일 ({WEEKDAY[now.weekday()]})"


def section_html(title, color, items):
    rows = []
    for i, n in enumerate(items, 1):
        foreign = n.get("lang") == "en"
        head = n.get("title_ko") if foreign else n["title"]
        orig = (f'<div style="font-size:13px;color:#6b7280;margin-top:3px">{escape(n["title"])}</div>'
                if foreign and n.get("translated") else "")
        rows.append(
            f'<tr><td style="padding:12px 0;border-bottom:1px solid #eceef1;vertical-align:top;'
            f'width:26px;color:#9ca3af;font-weight:700">{i}</td>'
            f'<td style="padding:12px 0;border-bottom:1px solid #eceef1">'
            f'<a href="{escape(n["link"])}" style="color:#111827;text-decoration:none;font-size:16px;'
            f'font-weight:600;line-height:1.45">{escape(head or n["title"])}</a>{orig}'
            f'<div style="font-size:12.5px;color:#6b7280;margin-top:5px">{escape(n.get("source", ""))}</div>'
            f'</td></tr>')
    return (f'<h2 style="font-size:16px;margin:28px 0 4px;color:{color}">{title} {len(items)}</h2>'
            f'<table role="presentation" width="100%" cellpadding="0" cellspacing="0">{"".join(rows)}</table>')


def build(data, now):
    c = data.get("categories", {})
    dom, glo = c.get("domestic", []), c.get("global", [])
    subject = f"🚗 오늘의 자동차 뉴스 · {date_label(now)}"
    html = f"""<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:#f4f5f7">
<div style="max-width:640px;margin:0 auto;padding:24px 18px;font-family:-apple-system,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;background:#fff">
<h1 style="font-size:22px;margin:0 0 4px">🚗 오늘의 자동차 뉴스</h1>
<div style="color:#6b7280;font-size:14px">{now.year}년 {date_label(now)} · 국내 {len(dom)}건 · 해외 {len(glo)}건</div>
{section_html("국내 뉴스", "#1d4ed8", dom)}
{section_html("해외 뉴스", "#b45309", glo)}
<div style="text-align:center;margin:30px 0 8px">
<a href="{APP_URL}" style="display:inline-block;background:#0f1b2d;color:#fff;text-decoration:none;padding:12px 22px;border-radius:999px;font-size:15px">앱에서 최신 뉴스 보기</a></div>
<div style="text-align:center;color:#9ca3af;font-size:12px;margin-top:14px">제목을 누르면 원문 기사로 이동합니다 · 해외 기사 제목은 자동 번역입니다</div>
</div></body></html>"""
    lines = [f"🚗 오늘의 자동차 뉴스 · {date_label(now)}", ""]
    for label, items in (("[국내 뉴스]", dom), ("[해외 뉴스]", glo)):
        lines.append(label)
        for i, n in enumerate(items, 1):
            lines.append(f"{i}. {n.get('title_ko') or n['title']} ({n.get('source', '')})")
            lines.append(f"   {n['link']}")
        lines.append("")
    lines.append(f"앱: {APP_URL}")
    return subject, html, "\n".join(lines)


def main(path="news.json"):
    user = os.environ.get("GMAIL_USER", "").strip()
    pw = os.environ.get("GMAIL_APP_PASSWORD", "").replace(" ", "").strip()
    to = [a.strip() for a in os.environ.get("MAIL_TO", "").split(",") if a.strip()]
    if not (user and pw and to):
        print("이메일 설정(GMAIL_USER, GMAIL_APP_PASSWORD, MAIL_TO)이 없어 발송을 건너뜁니다.")
        return
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not any(data.get("categories", {}).values()):
        print("보낼 뉴스가 없습니다.", file=sys.stderr)
        sys.exit(1)
    subject, html, text = build(data, datetime.now(KST))
    msg = MIMEMultipart("alternative")
    msg["Subject"] = str(Header(subject, "utf-8"))
    msg["From"] = formataddr((str(Header("자동차 뉴스", "utf-8")), user))
    msg["To"] = ", ".join(to)
    msg.attach(MIMEText(text, "plain", "utf-8"))
    msg.attach(MIMEText(html, "html", "utf-8"))
    with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=30) as s:
        s.login(user, pw)
        s.sendmail(user, to, msg.as_string())
    print(f"발송 완료: {len(to)}명")


if __name__ == "__main__":
    main(*sys.argv[1:])
