package com.bolt.browser.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper


import android.print.PrintManager
import android.print.PrintAttributes
import android.webkit.WebView
import java.io.File
/**
 * v1.3.91 — THE SAVE-PAGE PASS (the field report: "add options to
 * save pages as pdf, print, html etc. By maintaining formatting"). New
 * code → Kotlin per the README language rule.
 *
 * THREE OUTPUTS, one entry (the "Save page" sheet):
 *  - PDF: v1.3.92 — THE VECTOR SWITCH (the field: "saving a pdf means I
 *    want to save as a vector file, not just a screenshot wrapped in pdf
 *    file. The save as pdf still does that"). Save-as-PDF now opens the
 *    SYSTEM print preview (PrintManager + the WebView's own print
 *    document adapter), where "Save as PDF" produces Chromium's REAL
 *    vector rendering — selectable text, sharp at any zoom, a fraction of
 *    the size. This is the same route Chrome mobile uses; the dialogless
 *    print-to-file path stays impossible (the adapter's callback ctors
 *    are package-private in android.print — researched v1.3.91), so the
 *    preview's one extra tap is the honest price of a true vector file.
 *    The v1.3.91 strip-stitch output (the page as pixels, tiled into A4)
 *    remains available as the legacy path if printing is unavailable.
 *  - PRINT: the same print adapter through PrintManager — the SYSTEM
 *    print preview, where physical printers live (now the same tap as
 *    PDF; kept as its own row for discoverability).
 *  - HTML: a single-file archive the page builds ITSELF (the DOM clone
 *    with every canvas-copyable image inlined as a data: URL; remote
 *    <link> stylesheets stay as links — inlining cross-origin CSS by
 *    fetch dies on CORS half the time and would lose the sheet).
 *
 * The legacy MHT archive ("Save page offline") stays as the fourth sheet
 * option — it is the offline-reader format, this pass is the share format.
 */
object PageSave {

    /** result of an output; [uri] set on success. */
    class Done(@JvmField val ok: Boolean,
            @JvmField val uri: android.net.Uri?, @JvmField val msg: String)

    /** the completion callback (a fun interface so Java callers can use
     *  lambdas — a plain (Done) -> Unit maps to Function1 and refuses
     *  Java statement lambdas). */
    fun interface Callback { fun onDone(res: Done) }

    // ---------------------------------------------------------------- pdf

    /** v1.3.92 — Save the page as a VECTOR PDF through the system print
     *  preview (Chromium's own print rendering; the user taps "Save as
     *  PDF" there). Returns false only when the print service itself is
     *  unavailable — the caller can then fall back to the stitch engine.
     *  v1.3.95 — THE HONEST PROBE: a device without the print spooler
     *  used to fall through to the raster stitch SILENTLY (the v1.3.94
     *  field: "the save as pdf still saves as a raster image of the
     *  screen"), so the failure is now (a) probed (spooler package),
     *  (b) named in diagnostics, and (c) surfaced by the CALLER as an
     *  explicit choice (text-vector PDF vs image PDF) — never a silent
     *  downgrade. */
    @JvmStatic
    fun savePdfVector(a: Activity, w: WebView, title: String): Boolean {
        return try {
            // v1.3.96 — a page STILL LOADING prints partial content ("does
            // not cover the whole webpage"): Chromium prints what is laid
            // out at preview time. Name the risk instead of shipping a
            // silently truncated file; the user can wait and re-save.
            try {
                if (w.progress < 100)
                    Fx.toast(a, "Page is still loading — the PDF may miss content")
            } catch (_: Throwable) {}
            val pm = a.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: throw IllegalStateException("no PrintManager")
            try {
                a.packageManager.getPackageInfo("com.android.printspooler", 0)
            } catch (t: Throwable) {
                com.bolt.browser.util.Diag.log(a, "print spooler absent — vector preview cannot open")
                throw IllegalStateException("print spooler absent")
            }
            pm.print(title, w.createPrintDocumentAdapter(title),
                    PrintAttributes.Builder().build())
            true
        } catch (t: Throwable) {
            try {
                com.bolt.browser.util.Diag.log(a, "print open failed: "
                        + t.javaClass.simpleName + " "
                        + (t.message ?: "").take(60))
            } catch (_: Throwable) {}
            false
        }
    }

    /** the legacy strip-stitch fallback (pixels tiled into A4) — now an
     *  EXPLICIT user choice (see savePdfVector), never a silent downgrade.
     *  See [AiExport.capturePdf].
     *  v1.3.96 — THE FLATTEN WRAP: pages that scroll inside a fixed-height
     *  div (chat/SPA layouts) never expose their full height to the stitch
     *  walk, so the image PDF "did not cover the whole webpage". The big
     *  inner scrollers are reversibly opened BEFORE the capture (inline
     *  CSS through the CSSOM — CSP-proof, data-attribute-reversible) and
     *  restored the moment it finishes. */
    @JvmStatic
    fun savePdf(a: Activity, w: WebView, title: String, onDone: Callback) {
        try {
            w.evaluateJavascript(AiExport.flattenJs()) { _ ->
                try {
                    AiExport.capture(a, w, true, "Bolt-page-") { res ->
                        try {
                            w.evaluateJavascript(AiExport.flatRestoreJs(), null)
                        } catch (_: Throwable) {}
                        onDone.onDone(Done(res.ok, res.uri,
                                if (res.ok) res.msg else "Page is not ready"))
                    }
                } catch (t: Throwable) {
                    try { w.evaluateJavascript(AiExport.flatRestoreJs(), null) }
                    catch (_: Throwable) {}
                    onDone.onDone(Done(false, null, "Save failed"))
                }
            }
        } catch (t: Throwable) {
            onDone.onDone(Done(false, null, "Save failed"))
        }
    }

    /** v1.3.95 — THE TEXT-PDF FALLBACK: when the system print screen
     *  cannot open (spooler absent / print service refuses — the exact
     *  v1.3.94 field case), the page's READABLE content still becomes a
     *  TRUE vector PDF: the reader-style walk extracts
     *  heading/paragraph/list/quote/code blocks and [VectorPdf] renders
     *  them with selectable text at A4. Honestly labeled: it is the
     *  page's CONTENT, not its CSS layout. */
    @JvmStatic
    fun saveTextPdf(a: Activity, w: WebView, title: String, onDone: Callback) {
        try {
            w.evaluateJavascript(textBlocksJs()) { res ->
                try {
                    val json = unquote(res)
                    val blocks = VectorPdf.parseBlocks(json)
                    if (blocks.isEmpty()) {
                        onDone.onDone(Done(false, null, "No readable text found on this page"))
                        return@evaluateJavascript
                    }
                    val prefix = "Bolt-" + cleanTitle(title) + "-"
                    VectorPdf.save(a, blocks, prefix, title) { ok, uri, msg ->
                        onDone.onDone(Done(ok, uri, msg))
                    }
                } catch (t: Throwable) {
                    onDone.onDone(Done(false, null, "Save failed"))
                }
            }
        } catch (t: Throwable) {
            onDone.onDone(Done(false, null, "Save failed"))
        }
    }

    /** filename-safe title fragment for the text-PDF prefix. */
    private fun cleanTitle(title: String): String =
            title.replace(Regex("[^A-Za-z0-9._ -]"), "").trim()
                    .replace(Regex("\\s+"), "-").take(40).ifBlank { "page" }

    /** v1.3.95 — the reader-style block walk that feeds the text-PDF
     *  fallback (the ReaderMode container law: article/main/[role=main]
     *  candidates scored by paragraph text, junk classes penalized;
     *  then h1..h6 / p / li / blockquote / pre blocks, capped, in
     *  document order, as VectorPdf's {k, t} JSON). */
    private fun textBlocksJs(): String =
            "(function(){try{" +
            "function txt(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "var cand=[document.body];" +
            "try{cand=cand.concat(Array.prototype.slice.call(document.querySelectorAll(" +
            "'article,main,[role=main],.article,.post,.content,#content,.entry,.story')));}catch(e){}" +
            "var best=document.body,bestScore=-1;" +
            "for(var i=0;i<cand.length;i++){var el=cand[i];if(!el)continue;" +
            "var ps=el.querySelectorAll('p');var len=0;" +
            "for(var j=0;j<ps.length;j++)len+=txt(ps[j].textContent).length;" +
            "var cls=((el.className||'')+' '+(el.id||'')).toLowerCase();" +
            "var bad=/(comment|share|footer|header|sidebar|nav|menu|advert|promo|related|widget)/.test(cls)?80:0;" +
            "var score=len-bad*10;" +
            "if(score>bestScore){bestScore=score;best=el;}}" +
            // CLONE, never strip the live page (the ReaderMode law)
            "var clone=best.cloneNode(true);" +
            // v1.3.96 — THE PARSE FIX: this selector was shipped BROKEN in
            // v1.3.95 (the first literal ended with a JS quote-plus and the
            // second opened with `.ad` — an invalid JS token, the whole
            // IIFE failed to PARSE, and every Text-PDF save returned
            // «No readable text found» — the Save-as-pdf-fails report).
            // One continuous selector string now, node-checked in the gate.
            "try{var k=clone.querySelectorAll('script,style,noscript,iframe,svg,form,button,aside,nav,footer,header," +
            ".ad,.ads,.advert,.share,.related,.comments,.comment,.sidebar,.promo,[role=navigation],[aria-hidden=true]');" +
            "for(var i2=0;i2<k.length;i2++){var p=k[i2].parentNode;if(p)p.removeChild(k[i2]);}}catch(e){}" +
            "var nodes=clone.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th,dd,dt,figcaption');" +
            "var out=[],last='';" +
            // v1.3.96 — the walk is the WHOLE page's content: tables and
            // definition lists join (td/th/dd/dt/figcaption), the block cap
            // doubles (900->2500) and pre doubles (6000->12000) — the
            // «does not cover the whole webpage» report on text-heavy pages
            "for(var n=0;n<nodes.length&&out.length<2500;n++){" +
            "var el2=nodes[n],tag=el2.tagName.toLowerCase(),t;" +
            "if(tag==='pre'){t=(el2.textContent||'').replace(/\\s+$/,'');" +
            "if(t.length>12000)t=t.substring(0,12000);}" +
            "else t=txt(el2.textContent);" +
            "if(!t)continue;" +
            "if(tag==='p'){try{if(el2.closest('li,td,th,dd'))continue;}catch(e){}}" +
            "if(tag!=='pre'&&t===last)continue;" +
            "last=t;" +
            "out.push({k:tag,t:t});}" +
            "return JSON.stringify(out);" +
            "}catch(e){return '[]'}})()"

    /** the system print preview (physical printers + the OS save-as-PDF). */
    @JvmStatic
    fun print(a: Activity, w: WebView, title: String) {
        try {
            val pm = a.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(title, w.createPrintDocumentAdapter(title),
                    PrintAttributes.Builder().build())
        } catch (t: Throwable) {
            Fx.toast(a, "Print is not available")
        }
    }

    // ---------------------------------------------------------------- html

    /**
     * Build the single-file HTML (the page serializes itself — see
     * [htmlJs]) and write it to Downloads. Images inline as data: URLs
     * under a byte budget so a photo-heavy page cannot OOM the bridge;
     * what the budget skips stays as its original URL (the file still
     * renders, those images load from the network).
     */
    @JvmStatic
    fun saveHtml(a: Activity, w: WebView, title: String, onDone: Callback) {
        try {
            w.evaluateJavascript(htmlJs()) { res ->
                try {
                    if (res == null || res == "null" || res.length < 40) {
                        onDone.onDone(Done(false, null, "Page is not ready yet"))
                        return@evaluateJavascript
                    }
                    // the bridge hands the JSON-quoted string; unquote it
                    val html = unquote(res)
                    if (html.isEmpty()) {
                        onDone.onDone(Done(false, null, "Page is not ready yet"))
                        return@evaluateJavascript
                    }
                    Thread({
                        val name = fileName(title, "html")
                        val uri = downloadsTarget(a, name, "text/html")
                        var ok = false
                        if (uri != null) {
                            try {
                                val os = a.contentResolver.openOutputStream(uri)
                                // v1.3.93 — a null stream must NOT count as a
                                // write (the old ?.use skipped silently and ok
                                // became true over a 0-byte file)
                                if (os != null) {
                                    os.use { it.write(html.toByteArray(Charsets.UTF_8)) }
                                    ok = true
                                }
                            } catch (_: Throwable) {}
                            if (!ok) {
                                // no 0-byte orphan row stays in Downloads
                                try { a.contentResolver.delete(uri, null, null) }
                                catch (_: Throwable) {}
                            }
                        }
                        val done = if (ok) Done(true, uri, "Saved to Downloads")
                                else Done(false, null, "Save failed")
                        Handler(Looper.getMainLooper()).post { onDone.onDone(done) }
                    }, "bolt-savehtml").start()
                } catch (t: Throwable) {
                    onDone.onDone(Done(false, null, "Save failed"))
                }
            }
        } catch (t: Throwable) {
            onDone.onDone(Done(false, null, "Save failed"))
        }
    }

    /**
     * THE SELF-SERIALIZER. Clones the document, strips the running
     * scripts (a saved page must not execute anything on open — the
     * formatting is the point), inlines every image that can become a
     * data: URL within the budget (through the CANVAS of the LIVE image —
     * works for blob:, lazy-loaded and CORS-failed srcs where a plain
     * re-fetch would die), and returns "<!DOCTYPE html>" + the outer
     * HTML. Runs fully in the page; the live DOM is never touched.
     */
    private fun htmlJs(): String =
            "(function(){try{" +
            "var BUDGET=24*1024*1024,per=6*1024*1024,spent=[0];" +
            "var clone=document.documentElement.cloneNode(true);" +
            // no scripts in the archive (formatting, not behavior)
            "try{var sc=clone.querySelectorAll('script,noscript');for(var i=0;i<sc.length;i++)sc[i].remove();}catch(x){}" +
            // live image -> clone image, by document order on both sides
            "function inline2(live,img){try{" +
            "var src=img.getAttribute('src');if(!src||src.indexOf('data:')===0)return;" +
            "if(spent[0]+per>BUDGET)return;" +
            "var c=document.createElement('canvas');" +
            "var nw=live.naturalWidth||live.width||0,nh=live.naturalHeight||live.height||0;" +
            "if(!nw||!nh||nw*nh>16e6)return;" +
            "c.width=nw;c.height=nh;" +
            "c.getContext('2d').drawImage(live,0,0);" +
            "var u=c.toDataURL('image/png');" +
            "if(u&&u.length>60&&u.length<per){spent[0]+=u.length;img.setAttribute('src',u);img.removeAttribute('srcset');}" +
            "}catch(x){}}" +
            "var imgs=document.querySelectorAll('img');" +
            "var clImgs=clone.querySelectorAll('img');" +
            "for(var m=0;m<clImgs.length&&m<imgs.length;m++){try{inline2(imgs[m],clImgs[m]);}catch(x){}}" +
            "return '<!DOCTYPE html>'+clone.outerHTML;" +
            "}catch(e){return ''}})()"

    // ---------------------------------------------------------------- shared

    private fun unquote(s: String): String = try {
        org.json.JSONTokener(s).nextValue() as? String ?: ""
    } catch (t: Throwable) {
        s.removePrefix("\"").removeSuffix("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
    }

    /** the Downloads target for [name] (MediaStore 29+, the public dir below). */
    private fun downloadsTarget(c: Context, name: String, mime: String): android.net.Uri? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                c.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dir != null && !dir.exists()) dir.mkdirs()
                android.net.Uri.fromFile(File(dir, name))
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** "Bolt-<title>-<hhmmss>.<ext>" — the AI exporter's naming law. */
    private fun fileName(title: String, ext: String): String {
        val clean = (title.ifBlank { "page" })
                .replace(Regex("[^A-Za-z0-9._ -]"), "")
                .trim().replace(Regex("\\s+"), "-")
                .take(40).ifBlank { "page" }
        val t = System.currentTimeMillis() % 86400000
        return "Bolt-$clean-$t.$ext"
    }
}
