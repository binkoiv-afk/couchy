import os, urllib.request, io, zipfile
os.makedirs("out", exist_ok=True)
u = "https://codeload.github.com/TeamNewPipe/NewPipeExtractor/zip/refs/tags/v0.26.5"
data = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "probe"}), timeout=60).read()
z = zipfile.ZipFile(io.BytesIO(data))
want = ["downloader/Downloader.java", "downloader/Request.java", "downloader/Response.java", "NewPipe.java",
        "stream/StreamInfo.java", "stream/VideoStream.java", "stream/AudioStream.java", "stream/Stream.java", "stream/StreamInfoItem.java",
        "channel/ChannelInfo.java", "channel/tabs/ChannelTabInfo.java", "channel/tabs/ChannelTabs.java",
        "kiosk/KioskList.java", "kiosk/KioskInfo.java", "search/SearchInfo.java", "Image.java", "ListInfo.java", "InfoItem.java",
        "localization/DateWrapper.java", "services/youtube/linkHandler/YoutubeSearchQueryHandlerFactory.java", "StreamingService.java",
        "services/youtube/YoutubeParsingHelper.java", "services/youtube/YoutubeService.java", "build.gradle", "extractor/build.gradle",
        "linkhandler/SearchQueryHandlerFactory.java", "stream/DeliveryMethod.java", "Page.java", "ListExtractor.java"]
log = open("out/summary.txt", "w")
for n in z.namelist():
    for w in want:
        if n.endswith("/" + w) and "/test/" not in n:
            t = z.read(n).decode("utf-8", "replace")
            # keep only signatures to stay small
            if n.endswith(".java"):
                lines = [l for l in t.splitlines() if ("public " in l or "static " in l or "abstract " in l) and "*" not in l.strip()[:2]]
                t = "\n".join(lines)
            log.write("=== " + n + "\n" + t[:12000] + "\n")
log.close()
