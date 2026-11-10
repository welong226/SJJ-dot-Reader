# 殓师灵异（优）
# group: 小说 书源
# bookSourceUrl: http://www.rulianshi.org/#pb1101
# Rules below are the Legado rules from pack 1331. No selectors were added.
# kind, wordCount, isVip, and imageStyle are not Reader book fields, so they are not read.

# Subset of Legado rule evaluation used by translated book sources.
# Supports CSS/JSoup-style selectors, a small JSONPath subset, a small XPath
# subset, || / && / ##, {{ }} rule templates, and @put / @get.
# Legado JavaScript (<js>, @js:, java.*, method calls) is rejected.

import json
import re
from urllib.parse import quote_plus, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TOC_PAGE_LIMIT = 40
CONTENT_PAGE_LIMIT = 15
_MODES = {"text", "owntext", "textnodes", "html", "all"}
_ATTRS = {
    "href", "src", "content", "alt", "title", "id", "class", "name",
    "value", "action", "datetime", "onclick",
}


class Unsupported(Exception):
    pass


def _split_top(rule, sep):
    parts = []
    buf = []
    curly = 0
    paren = 0
    brack = 0
    quote = None
    i = 0
    n = len(rule)
    while i < n:
        if quote:
            buf.append(rule[i])
            if rule[i] == "\\" and i + 1 < n:
                buf.append(rule[i + 1])
                i += 2
                continue
            if rule[i] == quote:
                quote = None
            i += 1
            continue
        if rule.startswith("{{", i):
            curly += 1
            buf.append("{{")
            i += 2
            continue
        if rule.startswith("}}", i) and curly:
            curly -= 1
            buf.append("}}")
            i += 2
            continue
        ch = rule[i]
        if curly == 0 and ch in ("'", '"'):
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if curly == 0 and ch == "(":
            paren += 1
        elif curly == 0 and ch == ")" and paren:
            paren -= 1
        elif curly == 0 and ch == "[":
            brack += 1
        elif curly == 0 and ch == "]" and brack:
            brack -= 1
        if curly == 0 and paren == 0 and brack == 0 and rule.startswith(sep, i):
            parts.append("".join(buf))
            buf = []
            i += len(sep)
            continue
        buf.append(ch)
        i += 1
    parts.append("".join(buf))
    return parts


def _string_concat(expr):
    """Translate a JS expression that is only quoted strings joined by +."""
    expr = expr.strip()
    if not expr or expr[0] not in ("'", '"'):
        return None
    parts = []
    i = 0
    n = len(expr)
    expect_string = True
    while i < n:
        while i < n and expr[i] in " \t\r\n":
            i += 1
        if i >= n:
            break
        if not expect_string:
            if expr[i] != "+":
                return None
            i += 1
            expect_string = True
            continue
        if expr[i] not in ("'", '"'):
            return None
        quote = expr[i]
        i += 1
        buf = []
        closed = False
        while i < n:
            c = expr[i]
            if c == "\\":
                if i + 1 >= n:
                    return None
                nxt = expr[i + 1]
                buf.append({"n": "\n", "r": "\r", "t": "\t", "\\": "\\", "'": "'", '"': '"'}.get(nxt, nxt))
                i += 2
                continue
            if c == quote:
                i += 1
                closed = True
                break
            buf.append(c)
            i += 1
        if not closed:
            return None
        parts.append("".join(buf))
        expect_string = False
    if expect_string or not parts:
        return None
    return "".join(parts)


def _js_forbidden(expr):
    body = _split_top(expr, "##")[0]
    stripped = re.sub(r":contains\([^)]*\)|:matchesOwn\([^)]*\)|:matches\([^)]*\)", "", body)
    markers = (
        "(", "?", "java.", "<js>", "</js>", "@js:", "function ", "cookie.",
        "source.", "encodeURI", ".replace(", ".match(", "=>",
    )
    if any(item in stripped for item in markers):
        return True
    if re.search(r"[*/]|[+\-]\s|\s[+\-]", stripped):
        return True
    return False


def _template_exprs(text):
    exprs = []
    i = 0
    while True:
        start = text.find("{{", i)
        if start < 0:
            break
        depth = 1
        j = start + 2
        while j < len(text) and depth:
            if text.startswith("{{", j):
                depth += 1
                j += 2
                continue
            if text.startswith("}}", j):
                depth -= 1
                if depth == 0:
                    exprs.append(text[start + 2:j])
                    i = j + 2
                    break
                j += 2
                continue
            j += 1
        else:
            break
    return exprs


def _is_mode(step):
    return step.lower() in _MODES


def _is_attr(step):
    name = step[4:] if step.startswith("abs:") else step
    if name.lower() in _ATTRS or name.startswith("data-"):
        return True
    return False


def _attr_name(step):
    return step[4:] if step.startswith("abs:") else step


def _split_steps(rule):
    prefix = ""
    for name in ("@css:", "@json:", "@xpath:", "@XPath:"):
        if rule.startswith(name):
            prefix = name
            rule = rule[len(name):]
            break
    parts = [part for part in rule.split("@") if part != ""]
    if prefix:
        if parts:
            parts[0] = prefix + parts[0]
        else:
            parts = [prefix]
    return parts


def _parse_step(step):
    raw = step
    if raw.startswith("@css:"):
        raw = raw[5:]
    slice_spec = None
    match = re.search(r"\[(-?\d*):(-?\d*)\]$", raw)
    if match and "=" not in match.group(0) and "!" not in match.group(0):
        slice_spec = (match.group(1), match.group(2))
        raw = raw[:match.start()]
    bracket = None
    match = re.search(r"\[(!?)([0-9,\-]+)\]$", raw)
    if match and re.fullmatch(r"[0-9,\-]+", match.group(2) or "0"):
        nums = [int(item) for item in match.group(2).split(",") if item != ""]
        bracket = (match.group(1) == "!", nums)
        raw = raw[:match.start()]
    exclude = False
    index = None
    match = re.search(r"!(-?\d+(?::-?\d+)*)$", raw)
    if match:
        exclude = True
        index = match.group(1)
        raw = raw[:match.start()]
    else:
        match = re.search(r"\.(-?\d+(?::-?\d+)*)$", raw)
        if match:
            index = match.group(1)
            raw = raw[:match.start()]
    return raw, index, bracket, exclude, slice_spec


def _expand_eq(body):
    if ":eq(" not in body:
        return [(body, None)]
    parts = []
    rest = body
    while ":eq(" in rest:
        match = re.search(r":eq\((-?\d+)\)", rest)
        if not match:
            break
        parts.append((rest[:match.start()].strip(), match.group(1)))
        rest = rest[match.end():].strip()
    if rest:
        parts.append((rest, None))
    return parts or [(body, None)]


def _apply_index(elements, index, bracket):
    if bracket:
        exclude, nums = bracket
        if exclude:
            banned = set()
            n = len(elements)
            for num in nums:
                banned.add(num if num >= 0 else n + num)
            elements = [el for i, el in enumerate(elements) if i not in banned]
        else:
            picked = []
            n = len(elements)
            for num in nums:
                pos = num if num >= 0 else n + num
                if 0 <= pos < n:
                    picked.append(elements[pos])
            elements = picked
    if index is None:
        return elements
    n = len(elements)

    def norm(value):
        return value if value >= 0 else n + value

    pieces = [int(item) for item in index.split(":") if item != ""]
    if len(pieces) == 1:
        pos = norm(pieces[0])
        return [elements[pos]] if 0 <= pos < n else []
    if len(pieces) == 2:
        start, end = norm(pieces[0]), norm(pieces[1])
        if start > end:
            start, end = end, start
        return [elements[i] for i in range(start, end + 1) if 0 <= i < n]
    out = []
    for piece in pieces:
        pos = norm(piece)
        if 0 <= pos < n:
            out.append(elements[pos])
    return out


def _select_text(node, text):
    hits = []
    elements = []
    if getattr(node, "name", None):
        elements.append(node)
    elements.extend(node.find_all(True))
    for el in elements:
        own = "".join(str(node_text) for node_text in el.find_all(string=True, recursive=False))
        if text and text in own:
            hits.append(el)
    return hits


def _quote_attr_values(css):
    def repl(match):
        name, op, value = match.group(1), match.group(2), match.group(3)
        if op == "~=":
            return match.group(0)
        if any(ch in value for ch in ":/ \t"):
            value = value.replace("\\", "\\\\").replace('"', '\\"')
            return '[%s%s"%s"]' % (name, op, value)
        return match.group(0)
    return re.sub(r"\[([\w:-]+)([*^$~|]?=)([^\]\"']+)\]", repl, css)


def _css_contains(css):
    def repl(match):
        inner = match.group(1).replace("\\", "\\\\").replace('"', '\\"')
        return ':-soup-contains("%s")' % inner
    return re.sub(r":contains\((.*?)\)", repl, css)


def _select_css(node, body):
    if body == "children":
        return [child for child in getattr(node, "children", []) if getattr(child, "name", None)]
    if body.startswith("text."):
        return _select_text(node, body[5:])
    regex_attr = re.fullmatch(r"(.*)\[([\w:-]+)~=([^\]]+)\]", body.strip())
    if regex_attr:
        prefix, attr, pattern = regex_attr.group(1).strip(), regex_attr.group(2), regex_attr.group(3)
        try:
            compiled = re.compile(pattern)
        except re.error as exc:
            raise Unsupported("attr regex %s" % exc)
        base = _select_css(node, prefix) if prefix else node.find_all(True)
        return [el for el in base if compiled.search(el.get(attr) or "")]
    if body.startswith("class."):
        classes = [item for item in body[6:].split() if item]
        css = "." + ".".join(classes) if classes else ""
    elif body.startswith("tag."):
        css = body[4:]
    elif body.startswith("id."):
        css = "#" + body[3:]
    else:
        css = body
    css = _quote_attr_values(_css_contains(css))
    if not css:
        return [node]
    try:
        return node.select(css)
    except Exception as exc:
        raise Unsupported("selector %s: %s" % (css, exc))


def _finish_index(current, index, bracket, exclude, slice_spec):
    if slice_spec is not None:
        start_text, end_text = slice_spec
        start = int(start_text) if start_text != "" else None
        end = int(end_text) if end_text != "" else None
        if end is not None:
            end = end + 1 if end >= 0 else end
        current = current[start:end]
    if exclude and index is not None:
        length = len(current)
        banned = set()
        for piece in index.split(":"):
            if piece == "":
                continue
            number = int(piece)
            banned.add(number if number >= 0 else length + number)
        return [el for pos, el in enumerate(current) if pos not in banned]
    return _apply_index(current, index, bracket)


def _step_nodes(nodes, step):
    body, index, bracket, exclude, slice_spec = _parse_step(step)
    out = []
    for node in nodes:
        current = [node]
        for sel, eq_index in _expand_eq(body):
            nxt = []
            for item in current:
                found = _select_css(item, sel) if sel else [item]
                if eq_index is not None:
                    found = _apply_index(found, eq_index, None)
                nxt.extend(found)
            current = nxt
        out.extend(_finish_index(current, index, bracket, exclude, slice_spec))
    return out


def _element_text(el):
    if not hasattr(el, "decode_contents"):
        return str(el).strip()
    raw = el.decode_contents()
    raw = re.sub(r"(?i)<br\s*/?>", "\n", raw)
    raw = re.sub(r"(?i)</p\s*>", "\n", raw)
    raw = re.sub(r"(?i)<script\b[^>]*>.*?</script>", "", raw, flags=re.S)
    raw = re.sub(r"(?i)<style\b[^>]*>.*?</style>", "", raw, flags=re.S)
    raw = re.sub(r"<[^>]+>", "", raw)
    import html as html_lib
    raw = html_lib.unescape(raw).replace("\xa0", " ")
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in raw.splitlines()]
    return "\n".join(line for line in lines if line)


def _nodes_text(nodes, mode, attr):
    values = []
    for node in nodes:
        if attr:
            if hasattr(node, "get") and node.get(attr):
                values.append(node.get(attr))
            continue
        if mode == "html":
            values.append(node.decode_contents() if hasattr(node, "decode_contents") else str(node))
        elif mode == "all":
            values.append(str(node))
        elif mode == "owntext":
            if hasattr(node, "find_all"):
                own = "".join(str(text) for text in node.find_all(string=True, recursive=False))
                values.append(own.strip())
        elif mode == "textnodes":
            parts = []
            if hasattr(node, "find_all"):
                for text in node.find_all(string=True):
                    if getattr(getattr(text, "parent", None), "name", None) in ("script", "style"):
                        continue
                    piece = str(text).strip()
                    if piece:
                        parts.append(piece)
            values.append("\n".join(parts))
        else:
            values.append(_element_text(node))
    return [value for value in values if value and str(value).strip()]


def _apply_selector(nodes, rule):
    steps = _split_steps(rule)
    current = list(nodes)
    mode = None
    attr = None
    for index, step in enumerate(steps):
        if _is_mode(step):
            mode = step.lower()
            continue
        if _is_attr(step) and index == len(steps) - 1:
            attr = _attr_name(step)
            continue
        current = _step_nodes(current, step)
    return current, mode, attr


def _json_tokens(path):
    raw = path.strip()
    if raw.startswith("@json:"):
        raw = raw[6:].strip()
    if raw.startswith("$"):
        raw = raw[1:]
    if raw.startswith("."):
        raw = raw[1:]
    tokens = []
    while raw:
        if raw.startswith(".."):
            raw = raw[2:]
            match = re.match(r"([A-Za-z0-9_\u0080-\uffff]+)", raw)
            if not match:
                raise Unsupported("json path %s" % path)
            tokens.append(("desc", match.group(1)))
            raw = raw[match.end():]
            continue
        if raw.startswith("."):
            raw = raw[1:]
            continue
        match = re.match(r"([A-Za-z0-9_\u0080-\uffff]+)", raw)
        if match:
            tokens.append(("key", match.group(1)))
            raw = raw[match.end():]
            continue
        match = re.match(r"\[(\*|\d+)\]", raw)
        if match:
            tokens.append(("index", match.group(1)))
            raw = raw[match.end():]
            continue
        raise Unsupported("json path %s" % path)
    return tokens


def _find_key(data, key):
    found = []
    if isinstance(data, dict):
        for name, value in data.items():
            if name == key:
                found.append(value)
            found.extend(_find_key(value, key))
    elif isinstance(data, list):
        for item in data:
            found.extend(_find_key(item, key))
    return found


def _json_values(data, path):
    path = path.strip()
    if re.fullmatch(r"[A-Za-z0-9_\u0080-\uffff]+", path):
        if isinstance(data, dict):
            return [data[path]] if path in data else []
        if isinstance(data, list):
            return [item[path] for item in data if isinstance(item, dict) and path in item]
        return []
    values = [data]
    for kind, val in _json_tokens(path):
        nxt = []
        for item in values:
            if kind == "key":
                if isinstance(item, dict) and val in item:
                    nxt.append(item[val])
                elif isinstance(item, list):
                    for child in item:
                        if isinstance(child, dict) and val in child:
                            nxt.append(child[val])
            elif kind == "index":
                if val == "*":
                    if isinstance(item, list):
                        nxt.extend(item)
                    elif isinstance(item, dict):
                        nxt.extend(item.values())
                elif isinstance(item, list):
                    pos = int(val)
                    if 0 <= pos < len(item):
                        nxt.append(item[pos])
            elif kind == "desc":
                nxt.extend(_find_key(item, val))
        values = nxt
    return values


def _json_string(values):
    parts = []
    for value in values:
        if value is None:
            continue
        if isinstance(value, (dict, list)):
            continue
        text = str(value).strip()
        if text:
            parts.append(text)
    return "\n".join(parts)


def _is_json_rule(rule):
    raw = rule.strip()
    if raw.startswith("@json:") or raw.startswith("$"):
        return True
    if re.fullmatch(r"[A-Za-z0-9_\u0080-\uffff]+(?:(?:\.[A-Za-z0-9_\u0080-\uffff]+)|(?:\[(?:\*|\d+)\]))+", raw):
        return True
    return False


def _is_xpath_rule(rule):
    raw = rule.strip()
    return raw.startswith("@XPath:") or raw.startswith("@xpath:") or raw.startswith("//") or raw.startswith(".//")


def _split_xpath_and(pred):
    parts = []
    buf = []
    quote = None
    i = 0
    while i < len(pred):
        ch = pred[i]
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if pred.startswith(" and ", i):
            parts.append("".join(buf).strip())
            buf = []
            i += 5
            continue
        buf.append(ch)
        i += 1
    if buf or parts:
        parts.append("".join(buf).strip())
    return [part for part in parts if part]


def _attr_value(el, name):
    value = el.get(name) or ""
    if isinstance(value, list):
        return " ".join(value)
    return str(value)


def _pred_ok(el, pred):
    pred = pred.strip()
    if re.fullmatch(r"\d+", pred):
        return True
    match = re.fullmatch(r"@([\w:-]+)\s*=\s*(?:'([^']*)'|\"([^\"]*)\")", pred)
    if match:
        expected = match.group(2) if match.group(2) is not None else match.group(3)
        return _attr_value(el, match.group(1)) == expected
    match = re.fullmatch(r"contains\(\s*@([\w:-]+)\s*,\s*(?:'([^']*)'|\"([^\"]*)\")\s*\)", pred)
    if match:
        expected = match.group(2) if match.group(2) is not None else match.group(3)
        return expected in _attr_value(el, match.group(1))
    match = re.fullmatch(r"contains\(\s*text\(\)\s*,\s*(?:'([^']*)'|\"([^\"]*)\")\s*\)", pred)
    if match:
        expected = match.group(1) if match.group(1) is not None else match.group(2)
        own = "".join(str(text) for text in el.find_all(string=True, recursive=False))
        return expected in own or expected in el.get_text()
    if re.fullmatch(r"[\w:-]+", pred):
        return el.find(pred, recursive=False) is not None
    raise Unsupported("xpath predicate %s" % pred)


def _parse_xpath(expr):
    raw = expr.strip()
    for prefix in ("@XPath:", "@xpath:"):
        if raw.startswith(prefix):
            raw = raw[len(prefix):].strip()
    grouped = re.fullmatch(r"\((.+)\)\[(\d+)\]", raw)
    if grouped:
        return ("group", grouped.group(1), int(grouped.group(2)))
    if raw.startswith("."):
        raw = raw[1:]
    if "::" in raw:
        raise Unsupported("xpath axis")
    steps = []
    i = 0
    while i < len(raw):
        if raw.startswith("//text()", i) or raw.startswith("/text()", i):
            steps.append(("text",))
            i += 8 if raw.startswith("//text()", i) else 7
            continue
        if raw.startswith("//html()", i) or raw.startswith("/html()", i):
            steps.append(("html",))
            i += 8 if raw.startswith("//html()", i) else 7
            continue
        if raw.startswith("//@", i) or raw.startswith("/@", i) or raw.startswith("@", i):
            if raw.startswith("//@", i):
                i += 3
            elif raw.startswith("/@", i):
                i += 2
            else:
                i += 1
            match = re.match(r"([\w:-]+)", raw[i:])
            if not match:
                raise Unsupported("xpath attr")
            steps.append(("attr", match.group(1)))
            i += match.end()
            continue
        if raw.startswith("//", i):
            axis = "desc"
            i += 2
        elif raw.startswith("/", i):
            axis = "child"
            i += 1
        else:
            raise Unsupported("xpath %s" % raw[i:i + 24])
        match = re.match(r"(\*|[\w:-]+)", raw[i:])
        if not match:
            raise Unsupported("xpath %s" % raw[i:i + 24])
        name = match.group(1)
        i += match.end()
        preds = []
        while i < len(raw) and raw[i] == "[":
            end = raw.find("]", i)
            if end < 0:
                raise Unsupported("xpath bracket")
            preds.append(raw[i + 1:end])
            i = end + 1
        steps.append(("node", axis, name, preds))
    if not steps:
        raise Unsupported("empty xpath")
    return ("steps", steps)


def _xpath_step(node, axis, name, preds):
    if axis == "desc":
        found = node.find_all(True)
    elif axis == "child":
        found = [child for child in node.find_all(recursive=False) if getattr(child, "name", None)]
    else:
        raise Unsupported("xpath axis")
    if name != "*":
        found = [el for el in found if (el.name or "").lower() == name.lower()]
    position = None
    filters = []
    for pred in preds:
        pieces = _split_xpath_and(pred)
        numbers = [piece for piece in pieces if re.fullmatch(r"\d+", piece.strip())]
        rest = [piece for piece in pieces if piece not in numbers]
        if len(numbers) > 1:
            raise Unsupported("xpath position")
        if numbers:
            position = int(numbers[0])
        filters.extend(rest)
    matched = []
    for el in found:
        if all(_pred_ok(el, pred) for pred in filters):
            matched.append(el)
    if position is not None:
        if 1 <= position <= len(matched):
            return [matched[position - 1]]
        return []
    return matched


def _xpath_query(node, expr):
    parsed = _parse_xpath(expr)
    if parsed[0] == "group":
        nodes = _xpath_query(node, parsed[1])
        nodes = [item for item in nodes if hasattr(item, "find_all")]
        pos = parsed[2]
        return [nodes[pos - 1]] if 1 <= pos <= len(nodes) else []
    current = [node]
    for step in parsed[1]:
        if step[0] == "node":
            nxt = []
            for item in current:
                if hasattr(item, "find_all"):
                    nxt.extend(_xpath_step(item, step[1], step[2], step[3]))
            current = nxt
        elif step[0] == "text":
            texts = []
            for item in current:
                if not hasattr(item, "find_all"):
                    continue
                own = [str(text).strip() for text in item.find_all(string=True, recursive=False)]
                own = [text for text in own if text]
                texts.extend(own or ([item.get_text(" ", strip=True)] if item.get_text(strip=True) else []))
            return texts
        elif step[0] == "html":
            return [item.decode_contents() if hasattr(item, "decode_contents") else str(item) for item in current if hasattr(item, "find_all")]
        elif step[0] == "attr":
            return [item.get(step[1]) for item in current if hasattr(item, "get") and item.get(step[1])]
    return current


def _compile_regex(pattern):
    pattern = pattern.replace("{{chapter.title}}", "\x00").replace("{{title}}", "\x00")
    return re.compile(pattern, re.M)


def _apply_regex(text, pattern, repl):
    if text is None:
        text = ""
    compiled = _compile_regex(pattern)
    repl = re.sub(r"\$(\d+)", r"\\\1", repl or "")
    return compiled.sub(repl, text)


def _regex_pairs(parts):
    text_out = None
    return parts


def _apply_pairs(text, parts):
    index = 0
    while index < len(parts):
        pattern = parts[index]
        repl = parts[index + 1] if index + 1 < len(parts) else ""
        text = _apply_regex(text, pattern, repl if index + 1 < len(parts) else "")
        index += 2 if index + 1 < len(parts) else 1
    return text


def _join(values):
    return "\n".join(value.strip() for value in values if value and str(value).strip())


def eval_rule(rule, node, root, ctx):
    if rule is None:
        return ""
    rule = str(rule).strip()
    if not rule:
        return ""
    if any(marker in rule for marker in ("<js>", "</js>", "@js:", "java.")):
        raise Unsupported("Legado JS")
    if rule.startswith("@put:") or rule.startswith("@get:"):
        return _eval_atomic(rule, node, root, ctx)
    alts = _split_top(rule, "||")
    if len(alts) > 1:
        for alt in alts:
            value = eval_rule(alt.strip(), node, root, ctx)
            if value and str(value).strip():
                return value
        return ""
    parts = _split_top(rule, "&&")
    if len(parts) > 1:
        values = []
        for part in parts:
            value = eval_rule(part.strip(), node, root, ctx)
            if value and str(value).strip():
                values.append(str(value).strip())
        return "\n".join(values)
    pieces = _split_top(rule, "##")
    if len(pieces) > 1:
        body = pieces[0]
        text = eval_rule(body, node, root, ctx) if body.strip() else _element_text(node)
        return _apply_pairs(text or "", pieces[1:])
    return _eval_atomic(rule, node, root, ctx)


def _eval_atomic(rule, node, root, ctx):
    rule = rule.strip()
    if rule.startswith("@@"):
        return _eval_atomic(rule[2:], root, root, ctx)
    if rule.startswith("@") and not rule.startswith(("@css:", "@json:", "@xpath:", "@XPath:", "@put:", "@get:")):
        return _eval_atomic(rule[1:], node, root, ctx)
    if rule.startswith("@put:"):
        match = re.match(r"@put:\s*\{(.*)\}\s*$", rule, re.S)
        if not match:
            raise Unsupported("@put syntax")
        pairs = re.findall(r'([A-Za-z_][A-Za-z0-9_]*)\s*:\s*"((?:\\.|[^"\\])*)"', match.group(1), re.S)
        if not pairs:
            raise Unsupported("@put has no entries")
        for key, value in pairs:
            ctx.setdefault("vars", {})[key] = eval_rule(value, node, root, ctx)
        return ""
    if rule.startswith("@get:"):
        match = re.match(r"@get:\s*\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\s*$", rule)
        if not match:
            raise Unsupported("@get syntax")
        return str(ctx.get("vars", {}).get(match.group(1), "") or "")
    if "{{" in rule:
        return _eval_template(rule, node, root, ctx)
    single = _single_brace(rule, node, ctx)
    if single is not None:
        return single
    if _is_xpath_rule(rule):
        base_node = node if hasattr(node, "find_all") else root
        return _join([str(item) for item in _xpath_query(base_node, rule) if not hasattr(item, "find_all")])
    if isinstance(node, (dict, list)) and (_is_json_rule(rule) or re.fullmatch(r"[A-Za-z0-9_\u0080-\uffff]+", rule.strip())):
        return _json_string(_json_values(node, rule[6:].strip() if rule.startswith("@json:") else rule))
    if _is_json_rule(rule):
        return ""
    host = node if hasattr(node, "select") else root
    nodes, mode, attr = _apply_selector([host], rule)
    return _join(_nodes_text(nodes, mode or "text", attr))


def _single_brace(rule, node, ctx):
    if "{{" in rule or not re.search(r"(?<!\{)\{", rule):
        return None

    def repl(match):
        inner = match.group(1).strip()
        if inner == "baseUrl":
            return str(ctx.get("base", "") or "")
        if inner in ("key", "page", "title"):
            return str(ctx.get(inner, "") or "")
        if inner.startswith("$"):
            data = node if isinstance(node, (dict, list)) else {}
            return _json_string(_json_values(data, inner))
        raise Unsupported("brace {%s}" % inner[:60])

    return re.sub(r"(?<!\{)\{([^{}]+)\}(?!\})", repl, rule)


def _eval_template(rule, node, root, ctx):
    pieces = _split_top(rule, "##")
    body = pieces[0]
    out = []
    cursor = 0
    for expr in _template_exprs(body):
        start = body.find("{{" + expr + "}}", cursor)
        out.append(body[cursor:start])
        out.append(_eval_expr(expr, node, root, ctx))
        cursor = start + len(expr) + 4
    out.append(body[cursor:])
    text = "".join(out)
    if len(pieces) > 1:
        text = _apply_pairs(text, pieces[1:])
    return text


def _eval_expr(expr, node, root, ctx):
    expr = expr.strip()
    literal = _string_concat(expr)
    if literal is not None:
        return literal
    if expr in ("baseUrl", "key", "page", "title", "chapter.title"):
        if expr == "chapter.title":
            key = "title"
        elif expr == "baseUrl":
            key = "base"
        else:
            key = expr
        return str(ctx.get(key, "") or "")
    if _js_forbidden(expr):
        raise Unsupported("JS expression: %s" % expr[:80])
    return str(eval_rule(expr, node, root, ctx) or "")


def eval_list(rule, node, root, ctx):
    rule = str(rule or "").strip()
    reverse = False
    if rule.startswith("-") and not rule.startswith("--"):
        reverse = True
        rule = rule[1:].strip()
    if not rule:
        return []
    alts = _split_top(rule, "||")
    if len(alts) > 1:
        for alt in alts:
            found = eval_list(alt.strip(), node, root, ctx)
            if found:
                return list(reversed(found)) if reverse else found
        return []
    pieces = _split_top(rule, "##")
    rule = pieces[0].strip()
    if "{{" in rule:
        return []
    if _is_xpath_rule(rule):
        base_node = node if hasattr(node, "find_all") else root
        found = [item for item in _xpath_query(base_node, rule) if hasattr(item, "find_all")]
        return list(reversed(found)) if reverse else found
    if isinstance(node, (dict, list)) or _is_json_rule(rule):
        data = node if isinstance(node, (dict, list)) else None
        if data is None:
            return []
        path = rule[6:].strip() if rule.startswith("@json:") else rule
        values = _json_values(data, path)
        found = []
        for value in values:
            if isinstance(value, list):
                found.extend(item for item in value if isinstance(item, (dict, list)))
            elif isinstance(value, dict):
                found.append(value)
        return list(reversed(found)) if reverse else found
    host = root if hasattr(root, "select") else node
    nodes, mode, attr = _apply_selector([host], rule)
    if mode or attr:
        return []
    return list(reversed(nodes)) if reverse else nodes


def eval_multi(rule, node, root, ctx):
    rule = str(rule or "").strip()
    if not rule:
        return []
    if "{{" in rule or "##" in rule or rule.startswith("@get") or rule.startswith("@put"):
        value = eval_rule(rule, node, root, ctx)
        return [value] if value else []
    alts = _split_top(rule, "||")
    if len(alts) > 1:
        for alt in alts:
            found = eval_multi(alt.strip(), node, root, ctx)
            if any(str(item).strip() for item in found):
                return found
        return []
    if _is_xpath_rule(rule):
        base_node = node if hasattr(node, "find_all") else root
        values = _xpath_query(base_node, rule)
        if values and not hasattr(values[0], "find_all"):
            return [str(item) for item in values if str(item).strip()]
        return [_element_text(item) for item in values if hasattr(item, "find_all")]
    if isinstance(node, (dict, list)) and (_is_json_rule(rule) or re.fullmatch(r"[A-Za-z0-9_\u0080-\uffff]+", rule)):
        text = _json_string(_json_values(node, rule[6:].strip() if rule.startswith("@json:") else rule))
        return [text] if text else []
    host = node if hasattr(node, "select") else root
    nodes, mode, attr = _apply_selector([host], rule)
    if attr:
        return [node_item.get(attr) for node_item in nodes if hasattr(node_item, "get") and node_item.get(attr)]
    return _nodes_text(nodes, mode or "text", None)


def _apply_replace_field(text, field):
    if not field:
        return text
    for line in str(field).splitlines():
        line = line.strip()
        if not line:
            continue
        for chunk in _split_top(line, "||"):
            chunk = chunk.strip()
            if not chunk:
                continue
            if chunk.startswith("##"):
                text = _apply_pairs(text, _split_top(chunk, "##")[1:])
            elif "##" in chunk:
                text = _apply_pairs(text, _split_top(chunk, "##")[1:])
            else:
                text = _apply_regex(text, chunk, "")
    return text


def validate_rule(rule):
    if rule is None:
        return
    rule = str(rule).strip()
    if not rule:
        return
    if any(marker in rule for marker in ("<js>", "</js>", "@js:", "java.")):
        raise Unsupported("Legado JS")
    if rule.startswith("@put:"):
        match = re.match(r"@put:\s*\{(.*)\}\s*$", rule, re.S)
        if not match:
            raise Unsupported("@put syntax")
        pairs = re.findall(r'([A-Za-z_][A-Za-z0-9_]*)\s*:\s*"((?:\\.|[^"\\])*)"', match.group(1), re.S)
        if not pairs:
            raise Unsupported("@put has no entries")
        for _key, value in pairs:
            validate_rule(value)
        return
    if rule.startswith("@get:"):
        if not re.match(r"@get:\s*\{\s*[A-Za-z_][A-Za-z0-9_]*\s*\}\s*$", rule):
            raise Unsupported("@get syntax")
        return
    alts = _split_top(rule, "||")
    if len(alts) > 1:
        for alt in alts:
            validate_rule(alt.strip())
        return
    parts = _split_top(rule, "&&")
    if len(parts) > 1:
        for part in parts:
            validate_rule(part.strip())
        return
    pieces = _split_top(rule, "##")
    if len(pieces) > 1:
        if pieces[0].strip():
            validate_rule(pieces[0].strip())
        _apply_pairs("", pieces[1:])
        return
    _validate_atomic(rule)


def _validate_atomic(rule):
    if rule.startswith("@@"):
        return _validate_atomic(rule[2:])
    if rule.startswith("@") and not rule.startswith(("@css:", "@json:", "@xpath:", "@XPath:", "@put:", "@get:")):
        return _validate_atomic(rule[1:])
    if re.search(r"(?<!\{)\{", rule) and "{{" not in rule:
        for inner in re.findall(r"(?<!\{)\{([^{}]+)\}(?!\})", rule):
            inner = inner.strip()
            if inner in ("baseUrl", "key", "page", "title"):
                continue
            if inner.startswith("$"):
                _json_tokens(inner)
                continue
            raise Unsupported("brace {%s}" % inner[:60])
        return
    if "{{" in rule:
        for expr in _template_exprs(rule):
            if _string_concat(expr) is not None:
                continue
            if expr.strip() in ("baseUrl", "key", "page", "title", "chapter.title"):
                continue
            if _js_forbidden(expr):
                raise Unsupported("JS expression: %s" % expr.strip()[:80])
            validate_rule(expr.strip())
        pieces = _split_top(rule, "##")
        if len(pieces) > 1:
            _apply_pairs("", pieces[1:])
        return
    if _is_xpath_rule(rule):
        _parse_xpath(rule)
        return
    if _is_json_rule(rule) or re.fullmatch(r"[A-Za-z0-9_\u0080-\uffff]+", rule.strip()):
        if _is_json_rule(rule) or rule.strip().startswith("$") or rule.strip().startswith("@json:"):
            _json_tokens(rule[6:].strip() if rule.startswith("@json:") else rule)
        return
    steps = _split_steps(rule)
    import soupsieve
    for index, step in enumerate(steps):
        if _is_mode(step):
            continue
        if _is_attr(step) and index == len(steps) - 1:
            continue
        body, _index, _bracket, _exclude, _slice_spec = _parse_step(step)
        for sel, _eq in _expand_eq(body):
            _check_css(sel)


def _check_css(body):
    import soupsieve
    if not body or body.startswith("text.") or body == "children":
        return
    regex_attr = re.fullmatch(r"(.*)\[([\w:-]+)~=([^\]]+)\]", body.strip())
    if regex_attr:
        re.compile(regex_attr.group(3))
        if regex_attr.group(1).strip():
            _check_css(regex_attr.group(1).strip())
        return
    if body.startswith("class."):
        classes = [item for item in body[6:].split() if item]
        css = "." + ".".join(classes) if classes else ""
    elif body.startswith("tag."):
        css = body[4:]
    elif body.startswith("id."):
        css = "#" + body[3:]
    else:
        css = body
    css = _quote_attr_values(_css_contains(css))
    if not css:
        return
    try:
        soupsieve.compile(css)
    except Exception as exc:
        raise Unsupported("selector %s: %s" % (css, exc))


def _enc_key(key, charset):
    cs = charset or "utf-8"
    if cs.lower() in ("utf8", "utf-8"):
        cs = "utf-8"
    return quote_plus(key, encoding=cs)


def _apply_page_block(url, page):
    def repl(match):
        return match.group(1) if page > 1 else ""
    return re.sub(r"<,(.*?)>", repl, url, flags=re.S)


def resolve_search(source, query):
    spec = source["search"]
    charset = spec.get("charset")
    url = _apply_page_block(spec["url"], 1)
    url = url.replace("{{key}}", _enc_key(query, charset)).replace("{{page}}", "1")
    body = spec.get("body")
    if body:
        body = _apply_page_block(body, 1)
        body = body.replace("{{key}}", _enc_key(query, charset)).replace("{{page}}", "1")
    if not url.startswith("http://") and not url.startswith("https://"):
        base = source["site"]
        if url.startswith("/"):
            parsed = urlparse(base)
            base = "%s://%s/" % (parsed.scheme, parsed.netloc)
        elif not base.endswith("/"):
            base += "/"
        url = urljoin(base, url)
    return url, body


def _parse_doc(text):
    stripped = text.lstrip()
    if stripped.startswith("{") or stripped.startswith("["):
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            pass
    return BeautifulSoup(text, "html.parser")


def fetch(url, source, method="GET", body=None, charset=None, extra_headers=None):
    headers = {}
    headers.update(source.get("headers") or {})
    if extra_headers:
        headers.update({str(key): str(value) for key, value in extra_headers.items()})
    if not any(key.lower() == "user-agent" for key in headers):
        headers["User-Agent"] = DEFAULT_UA
    method = (method or "GET").upper()
    data = None
    if method == "POST":
        cs = charset or "utf-8"
        if cs.lower() == "utf8":
            cs = "utf-8"
        data = (body or "").encode(cs)
        headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
    kwargs = {"headers": headers, "timeout": (8, 20), "allow_redirects": True}
    try:
        response = requests.request(method, url, data=data, **kwargs)
    except requests.exceptions.SSLError:
        if not source.get("insecure"):
            raise
        import urllib3
        urllib3.disable_warnings()
        response = requests.request(method, url, data=data, verify=False, **kwargs)
    response.raise_for_status()
    if charset:
        response.encoding = charset
    elif not response.encoding or response.encoding.lower() == "iso-8859-1":
        response.encoding = response.apparent_encoding or "utf-8"
    text = response.text
    return _parse_doc(text), response.url, text


def abs_url(value, base):
    value = (value or "").strip()
    if not value or value.startswith("javascript:") or value.startswith("#"):
        return ""
    if value.startswith("data:"):
        return value
    if value.startswith("//"):
        return "https:" + value
    if value.startswith("http://") or value.startswith("https://"):
        return value
    return urljoin(base, value)


def _ctx(base, key="", page="1", title=""):
    return {"vars": {}, "base": base, "key": key, "page": page, "title": title}


def _apply_init(rule, node, root, ctx):
    if not rule or not str(rule).strip():
        return node
    rule = str(rule).strip()
    if rule.startswith("@put:"):
        eval_rule(rule, node, root, ctx)
        return node
    found = eval_list(rule, node, root, ctx)
    if found:
        return found[0]
    return node


def _chapters_from(source, doc, root, base, ctx):
    toc = source.get("ruleToc") or {}
    items = eval_list(toc.get("chapterList") or "", doc, root, ctx)
    chapters = []
    seen = set()
    for item in items:
        title = eval_rule(toc.get("chapterName") or "", item, root, ctx).strip()
        href = eval_rule(toc.get("chapterUrl") or "", item, root, ctx).strip()
        chapter_url = abs_url(href, base)
        if not chapter_url:
            continue
        if chapter_url in seen:
            continue
        seen.add(chapter_url)
        chapters.append({"title": title, "url": chapter_url})
    return chapters


def _collect_chapters(source, start, ctx):
    seen_pages = set()
    out = []
    seen_chapters = set()
    queue = [start]
    pages = 0
    while queue and pages < TOC_PAGE_LIMIT:
        page = queue.pop(0)
        if not page or page in seen_pages:
            continue
        seen_pages.add(page)
        pages += 1
        doc, final, _text = fetch(page, source)
        root = doc
        page_ctx = _ctx(final)
        page_ctx["vars"] = dict(ctx.get("vars") or {})
        for chapter in _chapters_from(source, doc, root, final, page_ctx):
            if chapter["url"] in seen_chapters:
                continue
            seen_chapters.add(chapter["url"])
            out.append(chapter)
        nxt_rule = (source.get("ruleToc") or {}).get("nextTocUrl")
        if not nxt_rule:
            break
        for nxt in eval_multi(nxt_rule, doc, root, page_ctx):
            absolute = abs_url(str(nxt), final)
            if absolute and absolute not in seen_pages:
                queue.append(absolute)
    return out


def run_search(source, query):
    url, body = resolve_search(source, query)
    spec = source["search"]
    doc, final, _text = fetch(
        url, source, spec.get("method") or "GET", body, spec.get("charset"), spec.get("headers")
    )
    ctx = _ctx(final, key=query, page="1")
    root = doc
    items = eval_list((source.get("ruleSearch") or {}).get("bookList") or "", doc, root, ctx)
    rules = source.get("ruleSearch") or {}
    results = []
    for item in items:
        item_ctx = _ctx(final, key=query, page="1")
        title = eval_rule(rules.get("name") or "", item, root, item_ctx).strip()
        book_url = abs_url(eval_rule(rules.get("bookUrl") or "", item, root, item_ctx), final)
        if not book_url:
            continue
        author = eval_rule(rules.get("author") or "", item, root, item_ctx).strip()
        cover = abs_url(eval_rule(rules.get("coverUrl") or "", item, root, item_ctx), final)
        latest = eval_rule(rules.get("lastChapter") or "", item, root, item_ctx).strip()
        results.append({
            "bookTitle": title,
            "bookUrl": book_url,
            "bookAuthor": author,
            "bookCover": cover or None,
            "latestChapter": latest or None,
        })
    return results


def run_details(source, url, last_chapter_url=""):
    doc, final, _text = fetch(url, source)
    ctx = _ctx(final)
    root = doc
    info = source.get("ruleBookInfo") or {}
    node = _apply_init(info.get("init") or "", doc, root, ctx)
    title = eval_rule(info.get("name") or "", node, root, ctx).strip()
    author = eval_rule(info.get("author") or "", node, root, ctx).strip()
    cover = abs_url(eval_rule(info.get("coverUrl") or "", node, root, ctx), final)
    intro = eval_rule(info.get("intro") or "", node, root, ctx).strip()
    toc_url = ""
    if info.get("tocUrl"):
        toc_url = abs_url(eval_rule(info.get("tocUrl") or "", node, root, ctx), final)
    if toc_url:
        chapters = _collect_chapters(source, toc_url, ctx)
    else:
        chapters = _chapters_from(source, doc, root, final, ctx)
    return {
        "url": url,
        "title": title,
        "author": author,
        "cover": cover or None,
        "intro": intro,
        "chapterList": chapters,
    }


def run_content(source, url):
    rule = source.get("ruleContent") or {}
    seen = set()
    parts = []
    current = url
    pages = 0
    title = ""
    while current and current not in seen and pages < CONTENT_PAGE_LIMIT:
        seen.add(current)
        pages += 1
        doc, final, _text = fetch(current, source)
        root = doc
        ctx = _ctx(final, title=title)
        if rule.get("title"):
            title = eval_rule(rule.get("title") or "", doc, root, ctx).strip()
            ctx["title"] = title
        text = eval_rule(rule.get("content") or "", doc, root, ctx)
        if text and text.strip():
            parts.append(text.strip())
        nxt_rule = rule.get("nextContentUrl")
        current = ""
        if not nxt_rule:
            break
        for nxt in eval_multi(nxt_rule, doc, root, ctx):
            absolute = abs_url(str(nxt), final)
            if absolute and absolute not in seen:
                current = absolute
                break
    body = "\n".join(parts)
    body = _apply_replace_field(body, rule.get("replaceRegex") or "")
    return body.strip()

_SOURCE = json.loads(r"""{
  "name": "殓师灵异（优）",
  "group": "小说 书源",
  "bookSourceUrl": "http://www.rulianshi.org/#pb1101",
  "site": "http://www.rulianshi.org/",
  "headers": {},
  "insecure": false,
  "search": {
    "url": "http://www.rulianshi.org/s.php?ie=utf-8&q={{key}}",
    "method": "GET",
    "body": null,
    "charset": null,
    "headers": {}
  },
  "ruleSearch": {
    "author": "class.author@text",
    "bookList": "class.bookbox",
    "bookUrl": "h4@a@href",
    "coverUrl": "img@src",
    "lastChapter": "class.update@a@text",
    "name": "h4@a@text"
  },
  "ruleBookInfo": {
    "author": "class.info@class.small@tag.span.0@text",
    "coverUrl": "class.cover@img@src",
    "intro": "class.info@ownText",
    "kind": "class.info@class.small@tag.span.4@text&&\nclass.info@class.small@tag.span.1@text&&\nclass.info@class.small@tag.span.2@text##分类：|状态：|更新时间：",
    "lastChapter": "class.info@class.small@tag.span.5@a@text",
    "name": "class.info@h2@text",
    "wordCount": "class.info@class.small@tag.span.3@text##字数："
  },
  "ruleToc": {
    "chapterList": "class.listmain@dd",
    "chapterName": "a@text",
    "chapterUrl": "a@href"
  },
  "ruleContent": {
    "content": "id.content@html##http.*html|请记住本书首发.*org",
    "nextContentUrl": "text.下一页@href"
  }
}""")


def getSiteUrl():
    """书源网址，供链接导入和搜索结果归属。"""
    return _SOURCE["site"]


def search(query):
    """按 ruleSearch 搜索，只请求第一页。"""
    return run_search(_SOURCE, query)


def getDetails(url, lastChapterUrl):
    """按 ruleBookInfo 和 ruleToc 取详情与升序目录。lastChapterUrl 不截断目录。"""
    return run_details(_SOURCE, url, lastChapterUrl or "")


def getChapterContent(url):
    """按 ruleContent 取正文。有 nextContentUrl 时按规则继续取后续页。"""
    return run_content(_SOURCE, url)
