"""Checks, from a server outside Russia, that every video, radio and YouTube source Couchy uses still answers in the format it expects."""
import base64, gzip, json, re, urllib.parse, urllib.request, zlib, traceback, sys

UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
out = open("health.txt", "w")


def say(line):
    print(line)
    out.write(line + "\n")
    out.flush()


def get(url, data=None, headers=None, limit=None):
    h = {"User-Agent": UA, "Accept-Encoding": "gzip, deflate"}
    h.update(headers or {})
    body = urllib.parse.urlencode(data).encode() if isinstance(data, dict) else data
    req = urllib.request.Request(url, data=body, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        raw = r.read(limit) if limit else r.read()
        enc = r.headers.get("Content-Encoding", "")
        if not limit and (enc == "gzip" or raw[:2] == b"\x1f\x8b"):
            raw = gzip.decompress(raw)
        elif not limit and enc == "deflate":
            raw = zlib.decompress(raw)
        return r.status, r.geturl(), raw.decode("utf-8", "replace"), r.headers


def check(name, fn):
    try:
        say(f"{name}: OK {fn()}")
    except Exception as e:
        say(f"{name}: FAIL {e!r}")
        say("    " + traceback.format_exc().strip().splitlines()[-1])


def rezka_decode(s):
    s = s.replace("\\/", "/").replace("#h", "")
    for _ in range(3):
        s = re.sub(r"//_//[A-Za-z0-9+/=]*?(?=[A-Za-z0-9+/=]*$|//_//)", "", s)
    return s


def rezka():
    base = "https://rezka.ag"
    _, _, html, _ = get(base + "/search/?do=search&subaction=search&q=" + urllib.parse.quote("гарри поттер"))
    links = re.findall(r'class="b-content__inline_item-link">\s*<a href="([^"]+)"', html)
    if not links:
        return "search returned no links; page starts: " + html[:200].replace("\n", " ")
    film = next((l for l in links if "/films/" in l), links[0])
    _, _, page, _ = get(film)
    m = re.search(r"initCDNMoviesEvents\((\d+),\s*(\d+)", page)
    if not m:
        return f"film page has no initCDNMoviesEvents ({film})"
    _, _, cdn, _ = get(base + "/ajax/get_cdn_series/", {"id": m.group(1), "translator_id": m.group(2), "action": "get_movie"},
                       {"X-Requested-With": "XMLHttpRequest", "Referer": film})
    j = json.loads(cdn)
    if not j.get("success"):
        return "get_cdn_series: " + cdn[:200]
    url = j.get("url") or ""
    # The encoded stream list: report its shape, the app decodes it.
    return f"film={film} success url_len={len(url)} starts={url[:12]!r}"


def filmix():
    res = []
    for api in ["http://filmixapp.cyou/api/v2/", "http://filmixapp.vip/api/v2/"]:
        try:
            dev = "user_dev_apk=2.0.1&user_dev_id=abcdef0123456789&user_dev_name=Couchy&user_dev_os=11&user_dev_vendor=Couchy&user_dev_token="
            code, final, text, _ = get(api + "search?story=" + urllib.parse.quote("матрица") + "&" + dev)
            t = text.strip()
            items = json.loads(t) if t.startswith("[") else json.loads(t).get("items", [])
            first = items[0] if items else {}
            post = ""
            if first.get("id"):
                _, _, p, _ = get(api + f"post/{first['id']}?" + dev)
                pj = json.loads(p)
                links = pj.get("player_links", {})
                movie = links.get("movie") or []
                link = movie[0].get("link") if movie else None
                post = f" post movies={len(movie)} link={str(link)[:80]!r}"
                if link:
                    q = (movie[0].get("qualities") or [480])[0]
                    vurl = link.replace("%s", str(q)) if "%s" in link else link
                    try:
                        c2, f2, _, h2 = get(vurl, limit=2048)
                        post += f" video={c2} {h2.get('Content-Type')}"
                    except Exception as e:
                        post += f" video FAIL {e!r}"
            res.append(f"{api} {code} items={len(items)}{post}")
        except Exception as e:
            res.append(f"{api} FAIL {e!r}")
    return " | ".join(res)


def radio():
    res = []
    for host in ["de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "at1.api.radio-browser.info", "all.api.radio-browser.info"]:
        try:
            _, _, text, _ = get(f"https://{host}/json/stations/topclick/5?hidebroken=true")
            st = json.loads(text)
            res.append(f"{host}: {len(st)}")
        except Exception as e:
            res.append(f"{host}: FAIL {type(e).__name__} {e}")
    _, _, text, _ = get("https://all.api.radio-browser.info/json/stations/search?countrycode=RU&order=clickcount&reverse=true&hidebroken=true&limit=3")
    st = json.loads(text)
    for s in st[:2]:
        try:
            c, f, _, h = get(s["url_resolved"] or s["url"], limit=4096)
            res.append(f"stream {s['name']!r} {c} {h.get('Content-Type')}")
        except Exception as e:
            res.append(f"stream {s['name']!r} FAIL {e!r}")
    return " | ".join(res)


def sites():
    res = []
    for name, url in [("kinogo", "https://kinogo.so/"), ("lordfilm", "https://lordfilm.top/"), ("seasonvar", "http://seasonvar.ru/autocomplete.php?query=%D0%B4%D0%BE%D0%BA%D1%82%D0%BE%D1%80"),
                      ("rutube", "https://rutube.ru/api/search/video/?query=%D1%84%D0%B8%D0%BB%D1%8C%D0%BC"), ("cinemeta", "https://v3-cinemeta.strem.io/manifest.json"),
                      ("anilibria", "https://anilibria.top/api/v1/app/search/releases?query=naruto"), ("mux test", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")]:
        try:
            c, f, t, _ = get(url)
            res.append(f"{name}: {c} len={len(t)}")
        except Exception as e:
            res.append(f"{name}: FAIL {e!r}")
    return " | ".join(res)


check("HDrezka", rezka)
check("Filmix", filmix)
check("Radio", radio)
check("Sites", sites)
out.close()
