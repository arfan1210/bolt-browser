package com.bolt.browser.util

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.bolt.browser.R
import com.bolt.browser.media.MediaControlService

/**
 * v1.3.65 — THE IMPORTED WHEEL: the video-only PiP engine.
 *
 * The v1.3.64 report: "the pip currently is clearly struggling to identify
 * the media from the ui background ... check out other online sources and
 * import a working pip instead of trying to fix this broken version". Two
 * proven open-source designs were imported and matched to Bolt's interface:
 *
 * 1. THE PAGE TAKEOVER (from the "Maximize Video" userscript, greasyfork
 *    4870 v12 — ~238 field reports, runs on every site including BOTH
 *    YouTube layouts; technique re-implemented, not copied verbatim):
 *    Bolt's v1.3.62–64 pins styled the BARE `<video>` with
 *    `position:fixed` — but a fixed box resolves against ANY ancestor
 *    carrying a `transform` / `filter` / `perspective` / `contain` /
 *    backdrop-filter (CSS containing-block rules), and YouTube's player
 *    tree carries them after a fullscreen exit + resize churn. The
 *    "pinned" video therefore rendered inside the ANCESTOR's coordinate
 *    box — exactly the field symptom "only the top half of the video with
 *    the rest being the youtube search bar". The proven takeover is
 *    THREE CSS layers (class names `__boltpv*`):
 *      html + body      → overflow:hidden (no scroll, no scrollbars)
 *      EVERY ancestor   → transform:none / contain:none / filter:none /
 *                         perspective:none / z-index:auto /
 *                         overflow:visible / transition:none, and
 *                         fixed-position ancestors demoted to absolute —
 *                         THIS is what makes `position:fixed` mean THE
 *                         VIEWPORT again
 *      the PLAYER       → fixed/top-0/left-0/100vw/100vh + max/min/margin/
 *                         padding/border resets + z-max + #000 background
 *                         + `object-fit:contain` on its videos, plus two
 *                         1px black edge strips covering rounding gaps
 *    The pin target is the PLAYER the video lives in (walk up while the
 *    ancestor's box tracks the video's, ±15px — the userscript's
 *    heuristic), falling back to the bare video when no container
 *    tracks it. The re-pin cycle is FLICKER-FREE: when the currently
 *    pinned element still contains the best video, nothing is touched
 *    (the 900 ms watchdog beat must not relayout the page), and the live
 *    pin is TRACKED (window.__boltpvpEl) so a detached player's leftover
 *    class cannot survive a re-attachment over the new pin.
 *
 * 2. THE WINDOW + CONTROLS (from Google's canonical PiP sample,
 *    googlearchive/android-PictureInPicture): `PictureInPictureParams`
 *    with the VIDEO's own aspect ratio (the whole video edge-to-edge —
 *    v1.3.63 inherited the screen aspect and letterboxed 20% away) and
 *    `setActions(RemoteAction)` — the pre-Android-12 media-control
 *    mechanism (the actions render in the tap-menu on 8–11 and in the
 *    window's button row on 12+; params stay updatable while in PiP).
 *    The user asked for exactly: close (the system X), fullscreen, and
 *    pause/play. The play/pause action rides the notification's own
 *    proven path — a PendingIntent to MediaControlService with
 *    CMD_TOGGLE (audio focus + un-conceal + renderer wake all live
 *    there); only its ICON follows the known playing state. The
 *    fullscreen action launches the task's own activity
 *    (NEW_TASK | REORDER_TO_FRONT) — the system dismisses PiP and the
 *    browser comes forward. RemoteAction icons are rendered by SYSTEMUI
 *    against ITS theme — they must be self-colored (lesson 23), hence
 *    the dedicated `ic_ntf_expand` joins the `ic_ntf_*` family.
 */
object PipVideo {

    // ------------------------------------------------------------ the pin

    /**
     * The takeover JS. Returns "<code>:<aspect>" where code is
     * 1 = a PLAYER container was pinned, 2 = the bare video was pinned,
     * 3 = the existing pin still holds the best video (no-op beat),
     * 0 = nothing pinnable found; aspect = videoWidth/videoHeight of the
     * chosen video (0 when unknown). Strict mode (the wedged-enter
     * fullscreen rescue) pins only a PLAYING video; PiP mode also accepts
     * a paused video WITH a frame (the site often pauses in the same
     * breath it exits fullscreen — v1.3.64's two-tier selector, kept).
     *
     * v1.3.66 — the pip literal also ARMS the in-page PiP sentinel
     * (window.__boltpip=1, disarmed by unpinJs): MediaHook's pipFight()
     * uses it to fight the mobile player's pause-on-play reversal while
     * the floating window is up (see MediaHook).
     *
     * v1.3.67 — the arming also stamps window.__boltpipAt ONCE per session
     * (the `||` keeps the FIRST stamp across the 900 ms watchdog beats —
     * a per-beat restamp would keep the exit gate's entry window alive
     * forever). MediaHook's EXIT GATE reads it: the page's automatic
     * exitFullscreen at the shrunk viewport (the mobile player's ~260 ms
     * reaction, the v1.3.62 measurement) is swallowed inside the first
     * 2.5 s of the session, so the custom view STAYS UP and the mobile
     * PiP takes the desktop configuration (which works perfectly).
     *
     * v1.3.68 — the stamp moment also resets window.__boltPipG (the exit
     * gate's GESTURE escape, once per session): the v1.3.67 field log
     * showed the player RETRYING exitFullscreen at +5.7 s/+15.2 s — far
     * past the old 2.5 s entry window — so the gate now covers the whole
     * armed session and only a REAL page gesture (touch/click/keydown,
     * MediaHook.onGesture) re-opens it. Android 10 PiP never gestures the
     * page; interactive surfaces (12+ expanded PiP) keep their exit
     * button. unpinJs clears the flag with the disarm.
     *
     * v1.3.69 — THE RESYNC RESIZE: the first arm also dispatches ONE
     * synthetic window resize marked __boltRS (MediaHook's pipMuted lets
     * exactly that marker through the armed mutes). The player's own
     * resize handler gY (player build a5c2aafe, base.js:5890) is what
     * latched its pictureInPicture premise — outerHeight*outerWidth under
     * a third of the screen area reads as "browser PiP window" — and the
     * bY progress-tick poll then pauses on EVERY tick while the premise
     * holds (the whole v1.3.66-68 stutter war). MediaHook's outer-size
     * spoof (armed in the same breath as __boltpip, an instant before
     * this dispatch) makes outerWidth/outerHeight report the screen dims —
     * so this ONE delivered resize re-runs gY against the spoofed size
     * and UN-latches the premise. Without it the flag would persist until
     * the next real resize, which the armed mutes swallow. Only at the
     * FIRST arm of a session (the __boltpipAt guard): the 900 ms beat
     * must not re-deliver resizes.
     */
    @JvmStatic
    fun pinJs(pipMode: Boolean): String {
        val selector = if (pipMode) {
            // two-tier: largest playing, else largest framed; then ARM the
            // in-page PiP sentinel for MediaHook's pipFight + pause gate
            // (disarmed by unpinJs — outside PiP the flag is always 0).
            // __boltpipAt is stamped ONCE and the gesture escape reset at
            // that same first moment: the beat re-runs this literal every
            // 900 ms and a fresh stamp would extend the exit gate for the
            // whole session, while a per-beat gesture reset would eat a
            // real tap on interactive surfaces (v1.3.67/v1.3.68)
            "var best=null,ba=-1,fb=null,fba=-1;" +
                "for(var i=0;i<vs.length;i++){var v=vs[i];" +
                "if(!v.videoWidth)continue;" +
                "var a=v.videoWidth*v.videoHeight;" +
                "if(!(v.paused||v.ended)){if(a>ba){ba=a;best=v;}}" +
                "if(a>fba){fba=a;fb=v;}}" +
                "if(!best)best=fb;" +
                "if(!best)return '0:0';" +
                "try{window.__boltpip=1;" +
                "if(!window.__boltpipAt){window.__boltpipAt=Date.now();" +
                "window.__boltPipG=0;" +
                // v1.3.69 — ONE marked resync resize at the first arm: gY
                // re-reads the (now spoofed) window size and un-latches the
                // pictureInPicture premise; pipMuted's __boltRS exemption
                // is the only reason this gets through the armed mutes
                "try{var __rse=new Event('resize');__rse.__boltRS=1;" +
                "window.dispatchEvent(__rse);}catch(x){}" +
                "}}catch(x){};"
        } else {
            // strict: playing only (a paused page must not be re-laid out)
            "var best=null,ba=-1;" +
                "for(var i=0;i<vs.length;i++){var v=vs[i];" +
                "if(v.paused||v.ended||!v.videoWidth)continue;" +
                "var a=v.videoWidth*v.videoHeight;if(a>ba){ba=a;best=v;}}" +
                "if(!best)return '0:0';"
        }
        return "(function(){try{" +
            "var vs=document.querySelectorAll('video');" +
            selector +
            // flicker-free beat: the live pin still holding the best
            // video is already correct — touching the DOM here would
            // relayout the page every 900 ms
            "var cur=document.querySelector('.__boltpvp');" +
            "if(cur&&(cur===best||cur.contains(best))" +
            "&&document.getElementById('__boltpv'))" +
            "return '3:'+(best.videoWidth/Math.max(1,best.videoHeight));" +
            // full re-pin cycle: strip every previous pin first (player
            // DOM re-creation, ad/main-video switches — exactly ONE pin
            // may ever be live), incl. the legacy v1.3.64 class
            unpinBody() +
            // player resolution (v1.3.66): a KNOWN-PLAYER fast path first —
            // the v1.3.65 field log decoded the mobile failure: 'fs pip pin=1
            // player=0' on m.youtube.com vs player=1 on desktop. The ±15px
            // box-tracking walk fails on the mobile site (the video's box
            // legitimately differs from its container's mid-relayout / by
            // letterboxing), so the BARE video got pinned — and the mobile
            // player, whose box then COLLAPSES (its fixed child left the
            // flow), reverses every play through its own size/visibility
            // logic (the four-release "I press play, it stops instantly"
            // mobile report). Desktop's walk already lands on
            // .html5-video-player; matching the known player class/id
            // directly pins the SAME element there and rescues mobile.
            // Non-YouTube pages fall through to the box-tracking walk.
            "var pl=best;" +
            "try{var kp=best.closest?best.closest(" +
            "'.html5-video-player,#movie_player,ytm-video-player,ytd-player'):null;" +
            "if(kp&&kp!==document.body&&kp!==document.documentElement" +
            "&&kp!==best&&kp.contains(best))pl=kp;}catch(x){}" +
            "if(pl===best){var dep=0;" +
            "while(dep<12){var p=pl.parentNode;" +
            "if(!p||!p.getBoundingClientRect||p===document.body" +
            "||p===document.documentElement)break;" +
            "if(Math.abs(p.offsetWidth-best.offsetWidth)<15" +
            "&&Math.abs(p.offsetHeight-best.offsetHeight)<15){pl=p;dep++;}else break;}}" +
            // the three-layer takeover CSS
            "if(!document.getElementById('__boltpv')){" +
            "var s=document.createElement('style');s.id='__boltpv';" +
            "s.textContent=" +
            "\"html.__boltpvh,html.__boltpvh body{overflow:hidden!important;}\"" +
            "+\".__boltpva{overflow:visible!important;z-index:auto!important;" +
            "transform:none!important;-webkit-transform-style:flat!important;" +
            "transition:none!important;contain:none!important;filter:none!important;" +
            "backdrop-filter:none!important;perspective:none!important;}\"" +
            "+\".__boltpvaf{position:absolute!important;}\"" +
            "+\".__boltpvp{position:fixed!important;top:0!important;left:0!important;" +
            "width:100vw!important;height:100vh!important;max-width:none!important;" +
            "max-height:none!important;min-width:0!important;min-height:0!important;" +
            "margin:0!important;padding:0!important;border:none!important;" +
            "z-index:2147483647!important;background-color:#000!important;" +
            "transform:none!important;}\"" +
            "+\".__boltpvp video{position:relative!important;left:0!important;" +
            "top:0!important;width:100%!important;height:100%!important;" +
            "object-fit:contain!important;transform:none!important;}\"" +
            "+\".__boltpve{position:fixed!important;top:0!important;bottom:0!important;" +
            "width:1px!important;height:100vh!important;" +
            "z-index:2147483647!important;background:#000!important;}\";" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "document.documentElement.classList.add('__boltpvh');" +
            // every ancestor between the player and the body gets the
            // neutralizer (fixed ones additionally demoted to absolute)
            "var node=pl.parentNode;" +
            "while(node&&node!==document.body&&node!==document.documentElement){" +
            "if(node.classList){node.classList.add('__boltpva');" +
            "try{if(getComputedStyle(node).position==='fixed')" +
            "node.classList.add('__boltpvaf');}catch(x){}}" +
            "node=node.parentNode;}" +
            "pl.classList.add('__boltpvp');" +
            // track the pin for the flicker-free beat AND so a detached
            // player's leftover class cannot survive re-attachment
            "try{window.__boltpvpEl=pl;}catch(x){}" +
            // the 1px edge strips (sub-pixel rounding gaps read as a
            // hairline of page UI at the window's edges)
            "if(!document.getElementById('__boltpvl')){" +
            "var l=document.createElement('div');l.id='__boltpvl';" +
            "l.className='__boltpve';l.style.left='0';" +
            "document.documentElement.appendChild(l);}" +
            "if(!document.getElementById('__boltpvr')){" +
            "var r=document.createElement('div');r.id='__boltpvr';" +
            "r.className='__boltpve';r.style.right='0';" +
            "document.documentElement.appendChild(r);}" +
            "var rv=(pl===best)?2:1;" +
            "return rv+':'+(best.videoWidth/Math.max(1,best.videoHeight));" +
            "}catch(e){return '0:0'}})()"
    }

    /** The full restore: every class off, the strips and the style
     *  element removed (also strips the legacy v1.3.64 `__boltfsf`
     *  pin — a zombie upgraded mid-session must never stay pinned).
     *
     *  v1.3.67 — THE RESYNC. A PiP session muted event deliveries to the
     *  page (fullscreenchange while armed; resize/orientationchange after
     *  the in-PiP custom-view death, `__boltpipx`) so the mobile player's
     *  state machine never pivoted at the shrunk viewport. The disarm
     *  must hand the page ONE resync chance: a synthetic resize +
     *  fullscreenchange, whose handlers re-read the LIVE DOM state
     *  (element sizes, document.fullscreenElement) and land on the truth
     *  — stale "still fullscreen" UI self-corrects on the next tap
     *  instead of locking. Fired ONLY when a PiP session was actually
     *  armed (wasPip): the strict wedged-enter unpin never armed anything
     *  and a normal fullscreen exit must not see a second event. */
    @JvmStatic
    fun unpinJs(): String {
        return "(function(){try{" +
            // v1.3.66 — disarm the in-page PiP sentinel HERE, not in
            // unpinBody(): the pin's own re-pin cycle INLINES unpinBody()
            // (strip every previous pin before applying the new one) — a
            // disarm there would cancel the selector's arming on every
            // full pin and the sentinel would never be armed (the mock
            // suite caught exactly this ordering bug)
            "var wasPip=false;try{wasPip=!!window.__boltpip}catch(x){};" +
            "try{window.__boltpip=0;window.__boltpipx=0;window.__boltpipAt=0;" +
            "window.__boltPipExits=0;window.__boltPipG=0;}catch(x){};" +
            unpinBody() +
            "var s=document.getElementById('__boltpv');if(s)s.remove();" +
            "if(wasPip){" +
            "try{window.dispatchEvent(new Event('resize'));}catch(x){}" +
            "try{document.dispatchEvent(new Event('fullscreenchange'));}catch(x){}}" +
            "}catch(e){}})()"
    }

    private fun unpinBody(): String {
        return "try{document.documentElement.classList.remove('__boltpvh');" +
            // the TRACKED pin first: a detached player (torn down and
            // maybe re-attached by the site's own JS) is invisible to
            // querySelectorAll — its leftover class must not survive a
            // re-attachment over the NEW pin
            "try{if(window.__boltpvpEl){window.__boltpvpEl.classList.remove('__boltpvp');" +
            "window.__boltpvpEl=null;}}catch(x){}" +
            "var cls=['__boltpva','__boltpvaf','__boltpvp'];" +
            "for(var c=0;c<cls.length;c++){" +
            "var els=document.querySelectorAll('.'+cls[c]);" +
            "for(var i=0;i<els.length;i++)els[i].classList.remove(cls[c]);}" +
            "var l=document.getElementById('__boltpvl');if(l)l.remove();" +
            "var r=document.getElementById('__boltpvr');if(r)r.remove();" +
            "var o=document.querySelectorAll('video.__boltfsf');" +
            "for(var j=0;j<o.length;j++)o[j].classList.remove('__boltfsf');" +
            "var os=document.getElementById('__boltfsf');if(os)os.remove();" +
            "}catch(x){}"
    }

    // ------------------------------------------------- the window + controls

    /**
     * The PiP params: the VIDEO's aspect (whole picture edge-to-edge) +
     * the two RemoteActions (play/pause toggle, fullscreen). `autoEnter`
     * is only honored on API 31+ (the system-side swipe-home trigger);
     * the actions render in the tap-menu on 8–11 and the button row on
     * 12+. Safe to call while already in PiP — the system applies the
     * new actions (the icon follows the known playing state).
     */
    @JvmStatic
    fun params(ctx: Context, aspect: Rational?, playing: Boolean,
               autoEnter: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            val b = PictureInPictureParams.Builder()
            if (aspect != null) {
                try { b.setAspectRatio(aspect) } catch (ignored: Throwable) {}
            }
            try { b.setActions(actions(ctx, playing)) } catch (ignored: Throwable) {}
            if (Build.VERSION.SDK_INT >= 31) {
                try { b.setAutoEnterEnabled(autoEnter) } catch (ignored: Throwable) {}
            }
            b.build()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * [play/pause toggle] + [fullscreen]. Close is the system's own X
     * (the tap-menu on 8–11, the persistent button on 12+).
     *
     * The toggle always sends CMD_TOGGLE — the service's view of the
     * playing state is fresher than any icon we shipped, and its
     * sendCmd path carries the audio-focus / un-conceal / renderer-wake
     * logic the notification's own button rides (a bare page-JS play
     * would regress the "play from notification does nothing" class).
     */
    private fun actions(ctx: Context, playing: Boolean): ArrayList<RemoteAction> {
        val out = ArrayList<RemoteAction>(2)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val label = if (playing) "Pause" else "Play"
        val ci = Intent(ctx, MediaControlService::class.java)
            .putExtra(MediaControlService.CMD, MediaControlService.CMD_TOGGLE)
        out.add(RemoteAction(
                Icon.createWithResource(ctx,
                        if (playing) R.drawable.ic_ntf_pause else R.drawable.ic_ntf_play),
                label, label,
                PendingIntent.getService(ctx, MediaControlService.CMD_TOGGLE, ci, flags)))
        // fullscreen: launching the task's own activity dismisses PiP and
        // brings the browser forward (singleTask + REORDER_TO_FRONT — no
        // recreate, the WebView tree survives)
        val fi = Intent(ctx, ctx.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        out.add(RemoteAction(
                Icon.createWithResource(ctx, R.drawable.ic_ntf_expand),
                "Fullscreen", "Fullscreen",
                PendingIntent.getActivity(ctx, 77, fi, flags)))
        return out
    }

    /** clamp a video aspect into PiP's legal 2.39:1..1:2.39 window. */
    @JvmStatic
    fun clampAspect(aspect: Float): Rational {
        var a = aspect
        if (!(a > 0f) || a.isNaN()) a = 16f / 9f
        if (a > 2.39f) a = 2.39f
        if (a < 1f / 2.39f) a = 1f / 2.39f
        return try {
            Rational(Math.round(a * 100f), 100)
        } catch (t: Throwable) {
            Rational(16, 9)
        }
    }
}
