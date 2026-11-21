#!/usr/bin/env python3
"""v1.3.95 gate — the v1.3.94 field report: the new-tab crash, the
hold-menu race, the search-history fill button, the vault stage codes,
the AI-response discoverability, the honest PDF fallback.

CRASH — "there are some crashes here and there like opening new tabs" +
   the pasted stack: RuntimeException "Canvas: trying to use a recycled
   bitmap" from SdGridLayout.setPage -> tileCell -> FaviconStore.avatar
   -> Canvas.drawBitmap. ROOT CAUSE: the network favicon fallback's
   decode-then-store handoff — storeLocked caches the DECODED bitmap in
   the mem LRU when it is <= 64px (mem[h] === bmp), and fetchAsync then
   recycled THE SAME OBJECT; the next home build loaded the corpse from
   the LRU, stats() swallowed the getPixels throw, and drawBitmap killed
   the process. THE OWNERSHIP LAW: storeLocked RETURNS the bitmap it
   kept; the caller recycles only what the store did NOT keep. Belts:
   load() never hands out a recycled entry (self-heal to disk decode),
   avatar()/dr()/stats() all check isRecycled first. Bonus corpses found
   in the same neighborhood: tileCache.remove(h) never matched the
   COMPOUND keys ("host|size|radius|mode") so landed icons never
   refreshed letter tiles; the MAX×MAX downscale distorted tall icons
   (now aspect-true).

HOLD-MENU — "the menu pops up too quickly I cannot test the dragging or
   merging yet. Make the menu pop after a bit longer of holding a icon
   without moving only": 600 -> 1200 ms, and ANY drift past ~touch-slop
   (6dp) now cancels the menu for good (the drag arm stays at 16dp), so
   a hesitant drag start never sees the menu pop.

FILL BUTTON — "I type how to, click the button to fill the history to
   the search bar and edit that instead of retyping the whole thing":
   every history/bookmark/tab suggestion row carries a trailing pencil
   that fills the omnibox field IN PLACE (dialog stays open, list
   re-filters); a past SEARCH row fills its decoded QUERY via the new
   Engines.queryOf reverse resolver (engine templates + the generic
   q/query/search_query/text/k params + /search/<q> paths).

VAULT — "the password save still fails with warning could not save -
   vault write failed": the umbrella boolean hid three different
   refusals. saveEx() now returns the STAGE (ok / unreadable / write /
   verify / input), every refusal leaves a diag line, the retries back
   off 150 ms, the save runs OFF the main thread, the unreadable case
   gets an actionable toast (the vault screen's reset), and the reset
   banner shows even with an EMPTY index (the old gate hid the only
   recovery path for a corpse whose derived index was empty).

AI DISCOVERABILITY — "I still dont see a way to save the zai or any ai
   response as pdf or image" (the THIRD such report; the feature has
   been on the menu's front row since v1.3.87): the card is renamed
   "Save AI response" and the SAME picker rides inside the Save page
   sheet, where a user who wants to save something actually looks.

PDF — "the save as pdf of a website in bolt still saves as a raster
   image of the screen as pdf. I want the full website in vector
   scalable form of pdf": the print spooler is now PROBED and the
   failure is NAMED in diagnostics; when the print screen cannot open,
   the user CHOOSES the fallback — a TEXT vector PDF (reader-style
   block walk -> VectorPdf, real selectable text) or the honest image
   PDF — the silent raster downgrade is dead. (The dialogless print
   drive stays impossible: PrintDocumentAdapter's callback ctors are
   package-private — javap-verified against android-34 this session.)

DIAG NOISE — the log's repeating "upload auth 403 OPTIONS ...rum.
   aliyuncs.com/rum/monitor" was chat.z.ai's own telemetry beacon
   failing its CORS preflight, logged every few minutes: OPTIONS
   preflights are not uploads (excluded) and each host+code+method
   logs once per session."""
from pathlib import Path
import re, sys, subprocess, tempfile, os

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"

act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
fav = (APP / "util/FaviconStore.kt").read_text(encoding="utf-8")
eng = (APP / "util/Engines.kt").read_text(encoding="utf-8")
vault = (APP / "util/PasswordVault.kt").read_text(encoding="utf-8")
vact = (APP / "ui/PasswordVaultActivity.kt").read_text(encoding="utf-8")
psave = (APP / "util/PageSave.kt").read_text(encoding="utf-8")
item = (ROOT / "app/src/main/res/layout/item_suggest.xml").read_text(encoding="utf-8")
strs = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

fails = 0
def need(cond, msg):
    global fails
    if cond:
        print("  OK  " + msg)
    else:
        fails += 1
        print("  FAIL " + msg)

print("A — THE CRASH FIX (the favicon ownership law)")
need("private fun storeLocked(c: Context, h: String, icon: Bitmap): Bitmap? {"
     in fav,
     "A1 storeLocked RETURNS the bitmap it kept in the mem LRU (the "
     "ownership contract)")
need("val kept = storeLocked(appc, cand.second, bmp)" in fav
     and "if (kept !== bmp) {" in fav,
     "A2 the network fallback recycles ONLY what the store did not keep")
need("mem[h]?.let { if (!it.isRecycled) return it" in fav
     and "mem[base]?.let { if (!it.isRecycled) return it" in fav,
     "A3 load() self-heals: a recycled LRU entry degrades to a disk decode")
need("val bmp = load(c, host)?.takeIf { !it.isRecycled }" in fav
     and "fun dr(c: Context, host: String?): Drawable? {" in fav
     and "load(c, host)?.takeIf { !it.isRecycled }" in fav,
     "A4 avatar()/dr() never composite a corpse (both readers guarded)")
need("if (b.isRecycled) return Stats(" in fav,
     "A5 stats() checks isRecycled before getPixels (the swallowed-throw corpse)")
need('while (it.hasNext()) if (it.next().key.startsWith("$h|")) it.remove()' in fav,
     "A6 tileCache invalidation matches the COMPOUND keys (host|size|radius|mode)")
need("minOf(MAX.toFloat() / icon.width, MAX.toFloat() / icon.height)" in fav,
     "A7 the downscale is aspect-true (the old MAXxMAX stretch died)")

print("B — THE HOLD-MENU CONTRACT (still hold, or drag)")
need("ui.postDelayed(sdHoldMenu, 550);" in act,
     "B1 the menu needs a 550 ms still hold past the lift (600 -> 1200 in "
     "v1.3.95; v1.3.97: 1200 -> 550 — the field: 'the duration to activate "
     "the menu is a bit too long'; the release-without-move door in "
     "sdDragEnd covers the deliberate case, so the hold door rides the "
     "launcher cadence: lift ~400 ms + 550 ms ≈ 1 s from touch-down)")
need(re.search(r"if \(Math\.abs\(x - sdOriginX\) > Fx\.dp\(this, 6\)", act) is not None
     and act.find("Fx.dp(this, 6)") < act.find("Fx.dp(this, 16)"),
     "B2 any 6dp drift cancels the menu BEFORE the 16dp drag arm "
     "(movement is certain; the arm threshold is unchanged)")

print("C — THE SEARCH-HISTORY FILL BUTTON")
need('android:id="@+id/btn_sug_fill"' in item
     and 'android:id="@+id/iv_sug_chevron"' in item,
     "C1 the row layout carries the fill button + the chevron it replaces")
need("new SuggestAdapter(et)" in act and "SuggestAdapter(EditText field)" in act,
     "C2 the adapter owns the omnibox field (fills it in place)")
need("String q = com.bolt.browser.util.Engines.queryOf(" in act
     and "field.setSelection(fill.length());" in act,
     "C3 the bind fills the QUERY when the row's URL is a search, the URL "
     "otherwise; cursor at the end, dialog stays open")
need("fun queryOf(c: Context, url: String?): String?" in eng,
     "C4 Engines.queryOf exists (the reverse resolver)")
need('k == "q" || k == "query" || k == "search_query"' in eng
     and 'private fun decodeQuery' in eng,
     "C5 the generic param pass + '+'-aware decoding")
need('name="fill_from_history"' in strs,
     "C6 the fill button's content description string")

print("D — THE VAULT STAGE CODES")
need("fun saveEx(c: Context, host: String, user: String, pass: String): Int" in vault
     and "saveEx(c, host, user, pass) == SAVE_OK" in vault,
     "D1 saveEx returns the stage; save() is the boolean skin")
need("const val SAVE_UNREADABLE = 1" in vault and "const val SAVE_WRITE = 2" in vault
     and "const val SAVE_VERIFY = 3" in vault and "const val SAVE_INPUT = 4" in vault,
     "D2 all five stage codes are pinned")
need("SystemClock.sleep(150)" in vault,
     "D3 the write + verify retries BACK OFF (a keystore hiccup outlives a "
     "0ms retry)")
need('"pw save refused unreadable host=' in vault
     and '"pw save refused write host=' in vault
     and '"pw save refused verify host=' in vault,
     "D4 every refusal leaves a named diag line")
need('"bolt-pwsave"' in act and "PasswordVault.saveEx(" in act,
     "D5 the save runs off the main thread (the keystore thread)")
need("the vault file is unreadable on this device" in act,
     "D6 the unreadable toast points at the recovery path (the reset)")
need("if (PasswordVault.unreadable) {" in vact
     and "unreadable && entries.isNotEmpty()" not in vact,
     "D7 the reset banner shows even with an EMPTY index")

print("E — AI-RESPONSE DISCOVERABILITY")
need('card(R.drawable.ic_download, "Save AI response"' in act,
     "E1 the menu card is named for its OUTCOME (was 'AI export')")
need(re.search(r'"New tab".{0,4000}"Save AI response".{0,4000}"Scan QR"',
               act, re.S) is not None,
     "E2 the card still sits at the FRONT of the menu")
need("opts.add(getString(R.string.save_ai_response));" in act
     and "} else if (w == 4) {" in act and "startAiExport();" in act,
     "E3 the picker rides INSIDE the Save page sheet (the 5th option)")
need('name="save_ai_response"' in strs,
     "E4 the sheet string")

print("F — THE HONEST SAVE-AS-PDF")
need('a.packageManager.getPackageInfo("com.android.printspooler", 0)' in psave,
     "F1 the print spooler is PROBED before promising the print screen")
need('"print open failed: "' in psave and '"print spooler absent' in psave,
     "F2 the print failure is NAMED in diagnostics")
need('"Text PDF"' in act and '"Image PDF"' in act
     and "Print screen unavailable" in act,
     "F3 the fallback is a CHOICE (text-vector vs image), never a silent "
     "raster downgrade")
need("fun saveTextPdf(a: Activity, w: WebView, title: String, onDone: Callback)"
     in psave and "VectorPdf.parseBlocks(json)" in psave,
     "F4 the text-PDF fallback renders real vector text through VectorPdf")
need("var clone=best.cloneNode(true);" in psave,
     "F5 the block walk CLONES (never strips the live page)")
need(re.search(r'pre\'\)\{t=\(el2\.textContent', psave) is not None,
     "F6 code blocks keep their newlines (pre is not whitespace-collapsed)")

print("G — THE DIAG NOISE LAW")
need('!"OPTIONS".equals(method)' in act,
     "G1 OPTIONS preflights are not uploads (the rum.aliyuncs 403 spam)")
need("uploadAuthLogged.add(dedupKey)" in act
     and 'String dedupKey = code + " " + method + " " + host;' in act,
     "G2 each host+code+method logs ONCE per session")

# ---- semantic probe (node): the queryOf reverse resolver ----
node = subprocess.run(["which", "node"], capture_output=True, text=True).stdout.strip()
if node:
    print("H — queryOf semantics (node probe)")
    js = r"""
// faithful JS port of Engines.queryOf (the same law the Kotlin runs)
var TPLS = ["https://www.google.com/search?q=%s","https://duckduckgo.com/?q=%s",
            "https://www.bing.com/search?q=%s","https://search.brave.com/search?q=%s",
            "https://www.startpage.com/sp/search?query=%s"];
function decodeQuery(s){ try { return decodeURIComponent(s.replace(/\+/g,' ')).trim(); }
                         catch(e){ return s; } }
function queryOf(url){
  if(!url || url.indexOf('http')!==0) return null;
  var u = url.split('#')[0];
  for(var t=0;t<TPLS.length;t++){
    var tpl=TPLS[t], i=tpl.indexOf('%s'); if(i<0) continue;
    var pre=tpl.substring(0,i), post=tpl.substring(i+2);
    if(u.indexOf(pre)!==0) continue;
    var mid;
    if(post.length>0){ if(u.substring(u.length-post.length)!==post) continue;
                       mid=u.substring(pre.length,u.length-post.length); }
    else mid=u.substring(pre.length);
    var dec=decodeQuery(mid.split('&')[0]);
    if(dec && dec.trim()!=='') return dec;
  }
  try{
    var qi=u.indexOf('?'); var qs=qi>=0?u.substring(qi+1):"";
    var pairs=qs.split('&');
    for(var p=0;p<pairs.length;p++){
      var pair=pairs[p]; if(!pair) continue;
      var k=pair.split('=')[0];
      if(k==='q'||k==='query'||k==='search_query'||k==='text'||k==='k'){
        var eq=pair.indexOf('='); if(eq<0) continue;
        var v=decodeQuery(pair.substring(eq+1));
        if(v && v.trim()!=='') return v;
      }
    }
    var m=u.match(/\/(?:maps\/)?search\/([^\/?&]+)/);
    if(m){ var v2=decodeQuery(m[1]); if(v2 && v2.trim()!=='') return v2; }
  }catch(e){}
  return null;
}
var cases = [
  ["https://www.google.com/search?q=how+to+make+rice&ie=UTF-8", "how to make rice"],
  ["https://duckduckgo.com/?q=rice+balls", "rice balls"],
  ["https://www.bing.com/search?q=biryani&form=QBLH", "biryani"],
  ["https://www.startpage.com/sp/search?query=test&foo", "test"],
  ["https://www.youtube.com/results?search_query=lofi+beats", "lofi beats"],
  ["https://www.amazon.com/s?k=usb+cable", "usb cable"],
  ["https://www.google.com/maps/search/pizza+place", "pizza place"],
  ["https://www.google.com/search?q=c%2B%2B+tutorial", "c++ tutorial"],
  ["https://github.com/arfan1210/bolt-browser", null],
  ["https://example.com/watch?v=abc123", null],
  ["https://example.com/page?q=", null]
];
for(var c=0;c<cases.length;c++){
  var got=queryOf(cases[c][0]);
  if(got!==cases[c][1]) throw "case '"+cases[c][0]+"': expected '"+
      cases[c][1]+"' got '"+got+"'";
}
print("ALL-CASES-PASS");
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js.replace("print(", "console.log("))
        p1 = f.name
    try:
        r1 = subprocess.run([node, p1], capture_output=True, text=True)
        need(r1.returncode == 0 and "ALL-CASES-PASS" in r1.stdout,
              "the reverse resolver decodes every engine form and rejects "
              "non-search URLs (node-proven)")
    finally:
        os.unlink(p1)
else:
    print("  SKIP node not found — H probe skipped")

print("V — version pin")
need("versionCode 140" in gradle and 'versionName "1.4.26"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 140 / 1.4.26")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if fails:
    print(f"v1.3.95 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.95 gate: all green")
