"""Fetches sample answers from search sources (multi-step where needed) into out/ for format checks."""
import gzip, json, os, re, urllib.parse, urllib.request, zlib, traceback

UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
os.makedirs("out", exist_ok=True)
log = open("out/summary.txt", "w")


def get(url, data=None, headers=None):
    h = {"User-Agent": UA, "Accept-Encoding": "gzip, deflate"}
    h.update(headers or {})
    body = urllib.parse.urlencode(data).encode() if isinstance(data, dict) else data
    req = urllib.request.Request(url, data=body, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        raw = r.read()
        enc = r.headers.get("Content-Encoding", "")
        if enc == "gzip" or raw[:2] == b"\x1f\x8b":
            raw = gzip.decompress(raw)
        elif enc == "deflate":
            raw = zlib.decompress(raw)
        return r.status, r.geturl(), raw.decode("utf-8", "replace")


def save(name, text):
    open(f"out/{name}.txt", "w").write(text[:80000])


def step(name, fn):
    try:
        result = fn()
        log.write(f"{name}: ok\n")
        return result
    except Exception as e:
        log.write(f"{name}: FAIL {e!r}\n")
        save(name + "_error", traceback.format_exc())


def rezka():
    base = "https://rezka.ag"
    code, url, html = get(base + "/search/?do=search&subaction=search&q=" + urllib.parse.quote("дюна"))
    save("rezka_search", html)
    _, _, ajax = get(base + "/engine/ajax/search.php", {"q": "дюна"}, {"X-Requested-With": "XMLHttpRequest"})
    save("rezka_ajax", ajax)
    links = re.findall(r'class="b-content__inline_item-link">\s*<a href="([^"]+)"', html)
    log.write(f"rezka links: {links[:5]}\n")
    film = next((l for l in links if "/films/" in l), links[0])
    _, _, page = get(film)
    save("rezka_film", page)
    serial = next((l for l in links if "/series/" in l or "/cartoons/" in l), None)
    if serial is None:
        _, _, h2 = get(base + "/search/?do=search&subaction=search&q=" + urllib.parse.quote("во все тяжкие"))
        serial = re.findall(r'class="b-content__inline_item-link">\s*<a href="([^"]+)"', h2)[0]
    _, _, spage = get(serial)
    save("rezka_series", spage)
    # Movie stream for the default translator.
    mid = re.search(r'initCDNMoviesEvents\((\d+),\s*(\d+)', page)
    if mid:
        _, _, cdn = get(base + "/ajax/get_cdn_series/", {"id": mid.group(1), "translator_id": mid.group(2), "action": "get_movie"},
                        {"X-Requested-With": "XMLHttpRequest", "Referer": film})
        save("rezka_cdn_movie", cdn)
    sid = re.search(r'initCDNSeriesEvents\((\d+),\s*(\d+)', spage)
    if sid:
        _, _, eps = get(base + "/ajax/get_cdn_series/", {"id": sid.group(1), "translator_id": sid.group(2), "action": "get_episodes"},
                        {"X-Requested-With": "XMLHttpRequest", "Referer": serial})
        save("rezka_cdn_episodes", eps)
        _, _, stream = get(base + "/ajax/get_cdn_series/", {"id": sid.group(1), "translator_id": sid.group(2), "season": "1", "episode": "1", "action": "get_stream"},
                           {"X-Requested-With": "XMLHttpRequest", "Referer": serial})
        save("rezka_cdn_stream", stream)


def jacred():
    q = urllib.parse.urlencode({"search": "Дюна: Часть вторая", "altname": "Dune: Part Two", "exact": "true", "year": "2024"})
    save("jacred_exact", get("https://jac.red/api/v1.0/torrents?" + q)[2])
    q = urllib.parse.urlencode({"search": "Во все тяжкие", "altname": "Breaking Bad", "exact": "true"})
    save("jacred_series", get("https://jac.red/api/v1.0/torrents?" + q)[2])


def torrentio():
    save("torrentio_ru", get("https://torrentio.strem.fun/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex,rutor,rutracker,comando,bludv,torrent9,ilcorsaronero,mejortorrent,wolfmax4k,cinecalidad,besttorrents%7Csort=seeders/stream/movie/tt15239678.json")[2])
    save("torrentio_series", get("https://torrentio.strem.fun/stream/series/tt0903747:1:1.json")[2])


def imdb():
    save("imdb_ru", get("https://v3.sg.media-imdb.com/suggestion/x/" + urllib.parse.quote("дюна") + ".json")[2])
    save("imdb_breaking", get("https://v3.sg.media-imdb.com/suggestion/b/breaking%20bad.json")[2])


def misc():
    save("rutube", get("https://rutube.ru/api/search/video/?query=" + urllib.parse.quote("ночной дозор фильм"))[2])
    save("apibay", get("https://apibay.org/q.php?q=dune&cat=200")[2])
    save("ytsmx", get("https://yts.lt/api/v2/list_movies.json?query_term=dune&limit=3")[2])
    save("wikidata", get("https://www.wikidata.org/w/api.php?action=wbsearchentities&search=" + urllib.parse.quote("дюна") + "&language=ru&uselang=ru&type=item&format=json&limit=5")[2])


for name, fn in [("rezka", rezka), ("jacred", jacred), ("torrentio", torrentio), ("imdb", imdb), ("misc", misc)]:
    step(name, fn)
log.close()
