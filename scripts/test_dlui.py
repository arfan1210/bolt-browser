#!/usr/bin/env python3
"""v1.3.59 release gate — the Downloads redesign + the shelf-dismissal fix.

Two field reports shipped in this version:
  1. "Download pop ups don't go away after the download complete popup. I
     want the bottom pop up to go away once I either have opened to view it
     or closed it."  -> the complete-dialog dismissal retires the matching
     shelf chip, and every OPEN path off the shelf (chip body, folder
     button, openShelfFile) retires it too.
  2. "the downloaded files is pretty monotonous... no separation for the
     different days, no icons, no previews, no card mode... re-imagine the
     downloads" -> ui/DlAdapter.kt (day-grouped sections + type icons +
     async previews + card grid + the action sheet), wired into
     DownloadsActivity with a persisted card/list toggle.

This gate STATICALLY verifies the wiring (source-level pins, the same
approach as test_blobdl.py) and BEHAVIORALLY simulates the day-grouping
algorithm (a Python mirror of DlAdapter.submit()).
"""
import datetime as dt
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
fails = []


def check(name, cond):
    print(("  PASS " if cond else "  FAIL ") + name)
    if not cond:
        fails.append(name)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


ba = read(os.path.join(APP, "java", "com", "bolt", "browser", "ui", "BrowserActivity.java"))
kt = read(os.path.join(APP, "java", "com", "bolt", "browser", "ui", "DlAdapter.kt"))
dl = read(os.path.join(APP, "java", "com", "bolt", "browser", "ui", "DownloadsActivity.java"))
strings = read(os.path.join(APP, "res", "values", "strings.xml"))

print("== A. the shelf-dismissal fix (BrowserActivity.java) ==")
m = re.search(r"protected void showDlPopup\(.+?^    protected void", ba, re.S | re.M)
check("showDlPopup dialog-dismiss listener retires the chip (dlShelfDismissed.add)",
      m and "dlShelfDismissed.add(it.id)" in m.group(0))
check("openShelfDone helper exists and marks the chip dismissed",
      "protected void openShelfDone(DownloadEngine.Item it)" in ba
      and "dlShelfDismissed.add(it.id)" in re.search(
          r"protected void openShelfDone\(.*?\n    \}", ba, re.S).group(0))
m = re.search(r"protected View buildDlChip\(.+?^    protected String", ba, re.S | re.M)
check("shelf chip DONE tap (body + folder button) routes through openShelfDone",
      m and m.group(0).count("openShelfDone(it)") >= 2)
check("openShelfFile delegates to openShelfDone",
      re.search(r"openShelfFile\(DownloadEngine\.Item it\) \{\s*openShelfDone\(it\);", ba) is not None)

print("== B. the type vocabulary (DlAdapter.kt) ==")
check("seven categories detected (incl. other)", kt.count('"images" -> R.drawable.ic_dl_image') == 1
      and "ic_dl_video" in kt and "ic_dl_audio" in kt and "ic_dl_apk" in kt
      and "ic_dl_arch" in kt and "ic_dl_doc" in kt and "ic_dl_file" in kt)
check("seven distinct icons on disk",
      all(os.path.exists(os.path.join(APP, "res", "drawable", f))
          for f in ("ic_dl_image.xml", "ic_dl_video.xml", "ic_dl_audio.xml",
                    "ic_dl_apk.xml", "ic_dl_arch.xml", "ic_dl_doc.xml",
                    "ic_dl_file.xml", "ic_dl_grid.xml", "ic_dl_rows.xml")))
m = re.search(r"object DlTypes \{.*?\n\}", kt, re.S)
check("colorOf has a distinct fixed hue per category",
      m and len(re.findall(r"0xFF[0-9A-F]{6}", m.group(0))) >= 7)
check("catOf is @JvmStatic (shared with the Java shelf + activity)",
      "@JvmStatic" in m.group(0))
check("shelf chip wears the file-type icon",
      "DlTypes.INSTANCE.catOf(it.mime, it.name)" in ba
      and "DlTypes.INSTANCE.iconOf(dcat)" in ba)

print("== C. the preview engine (DlAdapter.kt) ==")
m = re.search(r"object DlPreview \{.*?\n\}", kt, re.S)
check("preview decode is off-thread (single low-priority executor)",
      m and "Executors.newSingleThreadExecutor" in m.group(0)
      and "MIN_PRIORITY" in m.group(0))
check("LruCache keyed by item id + size",
      m and "LruCache<String, Bitmap>" in m.group(0) and '"${it.id}:$size"' in m.group(0))
check("recycled-view tag guard (iv.tag == key) before applying",
      m and "iv.tag == key" in m.group(0))
check("bind mints a fresh tag / clears it first",
      "thumb.tag = null" in kt)
check("image/video/apk decode paths + graceful fallback",
      "decodeImage" in m.group(0) and "decodeVideo" in m.group(0)
      and "decodeApk" in m.group(0) and "return null" in m.group(0))
check("preview only for finished items (ST_DONE gate)",
      re.search(r"\w\.status == ST_DONE && DlTypes\.previewable\(cat\)", kt) is not None
      and "it.status != ST_DONE" in kt)

print("== D. the grouping adapter (DlAdapter.kt) ==")
check("Active section header for queued/running/paused",
      'Hdr(act.getString(R.string.active_downloads)' in kt)
# v1.3.91: the sort moved to the ACTIVITY (the adapter's day grouping
# needs time-descending input; the default mode still is newest-first)
check("history sorted by time descending",
      "default: return Long.compare(b.time, a.time);" in dl
      and "sortByDescending" not in kt.replace("sortByDescending { it.time }", ""))
check("v1.3.91 sort pass present",
      "sortMode" in dl and "\"sortDl\"" in dl and "addSortButton" in dl
      and "fun submit(list: List<Item>, flat: Boolean, flatLabel: String? = null," in kt)
check("day labels: Today / Yesterday / d MMMM / d MMMM yyyy",
      "R.string.today" in kt and "R.string.yesterday" in kt
      and 'SimpleDateFormat("d MMMM"' in kt and 'SimpleDateFormat("d MMMM yyyy"' in kt)
check("day key uses a locale-stable format (yyyy-MM-dd, Locale.US)",
      'SimpleDateFormat("yyyy-MM-dd", Locale.US)' in kt)
check("card mode flag re-notifies", "var cards: Boolean" in kt
      and "field = v; notifyDataSetChanged()" in kt)
check("three view types (header / row / card)",
      "TYPE_HDR" in kt and "TYPE_ROW" in kt and "TYPE_CARD" in kt)

print("== E. the activity wiring (DownloadsActivity.java) ==")
check("DlAdapter on a 2-span grid, headers + list rows span all",
      "new GridLayoutManager(this, 2)" in dl and "getSpanSize" in dl
      and "dlAd.isHeader(pos) || !dlAd.getCards() ? 2 : 1" in dl)
check("card/list toggle persisted as dlAsCards",
      'prefs.b("dlAsCards", true)' in dl and 'prefs.set("dlAsCards", cards)' in dl)
check("long-press routes to the DlSheet action sheet",
      "DlSheet.INSTANCE.show(" in dl)
check("tap routes through primary() (open/resume/retry/pause)",
      "DlSheet.INSTANCE.openFile(this, it)" in dl)
check("submit() replaces the old direct adapter bind",
      "dlAd.submit(items, sortMode != 0, flatLabel, sortMode != 0)" in dl
      and "new DlAdapter" in dl)
check("the old Java DlAdapter inner class is gone",
      "class DlAdapter extends RecyclerView.Adapter" not in dl)

print("== F. the action sheet (DlAdapter.kt) ==")
m = re.search(r"object DlSheet \{.*?\n\}", kt, re.S)
check("grab-handle sheet via Ui.sheetBody/showSheet",
      "Ui.sheetBody(act)" in m.group(0) and "Ui.showSheet(act, body)" in m.group(0))
check("open / share / copy link / remove on DONE",
      "R.string.open_file" in m.group(0) and "R.string.share_file" in m.group(0)
      and "R.string.copy_link" in m.group(0) and "R.string.delete_entry" in m.group(0))
check("pause/cancel on active, retry/remove on failed",
      m.group(0).count("eng.cancel(it.id)") >= 2
      and m.group(0).count("eng.retry(it.id)") >= 1)
check("share streams the file URI with the read grant",
      "Intent.EXTRA_STREAM" in m.group(0)
      and "FLAG_GRANT_READ_URI_PERMISSION" in m.group(0))
check("copy link goes through the clipboard + toast",
      "ClipboardManager" in m.group(0) and "dl_link_copied" in m.group(0))

print("== G. strings ==")
for s in ("today", "yesterday", "active_downloads", "share_file", "dl_link_copied"):
    check(f"string '{s}' exists", f'name="{s}"' in strings)

print("== H. behavioral: day-grouping mirror of DlAdapter.submit() ==")


def submit(items, now, labels):
    """Python mirror of the Kotlin submit(): returns the section headers
    (title, count) in list order. `labels` maps day-key -> display name."""
    rows = []
    active = [i for i in items if i["st"] in ("q", "r", "p")]
    hist = sorted((i for i in items if i["st"] not in ("q", "r", "p")),
                  key=lambda i: i["t"], reverse=True)
    if active:
        rows.append(("Active", len(active)))
    last_key, hdr_idx, hdr_count = None, -1, 0
    for it in hist:
        key = it["t"].strftime("%Y-%m-%d")
        if key != last_key:
            last_key, hdr_count = key, 1
            rows.append([labels[key], 1])
            hdr_idx = len(rows) - 1
        else:
            hdr_count += 1
            rows[hdr_idx][1] = hdr_count
    return [(t, c) for (t, c) in rows]


NOW = dt.datetime(2026, 11, 5, 18, 0)
D = lambda days_ago, h=12: NOW - dt.timedelta(days=days_ago, hours=NOW.hour - h)
L = {D(0).strftime("%Y-%m-%d"): "Today", D(1).strftime("%Y-%m-%d"): "Yesterday",
     D(3).strftime("%Y-%m-%d"): "2 November", D(30).strftime("%Y-%m-%d"): "6 October"}

items = [
    {"st": "d", "t": D(0, 9)}, {"st": "d", "t": D(0, 10)}, {"st": "d", "t": D(0, 8)},
    {"st": "f", "t": D(1, 15)}, {"st": "d", "t": D(1, 9)},
    {"st": "d", "t": D(3, 20)}, {"st": "d", "t": D(30, 1)},
    {"st": "r", "t": D(0, 17)}, {"st": "p", "t": D(2, 3)},
]
hdrs = submit(items, NOW, L)
check("active strip first with the right count",
      hdrs[0] == ("Active", 2))
check("today groups 3 (with the running one inside Active, not Today)",
      hdrs[1] == ("Today", 3))
check("yesterday groups done+failed together (2)",
      hdrs[2] == ("Yesterday", 2))
check("older days each get their own header",
      hdrs[3] == ("2 November", 1) and hdrs[4] == ("6 October", 1))
check("empty list -> no headers", submit([], NOW, L) == [])
check("history only -> no Active strip",
      submit([{"st": "d", "t": D(0, 9)}], NOW, L) == [("Today", 1)])
check("same-day items stay one header even when interleaved by time",
      submit([{"st": "d", "t": D(1, 9)}, {"st": "d", "t": D(1, 10)},
              {"st": "d", "t": D(1, 8)}], NOW, L) == [("Yesterday", 3)])

print()
if fails:
    print(f"test_dlui: {len(fails)} FAILURES: " + "; ".join(fails))
    sys.exit(1)
print("test_dlui: all assertions green (shelf dismissal + type icons + previews "
      "+ day grouping + card/list toggle + action sheet, v1.3.59)")
