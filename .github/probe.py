import os, urllib.request, json
os.makedirs("out", exist_ok=True)
r = json.load(urllib.request.urlopen(urllib.request.Request("https://api.github.com/repos/XTLS/Xray-core/releases/latest", headers={"Authorization": "Bearer " + os.environ.get("GH_TOKEN","")})))
lines=[r["tag_name"]]+[a["name"]+" "+str(a["size"]) for a in r["assets"] if "android" in a["name"] or "arm" in a["name"]]
open("out/summary.txt","w").write("\n".join(lines))
