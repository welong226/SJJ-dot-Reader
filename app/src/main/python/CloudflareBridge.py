"""Optional helpers for Python book sources that want explicit Cloudflare cookie access.

Most sources need no changes: exec_script patches requests.Session.request to detect
CF challenges, open the WebView bridge, and retry with cookies + matching User-Agent.

Use these helpers only when a source must pre-warm cookies or read host credentials
outside the automatic path.
"""

from java.util import HashMap

from exec_script import ScriptAborted, check_aborted, is_cancelled_exception


def _bridge():
    from com.sjianjun.reader.http import CloudflareCookieBridge
    return CloudflareCookieBridge


def _call(fn, *args):
    check_aborted()
    try:
        return fn(*args)
    except ScriptAborted:
        raise
    except BaseException as e:
        if is_cancelled_exception(e):
            raise ScriptAborted("脚本任务已取消")
        raise


def _to_java_headers(headers):
    java_headers = HashMap()
    if not headers:
        return java_headers
    for key, value in headers.items():
        java_headers.put(str(key), str(value))
    return java_headers


def _to_py_map(java_map):
    if java_map is None:
        return None
    try:
        return {str(k): str(java_map.get(k)) for k in java_map.keySet()}
    except Exception:
        try:
            return dict(java_map)
        except Exception:
            return None


def get_host_cookies(url):
    """Return cached cookie/UA map for url's host, or None. Does not open WebView."""
    return _to_py_map(_call(_bridge().getCachedCredentials, url))


def refresh_host_cookies(url, user_agent=None):
    """Read CookieManager for url; return credentials if cf_clearance present."""
    return _to_py_map(_call(_bridge().refreshFromCookieManager, url, user_agent))


def ensure_host_cookies(url, headers=None, force=False):
    """Ensure CF credentials: cache → CookieManager → WebView challenge UI if needed."""
    return _to_py_map(
        _call(_bridge().ensureCredentials, url, _to_java_headers(headers), bool(force))
    )


def clear_host_cookies(host_or_url):
    """Drop cached credentials for a host."""
    _call(_bridge().clearHost, host_or_url)


def is_challenge(status_code, headers=None, body_snippet=None):
    """True when the HTTP response looks like a Cloudflare challenge page."""
    return bool(
        _call(
            _bridge().isChallenge,
            int(status_code),
            _to_java_headers(headers),
            body_snippet or "",
        )
    )
