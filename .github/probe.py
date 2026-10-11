import json, os, urllib.request
os.makedirs("out", exist_ok=True)
log = open("out/summary.txt", "w")
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "probe", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(r, timeout=30) as x: return x.read().decode()
for u in ["https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases?per_page=5",
          "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/tags?per_page=8",
          "https://jitpack.io/api/builds/com.github.TeamNewPipe/NewPipeExtractor/latestOk",
          "https://api.github.com/repos/TeamNewPipe/NewPipe/releases?per_page=2"]:
    try:
        t = get(u)
        try:
            j = json.loads(t)
            if isinstance(j, list): t = "\n".join(f"{x.get('tag_name') or x.get('name')} {x.get('published_at','')}" for x in j)
        except Exception: pass
        log.write(u + "\n" + t[:1500] + "\n\n")
    except Exception as e:
        log.write(u + " ERR " + str(e) + "\n")
log.close()
