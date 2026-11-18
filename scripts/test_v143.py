#!/usr/bin/env python3
"""v1.4.3 release gate — the egg's retry, ONE MEMORY TWO VIEWS, the vault's
caller-IV corpse.

The v1.4.2 field report (three fronts):

1. "I cannot continue the game after first failure as the game screen
   freezes and does nothing until I go back and enter again by tapping 7
   times" — THE RETRY CORPSE: resetRun() set `py = -1f // placed at first
   layout`, but onSizeChanged fires ONCE per view lifetime, not per run. On
   retry the bolt stayed at py=-1 (above the ceiling) and the FIRST physics
   frame died instantly, off-screen — every retry tap redrew the same dead
   frame. THE LAW: a "placed at layout" sentinel is a FIRST-RUN contract;
   every later reset must re-derive the placement from the live size.

2. "switching from tab groups above to follow tab order does not maintain
   the immediate before arrangements, the tab folders move itself by
   changing modes, fix it and make consistent" — TWO ORDERING MEMORIES: the
   on-top placement read folder order from the groups ARRAY, the in-order
   placement from tm.tabs (first-member positions); syncGroupPlacement
   rebuilt one from the other at each toggle, and the probe proved the
   round trip destroyed the interleaved arrangement ([T1,A,T2,B,T3] came
   back as [A,B,T1,T2,T3]). THE LAW: ONE memory (tm.tabs), TWO views — both
   placements derive folder order from first-member positions, the group
   drag block-moves members in both placements, and the toggle is a PURE
   VIEW CHANGE (the sync is deleted).

3. "Password saving and auto fill has issue, Could not save, vault write
   failed" (diag: `pw save refused write ... size=1` on two sites, two
   versions) — THE CALLER-IV CORPSE: writeAll initialized the cipher with
   a caller-provided IV in ENCRYPT mode for an AndroidKeyStore key created
   with setRandomizedEncryptionRequired at its default (true) — PROHIBITED
   by the documented contract, a deterministic InvalidAlgorithmParameterException
   on real hardware. NO PASSWORD EVER SAVED since v1.3.38 (size=1 = the
   vault was always empty; the v1.3.91/92/95 fixes hardened retry/verify
   logic AROUND a write that could never land). THE LAW: let the keystore
   generate the IV (init with the key alone), read cipher.iv, prepend it;
   DECRYPT is the mirror (caller supplies the stored IV) and was always
   correct. Plus the evidence trails the earlier fixes lacked: every vault
   refusal now logs its throwable class + message.

PART A — static checks for all three fronts.
PART B — the functional probes (javac + run against the real org.json):
  B1 the placement laws (the REAL render branches + moveGroupRelative:
     toggle-never-touches-tm.tabs, round-trip byte-identical, the on-top
     drag block-moves and carries, never-between-members);
  B2 the vault cipher round-trip (the fixed init/iv sequence transcribed
     1:1 with a JVM AES key: encrypt with cipher.iv, decrypt with the
     stored IV, 12-byte split, tamper caught by the GCM tag).

V — the version pin.
"""
import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
SIA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java")
EGG = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BoltEggActivity.kt")
PV = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/PasswordVault.kt")
STRS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
GR = os.path.join(ROOT, "app/build.gradle")


def balance_end(src, start):
    """v1.4.7: the follow branch's true close, by brace balance (the
    brace-hop chains break every time the walk's nesting changes)."""
    depth = 0; i = start; begun = False
    while i < len(src):
        c = src[i]
        if c == '{': depth += 1; begun = True
        elif c == '}':
            depth -= 1
            if begun and depth == 0: return i + 1
        i += 1
    return -1

fails = 0

def need(cond, why):
    global fails
    tag = "PASS" if cond else "FAIL"
    print(f"  {tag} {why}")
    if not cond:
        fails += 1

def slurp(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

ta, tm, sia, egg, pv, strs, gradle = (slurp(TA), slurp(TM), slurp(SIA),
                                      slurp(EGG), slurp(PV), slurp(STRS), slurp(GR))

print("A1 — the egg's retry corpse (BoltEggActivity)")
need(re.search(r"py = if \(height > 0\) height \* 0\.44f else -1f", egg) is not None,
     "resetRun re-places the bolt from the LIVE height (the -1 sentinel was a first-run-only contract)")
need("RETRY CORPSE" in egg and "onSizeChanged fires ONCE per view lifetime, not per RUN" in egg,
     "the fix's comment records the corpse + the law")
need("placed at layout\" sentinel is a FIRST-RUN contract" in egg,
     "the law is written where the next session will read it")
need(re.search(r"if \(dead\) resetRun\(\)\s*\n\s*if \(!started\) \{ started = true; lastT = SystemClock\.elapsedRealtime\(\) \}", egg) is not None,
     "the retry door flows THROUGH the start door (reset, then start + flap — the old branch skipped the flap)")
need("if (dead) { resetRun(); started = true; return true }" not in egg,
     "the old early-return retry branch is gone")
need(re.search(r"lastT = SystemClock\.elapsedRealtime\(\)\s*\n\s*postInvalidateOnAnimation\(\)", egg) is not None,
     "resetRun also zeroes the frame clock (no stale-dt first frame)")

print("A2 — ONE MEMORY, TWO VIEWS (the placement redesign)")
need(re.search(r"void syncGroupPlacement", tm) is None and re.search(r"syncGroupPlacement\(", ta + sia) is None,
     "syncGroupPlacement is GONE (no definition, no calls — the toggle is a pure view change; only the historical comment remains)")
need(re.search(r"for \(JSONObject g : tm\.groupsInFirstMemberOrder\(\)\) items\.add\(g\);", ta) is not None,
     "the on-top render lists folders in FIRST-MEMBER order (derived from tm.tabs, the one memory)")
need(re.search(r'prefs\.set\("groupsOnTop", w == 0\);\s*\n\s*// v1\.4\.3[\s\S]{0,560}?adapter\.reload\(\);', ta) is not None,
     "the switcher toggle: pref flip + reload, nothing else")
need(re.search(r'prefs\.set\("groupsOnTop", w == 0\);\s*\n\s*// v1\.4\.3[\s\S]{0,260}?refresh\(\);', sia) is not None,
     "the settings toggle: pref flip + refresh, nothing else")
need(re.search(r'gdone = tm\.moveGroupRelative\(gid, ganchor, gafter\);', ta) is not None
     and "prefs.moveGroupRelative" not in ta,
     "the group drag block-moves members in BOTH placements (one drag law)")
need(re.search(r'gunder != null && !prefs\.groupsOnTop\(\)\)', ta) is not None,
     "loose tab cards anchor drops in the in-order placement only")
need("ONE MEMORY, TWO VIEWS" in tm,
     "the placement law is recorded in TabManager where the next session reads it")

print("A3 — the vault's caller-IV corpse (PasswordVault)")
need(re.search(r"cipher\.init\(Cipher\.ENCRYPT_MODE, key\(c\)\)", pv) is not None,
     "ENCRYPT init passes the KEY ALONE — the keystore generates the IV (the documented contract)")
inits = [l.strip() for l in re.findall(r"cipher\.init\(Cipher\.(?:ENCRYPT|DECRYPT)_MODE[^\n]*", pv)]
need(len(inits) == 2
     and any(l.startswith("cipher.init(Cipher.ENCRYPT_MODE, key(c))") for l in inits)
     and any(l.startswith("cipher.init(Cipher.DECRYPT_MODE, key(c), GCMParameterSpec(128, iv))") for l in inits),
     f"exactly two cipher inits: ENCRYPT (key alone) + DECRYPT (stored IV) — got {inits}")
need(re.search(r"cipher\.init\(Cipher\.DECRYPT_MODE, key\(c\), GCMParameterSpec\(128, iv\)\)", pv) is not None,
     "DECRYPT still supplies the STORED IV (the mirror path — always was correct)")
need(re.search(r"val iv = cipher\.iv", pv) is not None
     and re.search(r"if \(iv\.size != 12\)", pv) is not None,
     "the write reads cipher.iv and guards the 12-byte GCM assumption")
pv_flat = " ".join(re.sub(r"//+", " ", pv).split())
need("CALLER-IV CORPSE" in pv_flat and "NO PASSWORD EVER SAVED" in pv_flat,
     "the corpse + its history are recorded in the write path")
need("pw write fail cls=" in pv and "pw read fail cls=" in pv and "pw key $stage fail cls=" in pv.replace("${stage}", "$stage"),
     "every vault refusal logs its throwable class + message (the evidence the v1.3.91-95 fixes lacked)")
need('logKeyFail(c, "getEntry", t)' in pv and 'logKeyFail(c, "getKey", t)' in pv
     and 'intArrayOf(256, 192, 128)' in pv,
     "the key fetch falls back getEntry->getKey and generation 256->192->128 (OEM keystore quirks, all logged)")

print("B — the functional probes (javac + run)")
g_start = tm.find("    public List<JSONObject> groups() {")
g_end = tm.find("    /** v1.4.0 — THE ORPHAN HEAL")
f_start = tm.find("    /** the groups reordered by their FIRST MEMBER")
s_end = tm.rfind("}")
r_start = pv.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
# Prefs.reorderGroups lives in Prefs.java
PREFS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
prefs = slurp(PREFS)
pr_start = prefs.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
pr_end = prefs.find("    // ---- speed dial")
io_top = ta.find("if (prefs.groupsOnTop()) {", ta.find("private void rebuildItems()"))
# v1.4.8: the walk's empty placement moved into TabManager.followRowOrder
fro = tm.find("public List<Object> followRowOrder(boolean includeAnchorless)")
io_for = fro
io_add = tm.find("out.add(putAt++, g);", fro) if fro >= 0 else -1
_else = ta.find("} else {", io_top)
io_end2 = (balance_end(ta, ta.find("{", _else)) if _else >= 0 else -1)   # v1.4.7: brace-balanced from the ELSE's own brace (the slot law re-nested the walk)
if min(g_start, g_end, f_start, s_end, pr_start, pr_end, io_top, io_for, io_add, io_end2) < 0:
    need(False, "the methods + render branches are where the gate expects them")
    probe_ok = False
else:
    probe_ok = True

if probe_ok:
    rr_start = tm.find("    /** v1.4.5 — keep the ACTIVE TAB")
    rr_end = tm.find("    /** the groups reordered by their FIRST MEMBER")
    rr_blk = tm[rr_start:rr_end].replace("    private void reResolveActive",
                                         "    static void reResolveActive", 1) if 0 <= rr_start < rr_end else ""
    tm_block = tm[g_start:g_end] + rr_blk + tm[f_start:s_end]
    for sig in ("    public List<JSONObject> groups() {",
                "    public void assignGroup(int tabIndex, long groupId) {",
                "    public List<Tab> tabsOfGroup(long gid) {",
                "    public List<JSONObject> groupsInFirstMemberOrder() {",
                "    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {",
                # v1.4.8: the follow-order row model family (render calls it)
                "    public List<Object> followRowOrder() {",
                "    public List<Object> followRowOrder(boolean includeAnchorless) {",
                "    public Tab tabByTid(long tid) {",
                "    public void refreshAnchorsAround(String movedKey) {",
                "    public void captureGroupRowAnchors(long gid) {",
                "    public void normalizeEmptyAnchors() {",
                "    public boolean syncGroupsArrayToFollowOrder() {",
                "    private int rowKeyIndex(List<Object> rows, String key) {",
                "    private int folderRowIndex(List<Object> rows, long gid) {",
                "    private int anchorRowIn(List<Object> rows, JSONObject g) {",
                "    private boolean pendingEmptyKey(List<Object> rows, String key) {"):
        sig2 = sig.replace("    public ", "    public static ", 1) if sig.startswith("    public") \
            else sig.replace("    private ", "    private static ", 1)
        tm_block = tm_block.replace(sig, sig2, 1)
    io_block = ta[io_top:io_end2].replace("tm.groupsInFirstMemberOrder()", "groupsInFirstMemberOrder()").replace("tm.groups()", "groups()")
    io_block = io_block.replace("tm.followRowOrder()", "followRowOrder()")          # v1.4.8: the walk moved into TabManager
    io_block = io_block.replace("tm.normalizeEmptyAnchors()", "normalizeEmptyAnchors()")
    prefs_block = prefs[pr_start:pr_end]
    javac = None
    for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac"),
                 "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac",
                 shutil.which("javac") or ""):
        if cand and os.path.exists(cand):
            javac = cand
            break
    if not javac:
        need(False, "javac found (run scripts/setup_toolchain.sh first)")
        probe_ok = False

if probe_ok:
    jar = None
    work = "/tmp/bolt_v143_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v143_probe/json.jar",
              "/tmp/bolt_v142_gate/json-20240303.jar", "/tmp/org.json.jar"):
        if os.path.exists(p) and os.path.getsize(p) > 50_000:
            with open(p, "rb") as f:
                if f.read(2) == b"PK":
                    jar = p
                    break
    if not jar:
        urls = ("https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar",
                "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar")
        os.makedirs(work, exist_ok=True)
        dst = os.path.join(work, "json.jar")
        for u in urls:
            r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", dst, u], capture_output=True, text=True)
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 50_000:
                with open(dst, "rb") as f:
                    if f.read(2) == b"PK":
                        jar = dst
                        break
    if not jar:
        need(False, "the org.json jar (aliyun/maven)")
        probe_ok = False

if probe_ok:
    harness = """
import org.json.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public class Probe {
    static class Tab {
        public long groupId = 0;
        public long tid = 0;              // v1.4.8: the row identity (rowKeyOf)
        static long seq = 0;
        public String name = "";
        Tab(String n, long g) { name = n; groupId = g; tid = ++seq; }
        public String toString() { return name; }
    }
    static List<Tab> tabs = new ArrayList<Tab>();
    static int active = -1;
    static void save() {}
    static class PrefsStub {
        JSONArray groups = new JSONArray();
        public JSONArray groups() { return groups; }
        public boolean groupsOnTop() { return Probe.groupsOnTop; }
        void scheduleWrite() {}
        public void setGroupSlot(long id, int slot) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == id) {
                    try { if (slot >= 0) g.put("slot", slot); else g.remove("slot"); } catch (Exception e) {}
                }
            }
        }
        public void setGroupAnchor(long id, String prevKey, String nextKey) {   // v1.4.8
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == id) {
                    try { g.put("ap", prevKey == null ? "" : prevKey); g.put("an", nextKey == null ? "" : nextKey); g.remove("slot"); } catch (Exception e) {}
                }
            }
        }
        public void clearGroupPos(long id) {                                     // v1.4.8
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == id) {
                    try { g.remove("slot"); g.remove("ap"); g.remove("an"); } catch (Exception e) {}
                }
            }
        }
""" + prefs_block.replace("    public boolean reorderGroups", "        public boolean reorderGroups", 1).replace("\n    ", "\n        ") + """
    }
    static PrefsStub prefs = new PrefsStub();
    static boolean groupsOnTop;
    static boolean INCOGNITO = false;
    // ---- the REAL TabManager methods (static-ized) ----
""" + tm_block + """
    static void setGroups(int... ids) {
        prefs.groups = new JSONArray();
        for (int id : ids) { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("name", "G"+id); } catch (Exception e) {} prefs.groups.put(o); }
    }
    static String show(List<Object> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            sb.append(o instanceof JSONObject ? ("F#"+((JSONObject)o).optLong("id")) : o.toString());
            if (i < list.size()-1) sb.append(",");
        }
        return sb+"]";
    }
    static List<Object> render() {
        List<Object> items = new ArrayList<Object>();
        List<Tab> sorted = new ArrayList<Tab>(tabs);
""" + io_block + """
        return items;
    }
    static String names() { StringBuilder sb = new StringBuilder(); for (Tab t : tabs) sb.append(t.name); return sb.toString(); }
    static int bad = 0;
    static void ok(String label, boolean v) { if (!v) { System.out.println("FAIL "+label); bad++; } }
    static void eq(String label, Object a, Object b) { ok(label+" ("+a+" vs "+b+")", a.toString().equals(b.toString())); }

    // ---- B2: the vault cipher sequence, transcribed 1:1 (a JVM AES key
    // stands in for the keystore key; the IV LAW is what is under test) ----
    static SecretKey jvmKey() throws Exception {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        return g.generateKey();
    }
    static byte[] vaultWrite(byte[] plain, SecretKey k) throws Exception {
        // the FIXED sequence, 1:1: init with the key ALONE, read the generated
        // IV (Kotlin's cipher.iv is this getter)
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, k);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IllegalStateException("unexpected IV length " + iv.length);
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, 12); System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }
    static byte[] vaultRead(byte[] blob, SecretKey k) throws Exception {
        // the read path, 1:1 (caller IV in DECRYPT mode — always was correct)
        byte[] iv = java.util.Arrays.copyOfRange(blob, 0, 12);
        byte[] ct = java.util.Arrays.copyOfRange(blob, 12, blob.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
        return cipher.doFinal(ct);
    }

    public static void main(String[] args) throws Exception {
        // ===== B1: THE PLACEMENT LAWS =====
        tabs.clear();
        tabs.add(new Tab("T1", 0));
        tabs.add(new Tab("A1", 1)); tabs.add(new Tab("A2", 1));
        tabs.add(new Tab("T2", 0));
        tabs.add(new Tab("B1", 2));
        tabs.add(new Tab("T3", 0));
        setGroups(1, 2);
        groupsOnTop = false;
        String inOrderBefore = show(render());
        String memBefore = names();
        // L1: the toggle is a pure view change — tm.tabs NEVER moves
        groupsOnTop = true;
        String onTop = show(render());
        ok("L1 the on-top toggle never touches tm.tabs", names().equals(memBefore));
        groupsOnTop = false;
        ok("L1b toggling back still never touches tm.tabs", names().equals(memBefore));
        // L2: folder + loose subsequences identical across placements
        List<Object> inline = render(); groupsOnTop = true; List<Object> pinned = render(); groupsOnTop = false;
        StringBuilder fInline = new StringBuilder(), fPinned = new StringBuilder();
        for (Object o : inline) if (o instanceof JSONObject) fInline.append(((JSONObject)o).optLong("id"));
        for (Object o : pinned) if (o instanceof JSONObject) fPinned.append(((JSONObject)o).optLong("id"));
        eq("L2 folder order identical across placements", fInline.toString(), fPinned.toString());
        StringBuilder lInline = new StringBuilder(), lPinned = new StringBuilder();
        for (Object o : inline) if (o instanceof Tab) lInline.append(((Tab)o).name);
        for (Object o : pinned) if (o instanceof Tab) lPinned.append(((Tab)o).name);
        eq("L2b loose order identical across placements", lInline.toString(), lPinned.toString());
        // L3: the round trip is byte-identical (the v1.4.2 corpse)
        groupsOnTop = true; groupsOnTop = false;
        eq("L3 round-trip render is byte-identical", show(render()), inOrderBefore);
        // L4: an ON-TOP folder drag block-moves members and carries across
        groupsOnTop = true;
        List<Tab> hostA = tabsOfGroup(1);
        boolean moved = moveGroupRelative(2, hostA.get(0), false);
        ok("L4a the on-top drag block-moves", moved);
        String dragged = show(render());
        ok("L4b B renders above A on-top after the drag", dragged.indexOf("F#2") < dragged.indexOf("F#1"));
        StringBuilder ga = new StringBuilder();
        for (int i = 0; i < prefs.groups.length(); i++) ga.append("G").append(prefs.groups.optJSONObject(i).optLong("id"));
        eq("L4c the groups array follows first-member order", ga.toString(), "G2G1");
        groupsOnTop = false;
        String inDragged = show(render());
        ok("L4d the drag carries into the in-order placement", inDragged.indexOf("F#2") < inDragged.indexOf("F#1"));
        // L5: never between members
        List<Tab> hostA2 = tabsOfGroup(1);
        ok("L5a the boundary drag moves", moveGroupRelative(2, hostA2.get(0), true));
        int a1 = -1, a2 = -1, b1 = -1;
        for (int i = 0; i < tabs.size(); i++) {
            String nm = tabs.get(i).name;
            if (nm.equals("A1")) a1 = i; if (nm.equals("A2")) a2 = i; if (nm.equals("B1")) b1 = i;
        }
        ok("L5b B never lands between A's members", !(b1 > a1 && b1 < a2));

        // ===== B2: THE VAULT CIPHER ROUND-TRIP =====
        SecretKey k = jvmKey();
        String secret = "[{\\"h\\":\\"example.com\\",\\"u\\":\\"someone\\",\\"p\\":\\"hunter2\\"}]";
        byte[] blob = vaultWrite(secret.getBytes("UTF-8"), k);
        ok("B2a the write produces iv(12)||ct", blob.length > 12 + secret.length());
        eq("B2b the round-trip returns the payload", new String(vaultRead(blob, k), "UTF-8"), secret);
        // every write is randomized (a fresh IV each time — the keystore law)
        byte[] blob2 = vaultWrite(secret.getBytes("UTF-8"), k);
        ok("B2c each write carries a FRESH IV (ciphertexts differ)", !java.util.Arrays.equals(blob, blob2));
        // tamper is caught by the GCM tag
        byte[] tampered = blob.clone(); tampered[tampered.length - 1] ^= 0x01;
        boolean caught = false;
        try { vaultRead(tampered, k); } catch (Exception e) { caught = true; }
        ok("B2d a tampered payload fails the GCM tag", caught);
        // the wrong key fails the tag too
        boolean wrongKey = false;
        try { vaultRead(blob, jvmKey()); } catch (Exception e) { wrongKey = true; }
        ok("B2e the wrong key fails the GCM tag", wrongKey);

        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write(harness)
        r = subprocess.run([javac, "-cp", jar, "-d", d, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the extracted methods + render branches compile (probe)")
            print(r.stderr[:2500])
        else:
            r2 = subprocess.run(["java", "-cp", d + os.pathsep + jar, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL code runs green: the placement laws (toggle never touches tm.tabs, folder+loose order parity, byte-identical round trip, the drag carries, never-between-members) + the vault cipher round-trip (iv(12)||ct, decrypt, fresh IV per write, GCM tamper + wrong-key catches)")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:2500])
                print(r2.stderr[:800])

print("V — the version pin")
need('versionCode 129' in gradle, 'versionCode 129')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)
need('versionName "1.4.15"' in gradle, 'versionName "1.4.15"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)
entries = re.findall(r"<item>([^<]+)</item>", strs[strs.find('name="about_changelog"'):strs.find("</string-array>", strs.find('name="about_changelog"'))])
need(entries and entries[0].startswith("1.4.15|"), "the About changelog's newest entry is 1.4.13 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)

print()
if fails == 0:
    print("v1.4.3 gate: ALL GREEN")
else:
    print(f"v1.4.3 gate: {fails} FAILURES")
    sys.exit(1)
