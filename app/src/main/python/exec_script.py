import copy
import inspect
import json
import socket
import threading
import time
from urllib.parse import urlencode, urlparse

# ==================== 可取消的脚本任务 ====================
# Java 侧每次脚本调用前会分配一个任务标识（set_current_task / clear_current_task）。
# 任务期间建立的网络连接、派生的线程都登记到该任务上；取消时由 abort(key) 依次执行：
#   1) 关闭登记的网络连接 -> 阻塞中的 recv/send 立刻返回（shutdown 会唤醒阻塞的读）
#   2) 向登记的执行线程注入 ScriptAborted -> 脚本在字节码边界抛出异常并退出
#   3) 保留该脚本的命名空间缓存 -> Session/连接池可继续复用，下一次请求不用重新建连
# 另外默认给脚本补网络超时，避免脚本没写 timeout 时无限等待。
# 任务取消后：后续网络操作在"调用前"就被拦下（check_aborted），不再白等、不再新建 WebView/弹验证页；
# 正在阻塞中的 socket / web_get / verification_activity_get 由上面的 1)、2) 立刻打断。
# 注意：线程若阻塞在不可干预的 C 扩展内部（例如 re 的灾难性回溯），注入要等它返回才生效，
# 这类情况单进程无法打断，只能靠进程隔离。

DEFAULT_SOCKET_TIMEOUT = 30.0    # 秒：原始 socket 没显式设置时的默认超时
DEFAULT_CONNECT_TIMEOUT = 5.0    # 秒：requests 建连超时（DNS/握手卡住时快速失败，而不是等满 30s）
DEFAULT_READ_TIMEOUT = 15.0      # 秒：requests 读取超时
_ABORTED_KEY_LIMIT = 64          # 已取消任务标识的保留数量，用于拦住"取消后继续重试"的连接
_NAMESPACE_IDLE_SECONDS = 30 * 60  # 秒：脚本命名空间超过该时间未使用则丢弃（连同 Session、连接池）
_DNS_CACHE_TTL_SECONDS = 120.0   # 秒：getaddrinfo 结果缓存时间，避免同一域名反复解析


class ScriptAborted(BaseException):
    """脚本任务被主动取消。继承 BaseException，脚本里的 except Exception 抓不到。"""


class _TaskState:
    __slots__ = ("key", "thread_ids", "sockets", "refs")

    def __init__(self, key):
        self.key = key
        self.thread_ids = set()
        self.sockets = set()
        self.refs = 0          # 同一个 key 上并发了几次调用


# 脚本内容 -> [命名空间, 最后一次使用时间]。条目会被 _cleanup_namespaces 按空闲时间回收
_namespace_cache = {}
_namespace_cache_lock = threading.RLock()
_tasks_lock = threading.RLock()
_task_states = {}
_aborted_keys = []
# 线程号 -> 当前任务状态。这里不能用 threading.local()：
# Chaquopy 的每一次 Java -> Python 调用拿到的不是同一个 thread state，而 threading.local
# 的数据正挂在 thread state 上，于是 set_current_task 写入的值，在下一次调用里读出来永远是 None，
# 导致 Thread.start 钩子直接放行、脚本自建线程和连接从不登记，取消时一个都清不掉。
_thread_keys = {}


def _load_async_raise():
    try:
        import ctypes
        # PyThreadState_SetAsyncExc(thread_id, exc)：向目标线程异步注入异常
        fn = ctypes.pythonapi.PyThreadState_SetAsyncExc
        fn.argtypes = (ctypes.c_ulong, ctypes.py_object)
        fn.restype = ctypes.c_int
        return fn, ctypes
    except Exception:
        return None, None


_set_async_exc, _ctypes = _load_async_raise()


def _current_key():
    state = _thread_keys.get(threading.get_ident())
    return state.key if state is not None else None


def check_aborted():
    """调用前拦截：当前脚本任务已取消时立刻抛 ScriptAborted，不再发起任何后续动作。

    供各 API 包装层（web_get / verification_activity_get / requests …）在真正调用前使用，
    避免任务已取消还去新建 WebView、弹验证页、发网络请求。
    """
    key = _current_key()
    if _is_aborted(key):
        raise ScriptAborted("脚本任务已取消")
    return key


def is_cancelled_exception(e):
    """是否为"任务被取消"类异常。

    Java 侧取消抛的是 ScriptTaskCancelledException，经 Chaquopy 传回 Python 时是普通异常，
    包装层用它把两种情况统一成 ScriptAborted（继承 BaseException，脚本的 except Exception 抓不到）。
    """
    if isinstance(e, ScriptAborted):
        return True
    text = "%s %s" % (type(e), e)
    return "ScriptAborted" in text or "ScriptTaskCancelled" in text


def _mark_aborted(key):
    if key in _aborted_keys:
        return
    _aborted_keys.append(key)
    if len(_aborted_keys) > _ABORTED_KEY_LIMIT:
        del _aborted_keys[0]


def _is_aborted(key):
    return key is not None and key in _aborted_keys


def set_current_task(key):
    """Java 侧在脚本调用开始前调用：把当前线程登记到该任务上。"""
    if key is None:
        return None
    with _tasks_lock:
        state = _task_states.get(key)
        if state is None:
            state = _TaskState(key)
            _task_states[key] = state
            # 同一个 key 会被复用（例如同一本书再次加载）：新任务开始，清掉上一次的取消标记，
            # 否则新任务的所有网络操作都会被"任务已取消"直接拦掉
            if key in _aborted_keys:
                _aborted_keys.remove(key)
        state.refs += 1
        state.thread_ids.add(threading.get_ident())
        _thread_keys[threading.get_ident()] = state
    return key


def clear_current_task(key):
    """Java 侧在脚本调用结束后调用：注销本次调用，最后一个调用结束时才释放任务状态。"""
    if key is None:
        return
    with _tasks_lock:
        state = _thread_keys.pop(threading.get_ident(), None)
        if state is None:
            return
        state.refs -= 1
        if state.refs > 0:
            # 同一个 key 还有别的调用在跑（例如反复加载同一本书），状态要留给取消用
            return
        # 只摘掉自己那一份，如果已经被 abort() 摘走了就别动新下来的
        if _task_states.get(state.key) is state:
            _task_states.pop(state.key, None)
        # 任务彻底结束：清掉所有登记在本状态上的线程（含脚本自建线程 / 池化复用线程），
        # 否则这些线程的 _thread_keys 陈旧条目仍指向已死的 state。被别的任务复用该线程时，
        # 会误读到本任务的“已取消”标记（跨任务污染），且 abort 也清不掉它们（一直不取消）。
        # 用 list() 快照遍历，并加 `is state` 兜底，避免误删别的任务、或并发改写时删错条目。
        for tid in list(state.thread_ids):
            if _thread_keys.get(tid) is state:
                _thread_keys.pop(tid, None)
    state.sockets.clear()
    state.thread_ids.clear()


def abort(key):
    """取消一个脚本任务，返回是否命中。需要在其它线程调用（不能是正在执行脚本的线程）。"""
    if not key:
        return False
    with _tasks_lock:
        state = _task_states.pop(key, None)
        _mark_aborted(key)
    if state is None:
        return False
    # 1) 关闭登记的网络连接：阻塞中的 recv/send 会立刻返回
    for sock in list(state.sockets):
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass
    # 2) 向执行线程（含脚本自建线程）注入异常。跳过调用者自己：
    #    取消动作是在别的线程发起的，若把取消线程也打断反而会破坏调用方
    current_thread_id = threading.get_ident()
    for thread_id in list(state.thread_ids):
        if thread_id == current_thread_id:
            continue
        _async_raise(thread_id, ScriptAborted)
    # 3) 不丢弃脚本命名空间缓存：取消只靠上面的"关连接 + 注入异常 + 取消后拦截新请求"，
    #    保留命名空间可以让脚本的 Session/连接池继续复用，避免每次取消后重新 exec 脚本、
    #    重建连接（多一次 DNS/TLS）。被 shutdown 的连接会被 urllib3 判定为已断开并自动重建。
    with _tasks_lock:
        if _thread_keys.get(threading.get_ident()) is state:
            _thread_keys.pop(threading.get_ident(), None)
    return True


def _async_raise(thread_id, exc_type):
    """向指定线程注入异常；阻塞在 C 调用里时会在该调用返回后抛出。"""
    if _set_async_exc is None:
        return False
    try:
        result = _set_async_exc(_ctypes.c_ulong(thread_id), exc_type)
        if result > 1:
            # 影响到了多个线程状态，回滚后放弃
            _set_async_exc(_ctypes.c_ulong(thread_id), None)
            return False
        return result == 1
    except Exception:
        return False


def _register_socket(sock):
    """把连接登记到当前任务上，供 abort() 关闭。"""
    with _tasks_lock:
        state = _thread_keys.get(threading.get_ident())
        if state is not None:
            state.sockets.add(sock)


def _register_thread(key):
    """脚本自建线程启动时调用：把自己挂到父线程所属的任务状态上。"""
    my_id = threading.get_ident()
    with _tasks_lock:
        state = _task_states.get(key)
        if state is None:
            return
        state.thread_ids.add(my_id)
        _thread_keys[my_id] = state


def _mark_patched(fn):
    try:
        fn._liter_reader_patched = True
    except Exception:
        pass
    return fn


def _make_socket_hook(orig):
    """给阻塞式 socket 操作套一层：调用前拦截、阻塞时登记连接、取消后统一抛取消异常。"""

    def hook(self, *args, **kwargs):
        key = _current_key()
        # 1) 调用前拦截：任务已取消，不再发起任何网络操作（脚本重试也不会白等）
        if _is_aborted(key):
            raise ScriptAborted("脚本任务已取消")
        if key is not None:
            # 2) 登记连接：每次阻塞式操作都登记，连接池里复用的长连接也能被 abort() 关掉，
            #    从而使阻塞中的 recv/send 立刻返回
            _register_socket(self)
        try:
            return orig(self, *args, **kwargs)
        except BaseException:
            # 3) 连接是被取消动作关掉的：把底层错误统一成取消异常，
            #    避免脚本/urllib3 把它当成网络错误去重试
            if _is_aborted(key):
                raise ScriptAborted("脚本任务已取消")
            raise

    return hook


# 会阻塞等待网络的操作：都要能在取消时被 shutdown 唤醒
_SOCKET_BLOCKING_OPS = ("connect", "recv", "recv_into", "recvfrom", "send", "sendall")


def _install_socket_hooks():
    """给脚本运行环境补默认超时，并把阻塞式连接登记到当前任务上。"""
    try:
        socket.setdefaulttimeout(DEFAULT_SOCKET_TIMEOUT)
    except Exception:
        pass

    for name in _SOCKET_BLOCKING_OPS:
        try:
            orig = getattr(socket.socket, name, None)
            if orig is None or getattr(orig, "_liter_reader_patched", False):
                continue
            setattr(socket.socket, name, _mark_patched(_make_socket_hook(orig)))
        except Exception:
            pass


# 进程内 DNS 结果缓存：脚本反复请求同一域名时，避免每次新连接都重新解析。
# 弱网/地铁下系统 DNS 抖动明显，缓存能减少"卡在解析"的等待；TTL 见 _DNS_CACHE_TTL_SECONDS。
_dns_cache = {}
_dns_cache_lock = threading.Lock()
_DNS_CACHE_MAX = 512


def _install_dns_hook():
    try:
        if getattr(socket.getaddrinfo, "_liter_reader_patched", False):
            return
        orig_getaddrinfo = socket.getaddrinfo

        def cached_getaddrinfo(host, *args, **kwargs):
            if not isinstance(host, str) or not host:
                return orig_getaddrinfo(host, *args, **kwargs)
            now = time.time()
            key = (host, args, tuple(sorted(kwargs.items())))
            with _dns_cache_lock:
                entry = _dns_cache.get(key)
                if entry is not None and now - entry[1] < _DNS_CACHE_TTL_SECONDS:
                    return entry[0]
            result = orig_getaddrinfo(host, *args, **kwargs)
            with _dns_cache_lock:
                _dns_cache[key] = [result, time.time()]
                if len(_dns_cache) > _DNS_CACHE_MAX:
                    _dns_cache.clear()
            return result

        socket.getaddrinfo = _mark_patched(cached_getaddrinfo)
    except Exception:
        pass


# ==================== Cloudflare 透明降级：requests 被挡时改走 WebView ====================
# 目标：书源一行都不用改。requests 拿到 Cloudflare 验证页时，自动用后台 WebView
# （真浏览器，会跑 JS）重抓一次，把回来的 HTML 包成 requests.Response 还给书源。
# 触发条件：HTTP 403/503 + 页面像 Cloudflare 验证页，或 403/503 + 响应头带 cf-ray
# 且 Content-Type 是 HTML（JSON 接口的 403 不触发，避免把 JSON 包成 HTML）。
# 另外两个优化：
#   1) 某域名经 WebView 成功一次后记下来（_CF_WEBVIEW_HOSTS，默认 30 分钟），
#      其间同域名请求直接走 WebView，不再先浪费一次被挡的 requests；
#   2) WebView 过关后，把全局 CookieManager 里的 cookie（主要是 cf_clearance）
#      同步进当前 Session，之后同站 requests 直接过，不用再开 WebView。
# 限制：web_get 只回 HTML（GET；POST 用 web_post），响应头/状态码是合成的；
# WebView 抓一次要几秒，只对被挡的站用；遇到要人手点的验证框时 WebView 也过不了，
# 那时应由书源改调 verification_activity_get（弹可见 WebView 让人点一次），本层不自动弹。

_CF_CHALLENGE_MARKERS = (
    "just a moment", "cf-challenge", "checking your browser",
    "attention required", "turnstile", "challenge-platform",
    "请稍候", "正在检查",
)
_CF_WEBVIEW_HOST_TTL = 30 * 60  # 秒：某域名经 WebView 成功后，直接走 WebView 的有效期
_CF_WEBVIEW_TIMEOUT = 30  # 秒：单次 WebView 抓取超时
_CF_WEBVIEW_HOSTS = {}  # host -> expiry timestamp
_CF_WEBVIEW_HOSTS_LOCK = threading.Lock()


def _log_cf(msg):
    """降级日志：能打到 App 日志就打，打不出就静默（绝不能影响请求本身）。"""
    try:
        from log import log
        log("[cf-fallback] " + msg)
    except Exception:
        pass


def _html_has_cf_markers(html):
    try:
        text = (html or "")[:65536].lower()
    except Exception:
        return False
    return any(m in text for m in _CF_CHALLENGE_MARKERS)


def _looks_like_cf_challenge(resp):
    """响应是否像 Cloudflare 验证页。"""
    if resp is None:
        return False
    try:
        status = resp.status_code
    except Exception:
        return False
    if status not in (403, 503):
        return False
    try:
        body = resp.text or ""
    except Exception:
        body = ""
    if _html_has_cf_markers(body):
        return True
    # 无明显标记时：只有 Cloudflare 前置 + 返回 HTML 才值得试一次 WebView，
    # JSON 接口的 403（例如鉴权失败）不触发，避免把 JSON 包成 HTML。
    try:
        headers = {k.lower(): v for k, v in (resp.headers or {}).items()}
    except Exception:
        headers = {}
    content_type = headers.get("content-type", "")
    return "cf-ray" in headers and "text/html" in content_type


def _url_host(url):
    try:
        return urlparse(url or "").hostname or ""
    except Exception:
        return ""


def _webview_host_remembered(host):
    if not host:
        return False
    now = time.time()
    with _CF_WEBVIEW_HOSTS_LOCK:
        expiry = _CF_WEBVIEW_HOSTS.get(host)
        if expiry is None:
            return False
        if now > expiry:
            del _CF_WEBVIEW_HOSTS[host]
            return False
        return True


def _webview_host_remember(host, ttl=_CF_WEBVIEW_HOST_TTL):
    if not host:
        return
    with _CF_WEBVIEW_HOSTS_LOCK:
        _CF_WEBVIEW_HOSTS[host] = time.time() + ttl


def _webview_host_forget(host):
    if not host:
        return
    with _CF_WEBVIEW_HOSTS_LOCK:
        _CF_WEBVIEW_HOSTS.pop(host, None)


def _webview_fetch(method, url, headers, data=None, timeout=_CF_WEBVIEW_TIMEOUT):
    """经后台 WebView 抓一页，返回 (final_url, html)；失败返回 (None, None)。

    web_get/web_post 返回的是 JSON 字符串 {"url": ..., "html": ...}。
    懒加载 import，避免与 WebViewClient.py 的循环引用（它 import exec_script）。
    """
    try:
        from WebViewClient import web_get, web_post
        if method == "POST":
            raw = web_post(url, data or "", timeout=int(timeout * 1000))
        else:
            raw = web_get(url, headers or {}, timeout=int(timeout * 1000))
        if not raw:
            return None, None
        parsed = json.loads(raw)
        html = parsed.get("html") or ""
        if not html:
            return None, None
        return parsed.get("url") or url, html
    except Exception:
        return None, None


def _sync_webview_cookies(session, url):
    """把 WebView 全局 CookieManager 里的 cookie 同步进 Session；返回同步的条数。

    WebView 过关后 Cloudflare 会下 cf_clearance 等 cookie，全局 CookieManager 会保留
    它们（见 WebViewVerificationActivity 的注释）。同步后同站 requests 可能直接过。
    失败就静默跳过：这只是优化，降级本身不靠它。
    """
    try:
        from android.webkit import CookieManager
        raw = CookieManager.getInstance().getCookie(url)
        if not raw:
            return 0
        host = _url_host(url)
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
                session.cookies.set(name, value, domain=host)
                count += 1
            except Exception:
                pass
        return count
    except Exception:
        return 0


def _synth_response(url, final_url, html, orig_resp=None):
    """把 WebView 抓回的 HTML 包成 requests.Response（状态码/响应头是合成的）。"""
    import requests
    synth = requests.Response()
    synth.status_code = 200
    synth._content = html.encode("utf-8", "ignore")
    synth.headers["Content-Type"] = "text/html; charset=utf-8"
    synth.url = final_url or url
    synth.reason = "OK"
    synth.encoding = "utf-8"
    if orig_resp is not None:
        try:
            synth.request = orig_resp.request
        except Exception:
            pass
    return synth


def _try_webview(session, method, url, kwargs, orig_resp=None):
    """经 WebView 抓一页并包成 Response；失败返回 None（调用方回落到原始响应）。"""
    if not url:
        return None
    try:
        headers = dict(kwargs.get("headers") or {})
    except Exception:
        headers = {}
    data = None
    if method == "POST":
        data = kwargs.get("data")
        if data is None and kwargs.get("json") is not None:
            try:
                data = json.dumps(kwargs.get("json"), ensure_ascii=False)
            except Exception:
                data = None
        elif isinstance(data, dict):
            try:
                data = urlencode(data)
            except Exception:
                data = None
        elif isinstance(data, (bytes, bytearray)):
            data = bytes(data).decode("utf-8", "ignore")
        elif data is not None:
            data = str(data)
    final_url, html = _webview_fetch(method, url, headers, data=data)
    if not html:
        return None
    if _html_has_cf_markers(html):
        # WebView 也拿到了验证页（例如要人手点的 Turnstile）：不记域名、不伪装成功。
        # 这种情况应由书源改调 verification_activity_get（弹可见 WebView 让人点一次）。
        _log_cf("WebView 仍被验证拦截：%s" % url)
        return None
    host = _url_host(url)
    _webview_host_remember(host)
    try:
        synced = _sync_webview_cookies(session, final_url or url)
    except Exception:
        synced = 0
    _log_cf("Cloudflare 降级：WebView 已代取 %s（同步 cookie %d 条）" % (url, synced))
    return _synth_response(url, final_url, html, orig_resp)


def _requests_call_info(args, kwargs):
    """从 Session.request 的调用参数里取出 (method, url)，兼容位置参数与关键字参数。"""
    method = kwargs.get("method", args[0] if len(args) > 0 else "GET")
    url = kwargs.get("url", args[1] if len(args) > 1 else "")
    return str(method or "GET").upper(), url or ""


def _install_requests_hooks():
    """脚本直接用 requests 时：调用前拦截 + 取消后统一异常 + 未指定时的默认超时。

    另加 Cloudflare 透明降级：响应像验证页时自动改走 WebView 重抓（见上节），
    命中时书源无感知；降级本身任何一步失败都退回原始响应，不把请求搞砸。
    """
    try:
        import requests
        if getattr(requests.Session.request, "_liter_reader_patched", False):
            return
        orig_request = requests.Session.request

        def request(self, *args, **kwargs):
            key = check_aborted()
            # requests 不传 timeout 时会永久等待；只在脚本没指定时补默认值（建连/读取分开，
            # 建连超时设短一点，DNS/握手卡住时能快速失败，脚本好重试或降级）
            if "timeout" not in kwargs and len(args) < 9:
                kwargs["timeout"] = (DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT)
            method, url = _requests_call_info(args, kwargs)
            host = _url_host(url)
            # 1) 该域名最近经 WebView 成功过：直接走 WebView，省一次被挡的尝试
            if host and _webview_host_remembered(host):
                synth = _try_webview(self, method, url, kwargs)
                if synth is not None:
                    return synth
                # WebView 失败：回落到正常 requests，并清除记忆，避免每次都先卡 WebView
                _webview_host_forget(host)
            # 2) 正常 requests
            try:
                resp = orig_request(self, *args, **kwargs)
            except BaseException:
                if _is_aborted(key):
                    raise ScriptAborted("脚本任务已取消")
                raise
            # 3) 命中 Cloudflare 验证页：改走 WebView 重抓
            try:
                if _looks_like_cf_challenge(resp):
                    synth = _try_webview(self, method, url, kwargs, resp)
                    if synth is not None:
                        return synth
            except BaseException:
                if _is_aborted(key):
                    raise ScriptAborted("脚本任务已取消")
                # 降级失败：退回原始响应
                pass
            return resp

        requests.Session.request = _mark_patched(request)
    except Exception:
        pass


def _install_thread_hooks():
    """脚本自建线程时也把线程登记到任务上（线程内继续归属同一任务）。"""
    try:
        if getattr(threading.Thread.start, "_liter_reader_patched", False):
            return
        orig_start = threading.Thread.start

        def start(self):
            key = _current_key()
            if key is None:
                return orig_start(self)
            orig_run = self.run

            def run_with_task(*args, **kwargs):
                _register_thread(key)
                try:
                    return orig_run(*args, **kwargs)
                finally:
                    my_id = threading.get_ident()
                    with _tasks_lock:
                        _thread_keys.pop(my_id, None)
                        state = _task_states.get(key)
                        if state is not None:
                            state.thread_ids.discard(my_id)

            self.run = run_with_task
            return orig_start(self)

        threading.Thread.start = _mark_patched(start)
    except Exception:
        pass


_install_dns_hook()
_install_socket_hooks()
_install_requests_hooks()
_install_thread_hooks()


# ==================== 脚本执行 ====================

def _cleanup_namespaces(now=None):
    """丢弃超过 [_NAMESPACE_IDLE_SECONDS] 未使用的脚本命名空间（连同脚本里的 Session、连接池）。

    在每次脚本调用（[_build_namespace]）时顺带检查，平时不占任何定时器/线程。
    """
    if now is None:
        now = time.time()
    with _namespace_cache_lock:
        stale = [script for script, entry in _namespace_cache.items()
                 if now - entry[1] > _NAMESPACE_IDLE_SECONDS]
        for script in stale:
            _namespace_cache.pop(script, None)
    return len(stale)


def _build_namespace(script):
    now = time.time()
    # 每次调用都顺带回收空闲超时的命名空间，避免旧脚本（Session、连接池）一直驻留
    _cleanup_namespaces(now)
    with _namespace_cache_lock:
        entry = _namespace_cache.get(script)
        if entry is not None:
            # 命中：刷新使用时间，保证常用脚本不会被回收
            entry[1] = now
            return entry[0]
    safe_builtins = copy.copy(__builtins__.__dict__) if hasattr(__builtins__, '__dict__') else copy.copy(__builtins__)
    namespace = {'__builtins__': safe_builtins}
    # exec 可能执行很久（脚本顶层代码），不要持有锁
    exec(script, namespace)
    with _namespace_cache_lock:
        _namespace_cache[script] = [namespace, time.time()]
    return namespace


def has_function(script, func):
    namespace = _build_namespace(script)
    target = namespace.get(func)
    return callable(target)


def warm_up_dns(hosts):
    """预热 DNS：解析给定域名（80/443），把结果填进 [_dns_cache]，供后续脚本请求直接复用。

    启动时在后台调用，让"第一个请求"不再卡在 DNS 解析上（弱网/地铁下尤其明显）。
    """
    import socket
    resolved = 0
    for host in hosts:
        if not isinstance(host, str) or not host:
            continue
        for port in (80, 443):
            try:
                socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
                resolved += 1
            except Exception:
                pass
    return resolved


def _accepts_two_args(target):
    # 老脚本可能只声明了一个参数，需要兼容
    try:
        parameters = list(inspect.signature(target).parameters.values())
    except (TypeError, ValueError):
        return True
    if any(p.kind == p.VAR_POSITIONAL for p in parameters):
        return True
    positional = [p for p in parameters
                  if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    return len(positional) >= 2


def exec_script(script, func, arg=None, arg2=None):
    # 脚本可能把默认超时改成 None，每次调用前补回来，保证"卡住"是有界的
    try:
        socket.setdefaulttimeout(DEFAULT_SOCKET_TIMEOUT)
    except Exception:
        pass
    # 1. 准备一个感觉的运行环境
    namespace = _build_namespace(script)
    target = namespace[func]
    if arg2 is None:
        if arg is None:
            result = target()
        else:
            result = target(arg)
    elif _accepts_two_args(target):
        result = target(arg, arg2)
    else:
        result = target(arg)
    if isinstance(result, dict):
        json_str = json.dumps(result, ensure_ascii=False)
    else:
        json_str = result
    return json_str
