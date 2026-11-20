# 稷下書院（novel543.com）— SJJ Reader Python 書源
# 站點：https://www.novel543.com
#
# 結構（2026-11-20 以瀏覽器實際探查確認，未登入、未付費）：
#   搜尋：/search/{關鍵字 URL 編碼}，例如 /search/%E6%96%97%E7%A0%B4%E8%92%BC%E7%A9%B9
#   詳情：/{10 位數字書號}/，例如 /0802709399/
#   目錄：/{書號}/dir，全部章節在同一頁，無分頁
#   章節：/{書號}/{前綴}_{章號}.html，例如 /0802709399/8096_1.html
#         （前綴 8096 是該書專屬編號，須從目錄頁實際抓取，不可猜測）
#   正文：實際在 look.twword.com，路徑相同，例如
#         https://look.twword.com/0802709399/8096_1.html
#         章節分頁：/{書號}/{前綴}_{章號}_{頁號}.html（標題尾端標 (x/y)）
#
# 注意：
#   1. 搜尋結果頁版面（2026-11-20 使用者手機 Brave 截圖確認）：
#      結果是一列圖文卡片，左封面、右側依序是粗體書名、簡介、作者行、主角行；
#      作者行沒有「作者：」字樣，只有圖示＋名字；另有「主角：」行。
#      搜尋解析改按此版面寫：標題不一定在連結文字裡，改從卡片找；
#      作者取簡介之後、主角行之前最近的短行。
#   2. 正文要去掉每頁末尾的「溫馨提示」與 VIP 免廣告連結，以及內嵌 iframe 留下的空白。
#   3. 手機網路若也被 Cloudflare 擋，搜尋／詳情／正文都會是空的；
#      先用手機瀏覽器開 https://www.novel543.com/ 看看能不能正常載入。
#   4. 本站搜尋頁會觸發 Cloudflare 托管驗證，後台 WebView 等不到它放行；
#      被擋時改彈可見驗證窗（verification_activity_get）讓使用者過一次驗證，
#      通過後 cookie 留在全域 CookieManager，之後的請求就能走。
#      驗證窗跳出後，請等頁面真的載入再按「完成」。
#      注意：成功一次後旗標會復位，下次再被擋會再彈窗（不要只彈一次就鎖死，
#      否則第二次搜尋會直接失敗、只能重開 App 才能再搜）。
from urllib.parse import urljoin, quote, urlparse
import json
import re

import requests
from bs4 import BeautifulSoup

from log import log

_session = requests.Session()
_session.trust_env = False

_SITE_URL = "https://www.novel543.com"
_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-TW,zh;q=0.9",
}

_BOOK_RE = re.compile(r"^/(\d{6,12})/$")                # /0802709399/
_CHAPTER_RE = re.compile(r"^/(\d+)/(\d+)_(\d+)\.html$")  # /0802709399/8096_1.html
_PAGE_RE = re.compile(r"\((\d+)/(\d+)\)\s*$")            # 標題尾端 (1/2)
_TITLE_RE = re.compile(r"《(.+?)》\((.+?)\)")            # <title>《書名》(作者)小說在線閱讀


_verify_done = False  # 本次 _fetch 是否已彈過驗證窗；成功後復位，下次被擋可再彈

_CF_MARKERS = ("just a moment", "cf-challenge", "checking your browser",
               "attention required", "turnstile", "challenge-platform",
               "请稍候", "正在检查")


def _fetch_raw(url):
    resp = _session.get(url, headers=_HEADERS, timeout=30)
    resp.encoding = resp.apparent_encoding or "utf-8"
    return resp


def _looks_blocked(resp):
    """回應是不是 Cloudflare 驗證頁（JSON 的 403 不算）。"""
    if resp is None or resp.status_code not in (403, 503):
        return False
    ct = (resp.headers.get("Content-Type") or "").lower()
    if "application/json" in ct:
        return False
    return any(m in (resp.text or "").lower() for m in _CF_MARKERS)


def _html_looks_blocked(html):
    low = (html or "").lower()
    return any(m in low for m in _CF_MARKERS)


def _sync_cookies(url):
    """把 WebView 全域 CookieManager 裡的 cookie 同步進 Session；失敗靜默。"""
    try:
        from android.webkit import CookieManager
        raw = CookieManager.getInstance().getCookie(url)
        if not raw:
            return 0
        host = urlparse(url).hostname or ""
        count = 0
        for part in raw.split(";"):
            part = part.strip()
            if "=" not in part:
                continue
            name, value = part.split("=", 1)
            name, value = name.strip(), value.strip()
            if not name:
                continue
            try:
                _session.cookies.set(name, value, domain=host)
                count += 1
            except Exception:
                pass
        return count
    except Exception:
        return 0


def _verify_once(url):
    """彈一次可見驗證窗；回傳驗證後頁面的 HTML（失敗／取消／仍是驗證頁回 None）。"""
    global _verify_done
    if _verify_done:
        return None
    _verify_done = True
    try:
        from SessionManager import verification_activity_get
        raw = verification_activity_get(url, _HEADERS, verificationKey="稷下書院")
        if not raw:
            return None
        html = (json.loads(raw).get("html") or "").strip()
        if not html:
            return None
        if _html_looks_blocked(html):
            # 使用者按完成時還在驗證頁：不算驗證過，下次被擋可再彈
            _verify_done = False
            return None
        return html
    except Exception as e:
        log("novel543 verify failed: %s" % e)
        return None


def _synth_resp(url, html):
    resp = requests.Response()
    resp.status_code = 200
    resp._content = html.encode("utf-8", "ignore")
    resp.headers["Content-Type"] = "text/html; charset=utf-8"
    resp.url = url
    resp.reason = "OK"
    resp.encoding = "utf-8"
    return resp


def _fetch(url):
    """requests 優先；被 Cloudflare 擋時彈驗證窗一次，同步 cookie 後重試，
    仍不行就直接用驗證後的頁面回傳。成功後復位旗標，下次被擋可再彈。"""
    global _verify_done
    try:
        resp = _fetch_raw(url)
        if not _looks_blocked(resp):
            _verify_done = False
            return resp
    except Exception as e:
        log("novel543 fetch failed: %s" % e)
        resp = None
    html = _verify_once(url)
    if html:
        _sync_cookies(url)
        try:
            resp2 = _fetch_raw(url)
            if not _looks_blocked(resp2):
                _verify_done = False
                return resp2
        except Exception as e:
            log("novel543 fetch after verify failed: %s" % e)
        _verify_done = False
        return _synth_resp(url, html)
    if resp is not None:
        return resp
    raise requests.exceptions.ConnectionError("novel543 unreachable after verification")


def _soup(resp):
    text = resp.text or ""
    return BeautifulSoup(text, "html.parser") if text else None


def getSiteUrl():
    return _SITE_URL


def search(query):
    url = _SITE_URL + "/search/" + quote(query, safe="")
    try:
        resp = _fetch(url)
    except Exception as e:
        log("novel543 search failed: %s" % e)
        return []
    soup = _soup(resp)
    if soup is None:
        return []
    books = []
    seen = set()
    seen_titles = set()
    for a in soup.find_all("a", href=True):
        href = (a.get("href") or "").strip()
        if not href:
            continue
        # 連結可能是完整網址，先取 path
        path = urlparse(href).path if href.startswith("http") else href
        if not _BOOK_RE.match(path):
            continue
        book_url = urljoin(_SITE_URL, path)
        if book_url in seen:
            continue
        seen.add(book_url)
        # 往上找結果卡片：包含封面圖、且文字夠長（有簡介），最多 4 層
        container = a
        for _ in range(4):
            parent = container.parent
            if parent is None or parent is soup:
                break
            container = parent
            if container.find("img") and len(container.get_text(" ", strip=True)) > 60:
                break
        # 標題：連結文字優先；封面連結本身沒文字時，從卡片找標題元素
        title = a.get_text(" ", strip=True)
        if not title or len(title) > 40:
            title = ""
            for tag in container.find_all(["h1", "h2", "h3", "h4", "b", "strong"]):
                t = tag.get_text(" ", strip=True)
                if 2 <= len(t) <= 40:
                    title = t
                    break
        if not title:
            continue
        cover = ""
        img = container.find("img")
        if img and img.get("src"):
            cover = urljoin(_SITE_URL, img["src"])
        # 作者：卡片文字按行拆；有「作者：」就取其後，否則取簡介之後、
        # 主角行之前最近的短行（作者行只有圖示＋名字，沒有「作者：」字樣）
        author = ""
        lines = [ln.strip() for ln in container.get_text("\n", strip=True).split("\n")]
        lines = [ln for ln in lines if ln and ln != title]
        m = re.search(r"作者[：:\s]*([^\s]{1,12})", " ".join(lines))
        if m:
            author = m.group(1)
        if not author:
            zhu_idx = next((i for i, ln in enumerate(lines) if ln.startswith("主角")), None)
            candidates = lines[:zhu_idx] if zhu_idx is not None else lines
            for ln in reversed(candidates):
                if 2 <= len(ln) <= 12 and "搜尋" not in ln and "結果" not in ln:
                    author = ln
                    break
        # 同書名＋同作者只留一筆（站方可能把同一本書掛在多個書號下）
        title_author = (title, author)
        if title_author in seen_titles:
            continue
        seen_titles.add(title_author)
        books.append({
            "bookTitle": title,
            "bookUrl": book_url,
            "bookAuthor": author,
            "bookCover": cover,
        })
    return books


def getDetails(url):
    book_url = url.split(",", 1)[0].rstrip("/") + "/"
    resp = _fetch(book_url)
    soup = _soup(resp)
    title, author, cover, intro = "", "", "", ""
    if soup is not None:
        t = soup.title.get_text(strip=True) if soup.title else ""
        m = _TITLE_RE.search(t)
        if m:
            title, author = m.group(1), m.group(2)
        if not title:
            h1 = soup.find("h1")
            if h1:
                title = h1.get_text(strip=True)
        meta = soup.find("meta", attrs={"property": "og:image"})
        if meta and meta.get("content"):
            cover = urljoin(_SITE_URL, meta["content"])
        if not cover:
            img = soup.find("img", src=re.compile("thumb"))
            if img and img.get("src"):
                cover = urljoin(_SITE_URL, img["src"])
        desc = soup.find("meta", attrs={"name": "description"})
        if desc and desc.get("content"):
            intro = desc["content"].strip()
        if not intro:
            paras = [p.get_text(" ", strip=True) for p in soup.find_all("p")]
            paras = [p for p in paras if len(p) > 30 and "溫馨提示" not in p and "VIP" not in p]
            if paras:
                intro = max(paras, key=len)
    # 目錄頁：全部章節在同一頁
    chapters = []
    toc_url = book_url + "dir"
    try:
        toc_soup = _soup(_fetch(toc_url))
    except Exception as e:
        log("novel543 toc failed: %s" % e)
        toc_soup = None
    if toc_soup is not None:
        seen = set()
        for a in toc_soup.find_all("a", href=True):
            href = (a.get("href") or "").strip()
            if not _CHAPTER_RE.match(href):
                continue
            name = a.get_text(" ", strip=True)
            if not name:
                continue
            curl = urljoin(_SITE_URL, href)
            if curl in seen:
                continue
            seen.add(curl)
            chapters.append({"title": name, "url": curl})
    return {
        "url": book_url,
        "title": title,
        "author": author,
        "cover": cover,
        "intro": intro,
        "chapterList": chapters,
    }


def _content_paras(soup):
    """回傳 (段落文字列表, 標題尾端 (x/y) 的總頁數)。"""
    if soup is None:
        return [], 1
    main = soup.find("main") or soup
    h1 = main.find("h1")
    total = 1
    if h1:
        m = _PAGE_RE.search(h1.get_text(" ", strip=True))
        if m:
            try:
                total = int(m.group(2))
            except ValueError:
                total = 1
    paras = []
    for p in main.find_all("p"):
        t = p.get_text(" ", strip=True)
        if not t:
            continue
        if "溫馨提示" in t:
            continue
        if "VIP" in t and "免廣告" in t:
            continue
        paras.append(t)
    return paras, total


def getChapterContent(url):
    chapter_url = url.split(",", 1)[0]
    # 正文實際在 look.twword.com，路徑與 novel543.com 相同；
    # 直接換網域抓，避開 novel543 章節連結的中介跳轉頁。
    content_url = chapter_url.replace("www.novel543.com", "look.twword.com").replace(
        "novel543.com", "look.twword.com")
    paras, total = [], 1
    try:
        paras, total = _content_paras(_soup(_fetch(content_url)))
    except Exception as e:
        log("novel543 content failed: %s" % e)
    if not paras:
        # 備援：回原站抓一次（跟隨跳轉）
        try:
            paras, total = _content_paras(_soup(_fetch(chapter_url)))
        except Exception as e:
            log("novel543 content fallback failed: %s" % e)
    # 章節分頁：/{書號}/{前綴}_{章號}_{頁號}.html
    total = min(total, 10)
    for page in range(2, total + 1):
        page_url = re.sub(r"_(\d+)\.html$", r"_\1_%d.html" % page, content_url)
        if page_url == content_url:
            break
        try:
            more, _ = _content_paras(_soup(_fetch(page_url)))
            paras.extend(more)
        except Exception as e:
            log("novel543 content page %d failed: %s" % (page, e))
            break
    return "\n".join(paras)


def verify(query=""):
    try:
        q = query or "斗破苍穹"
        books = search(q)
        if not books:
            return 1
        details = getDetails(books[0]["bookUrl"])
        chapters = details.get("chapterList") or []
        if not chapters:
            return 1
        content = getChapterContent(chapters[0]["url"])
        return 0 if len(content or "") > 10 else 1
    except Exception as e:
        log("verify failed: %s" % e)
        return 1


if __name__ == "__main__":
    print(">>>>>>search")
    result = search("斗破苍穹")
    print(result[:2])
    print(">>>>>>getDetails")
    result = getDetails(result[0]["bookUrl"])
    print({k: (v if k != "chapterList" else v[:3]) for k, v in result.items()})
    print(">>>>>>getChapterContent")
    result = getChapterContent(result["chapterList"][0]["url"])
    print(result[:500])
