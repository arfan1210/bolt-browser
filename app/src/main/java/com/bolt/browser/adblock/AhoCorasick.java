package com.bolt.browser.adblock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * v1.4.9 — compact Aho-Corasick automaton for the adblock filter engine.
 *
 * WHY THIS EXISTS: the pre-v1.4.9 FilterEngine compiled a java.util.regex
 * Pattern for EVERY one of the ~132k bundled EasyList/EasyPrivacy network
 * rules (needless for the ~98% that are plain literal matches) and the
 * compiled Pattern objects bloated the heap to ~130 MB. The v1.4.9 engine
 * keeps literal rules as plain strings + this automaton, and compiles a
 * Pattern only for the ~1.7k rules that genuinely need regex syntax
 * (unbounded '*' wildcards, repeated '^' separators). One scan of a URL
 * against the automaton finds EVERY keyword that occurs in it — replacing
 * both the per-rule regex matching and the old enumerate-all-substrings
 * token probing.
 *
 * MEMORY SHAPE (the point of the rewrite): the flattened automaton is
 * primitive arrays only — a CSR edge block (char[] + int[] + row offsets),
 * one int[] fail row and one int[] output-head row per state, and two
 * output arrays sized by the keyword count. ~68k states for the bundled
 * lists flatten to a few MB; the build-time trie (array lists) is freed
 * after build(). No per-rule objects beyond the caller's own payload
 * references, and nothing per-request: scan() allocates nothing.
 *
 * THREADING: add()/build() are confined to the single builder thread
 * (FilterEngine.load() is synchronized and swaps the built automaton in
 * as one volatile reference); scan() is read-only over immutable arrays
 * and safe from any number of threads concurrently.
 *
 * DELIBERATELY MINIMAL: no removal, no iteration, no case folding (the
 * engine lowercases rules and URLs before they get here), no external
 * dependencies — pure java.* so it compiles and runs standalone under the
 * release gates' functional probes.
 */
public final class AhoCorasick {

    /** scan() reports every keyword payload that ends at the current
     *  position; return false from accept() to stop the scan early. */
    public interface Sink { boolean accept(Object payload); }

    // ---- build-time trie: kids.get(s) = list of {char c, int to} ----
    private final ArrayList<ArrayList<long[]>> kids = new ArrayList<>();
    private int states = 1;                       // state 0 = root
    private int keywordCount = 0;                 // survives build() (payloads is freed)

    // ---- keywords as recorded by add(): end state + payload per keyword ----
    private final ArrayList<Integer> endStates = new ArrayList<>();
    private final ArrayList<Object> payloads = new ArrayList<>();

    // ---- flattened automaton (filled by build()) ----
    private int[] fail;          // per state: the longest-proper-suffix state
    private int[] rowStart;      // CSR: edges of state s live in [rowStart[s], rowStart[s+1])
    private char[] edgeChar;
    private int[] edgeTo;
    private int[] outHead;       // per state: first output node of the merged chain, or -1
    private int[] outNext;       // per output node: the next node in the chain, or -1
    private Object[] outPayload; // per output node: the payload passed to add()
    private boolean built = false;

    public AhoCorasick() { kids.add(new ArrayList<>()); }

    /** number of keywords added (duplicates counted — every add() fires). */
    public int size() { return keywordCount; }

    /** Add one keyword. The payload is reported by scan() whenever the
     *  keyword occurs in the scanned text (also when it occurs only as a
     *  proper suffix of a longer keyword, via the fail chain). Multiple
     *  keywords may share the same text; all their payloads fire. */
    public void add(String keyword, Object payload) {
        if (built) throw new IllegalStateException("AhoCorasick already built");
        if (keyword == null || keyword.isEmpty()) return;
        int s = 0;
        for (int i = 0; i < keyword.length(); i++) {
            s = childOrAdd(s, keyword.charAt(i));
        }
        endStates.add(s);
        payloads.add(payload);
        keywordCount++;
    }

    private int childOrAdd(int s, char c) {
        ArrayList<long[]> k = kids.get(s);
        for (int i = 0; i < k.size(); i++) {
            if ((char) k.get(i)[0] == c) return (int) k.get(i)[1];
        }
        int ns = states++;
        kids.add(new ArrayList<>());
        k.add(new long[]{ c, ns });
        return ns;
    }

    private int childOf(int s, char c) {
        ArrayList<long[]> k = kids.get(s);
        for (int i = 0; i < k.size(); i++) {
            if ((char) k.get(i)[0] == c) return (int) k.get(i)[1];
        }
        return -1;
    }

    /** Freeze the automaton: compute fail links + merged output chains,
     *  flatten the trie into CSR arrays, free the build-time structures.
     *  add() may not be called afterwards; scan() only works after this. */
    public void build() {
        if (built) return;
        int n = states;
        fail = new int[n];
        outHead = new int[n];
        Arrays.fill(outHead, -1);

        // own output chains: every keyword appends one node to its end
        // state's chain (duplicates on the same keyword share the state,
        // each payload gets its own node)
        int k = payloads.size();
        outPayload = payloads.toArray();
        outNext = new int[k];
        Arrays.fill(outNext, -1);
        int[] ownTail = new int[n];
        Arrays.fill(ownTail, -1);
        for (int j = 0; j < k; j++) {
            int st = endStates.get(j);
            if (ownTail[st] < 0) outHead[st] = j;
            else outNext[ownTail[st]] = j;
            ownTail[st] = j;
        }

        // pass 1 — BFS, fail links only. Depth-1 states fail to the root.
        // bfsOrder records the visit order: fail[t] sits at a STRICTLY
        // smaller depth than t, so iterating pass 2 in this order merges
        // every state's fail chain after that chain is itself final.
        int[] bfsOrder = new int[n];
        int oi = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (long[] e : kids.get(0)) {
            int t = (int) e[1];
            fail[t] = 0;
            q.add(t);
        }
        while (!q.isEmpty()) {
            int s = q.poll();
            bfsOrder[oi++] = s;
            for (long[] e : kids.get(s)) {
                char c = (char) e[0];
                int t = (int) e[1];
                int f = fail[s];
                while (f != 0 && childOf(f, c) < 0) f = fail[f];
                int ft = childOf(f, c);
                if (ft < 0 || ft == t) ft = 0;   // root fallback (ft<0); self-link cannot happen past depth 1
                fail[t] = ft;
                q.add(t);
            }
        }

        // pass 2 — merge the fail chains' outputs, in BFS order. A state
        // with its own outputs APPENDS its fail's chain at the tail (the
        // chain is shared by reference, never copied); a state without
        // own outputs INHERITS its fail's head outright.
        for (int i = 0; i < oi; i++) {
            int s = bfsOrder[i];
            int h = outHead[fail[s]];
            if (h < 0) continue;
            if (outHead[s] < 0) outHead[s] = h;
            else outNext[ownTail[s]] = h;
        }

        // flatten to CSR, each state's edges sorted by char for binary search
        rowStart = new int[n + 1];
        for (int s = 0; s < n; s++) rowStart[s + 1] = rowStart[s] + kids.get(s).size();
        edgeChar = new char[rowStart[n]];
        edgeTo = new int[rowStart[n]];
        for (int s = 0; s < n; s++) {
            int base = rowStart[s];
            ArrayList<long[]> row = kids.get(s);
            for (int i = 0; i < row.size(); i++) {
                edgeChar[base + i] = (char) row.get(i)[0];
                edgeTo[base + i] = (int) row.get(i)[1];
            }
            for (int i = 1; i < row.size(); i++) {   // insertion sort (rows are tiny)
                char c = edgeChar[base + i];
                int v = edgeTo[base + i];
                int j = base + i - 1;
                while (j >= base && edgeChar[j] > c) {
                    edgeChar[j + 1] = edgeChar[j];
                    edgeTo[j + 1] = edgeTo[j];
                    j--;
                }
                edgeChar[j + 1] = c;
                edgeTo[j + 1] = v;
            }
        }

        built = true;
        kids.clear();
        kids.trimToSize();
        endStates.clear();
        endStates.trimToSize();
        payloads.clear();
        payloads.trimToSize();
    }

    /** Scan [text] left to right, reporting every keyword payload whose
     *  keyword ends at the current position (including occurrences that
     *  end inside a longer keyword — the fail-chain outputs). Stops early
     *  when the sink returns false. Allocates nothing. */
    public void scan(String text, Sink sink) {
        if (!built || text == null) return;
        int len = text.length();
        int s = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            for (;;) {
                int t = child(s, c);
                if (t >= 0) { s = t; break; }
                if (s == 0) break;      // root: stay, consume the char
                s = fail[s];            // follow the fail link, retry
            }
            for (int node = outHead[s]; node != -1; node = outNext[node]) {
                if (!sink.accept(outPayload[node])) return;
            }
        }
    }

    /** binary-search state s's transition on c, or -1 */
    private int child(int s, char c) {
        int lo = rowStart[s], hi = rowStart[s + 1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            char mc = edgeChar[mid];
            if (mc == c) return edgeTo[mid];
            if (mc < c) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** diagnostics: number of trie states (meaningful after build) */
    public int states() { return states; }
}
