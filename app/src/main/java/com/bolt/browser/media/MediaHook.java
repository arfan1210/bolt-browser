package com.bolt.browser.media;

import com.bolt.browser.util.Prefs;

/** Builds the JavaScript injected into every page: media detection, vocal EQ,
 *  volume boost, speed, background play, cookie blocker, element inspector.
 *
 *  v1.3.13 media core rebuild ("works on every site, not just YouTube"):
 *  - tracks EVERY <video>/<audio> element via capture-phase media events
 *    (no polling scan for detection; the scan loop stays for the booster EQ);
 *  - reports state changes immediately + 1s position beats to the service;
 *  - spoof layer: document.hidden/visibilityState + hasFocus() + page-level
 *    blur/visibilitychange/pagehide are swallowed ONLY while the page is
 *    REALLY backgrounded, so players never learn the app went away and never
 *    pause. Element-level blur (form fields) is never touched. rAF is
 *    diverted to timers while hidden so rAF-driven players keep buffering.
 *    The old auto-resume watchdog / echo-reversal war is GONE — the page
 *    never pauses in the first place, so nothing needs reversing.
 *  - commands: __boltCmd('toggle'|'play'|'pause'|'stop'|'seek'|'seekTo'|
 *    'speed'|'vol'|'vocal', arg).
 *  This JS is mirrored by scripts/test_media_hook.js (mock-DOM tests). */
public class MediaHook {

    /** Core script injected at onPageStarted + onPageCommitVisible. */
    public static String core(Prefs p) {
        String settings = mediaSettingsJson(p);
        return "(function(){\n"
                + "if(window.__boltCore)return; window.__boltCore=1;\n"
                + "var SET=" + settings + ";\n"
                + "var chains=new WeakMap();\n"
                // foreign audio-claim tracking: patch createMediaElementSource BEFORE
                // any page/script code runs so we can see when SOMEONE ELSE attaches
                // an audio graph to a media element. Bolt's own booster must never
                // steal that route — a stolen route is exactly why a userscript's
                // volume panel silently stops applying changes until a reload.
                + "var foreign=new WeakSet();var boltBusy=false;\n"
                + "try{var __AC=window.AudioContext||window.webkitAudioContext;"
                + "if(__AC&&__AC.prototype&&__AC.prototype.createMediaElementSource&&!__AC.prototype.__boltGuard){"
                + "__AC.prototype.__boltGuard=1;"
                + "var __origCmes=__AC.prototype.createMediaElementSource;"
                + "__AC.prototype.createMediaElementSource=function(el){"
                + "try{if(el&&!boltBusy)foreign.add(el);}catch(x){}"
                + "return __origCmes.apply(this,arguments);};}}catch(e){}\n"
                // ================= v1.3.67 THE PiP SURVIVAL KIT =================
                // The v1.3.66 field log: 'fs pip pin=1 player=1' (the fast
                // path WORKED — the player was pinned) + 'fs pip fight n=1'
                // + the video still pauses instantly on m.youtube.com. The
                // reversal is stronger than the 5-try bound — exactly the
                // v1.3.66 handoff's prediction; this kit is the named next
                // lever, three layers + a decoder, ALL gated on the PiP
                // session flag (window.__boltpip, armed by PipVideo.pinJs,
                // disarmed by unpinJs) so the desktop path (which never
                // pauses inside PiP and whose custom view survives the
                // shrink) is byte-for-byte untouched.
                // ---- LAYER 3a: THE PAUSE DECODER + v1.3.68 THE PAUSE GATE ----
                // While a PiP session is armed, every page-side pause() is
                // recorded with its JS call stack (media events fire from
                // DOM tasks — the caller is already gone by the time any
                // listener runs, so the METHOD is the only interception
                // point). pipFight/pipGate report the recorded stack:
                // 'js=1 st=…' names the pausing module; js=0 with a pause
                // = NO JS caller = a renderer-side pause (a different bug
                // class).
                // v1.3.68 — THE DECODE THAT NAMED THE ENEMY: the v1.3.67
                // field log's 'fs pip fight n=1 js=0 st=Error | at
                // __HME.pause | at g.fZ.pause (player-plasma base.js)'
                // — the stack convicts YouTube's OWN mobile player JS of
                // the pause (the js=0 was a Java-side parse bug, not a
                // renderer pause), AND the user's report ("one word plays,
                // 2 second pause, one word play and repeat") is the pipFight
                // SCHEDULE itself: every try re-plays a word, the player
                // re-pauses within ~1 s, the next try lands after the
                // growing delay. A counter that plays AFTER the pause can
                // never produce continuous audio — only PREVENTING the
                // pause from executing can. So while the session is armed,
                // a NON-USER page-side pause of the ACTIVE element is
                // swallowed ENTIRELY (never reaches the element) and the
                // player's own API is nudged back to playing in the same
                // breath (ytApi playVideo — the state machine resyncs to
                // playing and keeps feeding the MSE buffer; YouTube keeps
                // buffering while it BELIEVES it is paused, so the element
                // never starves). Escapes, all load-bearing: __userPaused
                // (the user's own pause via the window's controls /
                // notification passes through untouched), ended (natural
                // end), !isConnected, this!==active (ad/element switches
                // are not ours to gate), a page gesture <2 s ago (on 12+
                // expanded-PiP surfaces the page keeps its own pause
                // button — a tap must work), and the rate limiter (12
                // swallows inside 3 s = a pathological reversal loop ->
                // 5 s of pass-through so pipFight owns it, then the gate
                // re-arms; the observed mobile cadence of one pause per
                // 0.5–3 s never trips it). The swallow reports 'pipgate'
                // events carrying the SAME decoder payload, so the pauser
                // stays NAMED even with the gate active.
                + "try{var __HME=(typeof HTMLMediaElement!=='undefined')?HTMLMediaElement:(window.HTMLMediaElement);\n"
                + "if(__HME&&__HME.prototype&&typeof __HME.prototype.pause==='function'&&!__HME.prototype.__boltPPG){\n"
                + "__HME.prototype.__boltPPG=1;var __origPause=__HME.prototype.pause;\n"
                + "__HME.prototype.pause=function(){try{if(window.__boltpip){\n"
                + "this.__boltPPS=String((new Error()).stack||'');\n"
                + "if(this===active&&!this.__userPaused&&!this.ended&&this.isConnected"
                + "&&Date.now()-lastGesture>=2000&&!pipGateTripped()){"
                + "pipGateN++;"
                + "if(pipGateN<=3||pipGateN%20===0){try{Bolt.mediaEvent('pipgate',pipEvt(this,pipGateN));}catch(x){}}"
                + "try{ytApi(this,true);}catch(x){}"
                + "return;}}}catch(x){}\n"
                + "return __origPause.apply(this,arguments);};}}catch(e){}\n"
                // ---- v1.3.69 LAYER 0: THE OUTER-SIZE SPOOF (the premise kill) ----
                // The v1.3.68 field log: 'fs pip gate n=1 js=1' AND 'fs pip
                // fight n=2 js=1' with the SAME stack — the handoff's own
                // "rate limiter degraded to the fight" evidence combo — and
                // the user's report a STEADY "2 s plays, 2 s pause" loop,
                // with keepalive pos FROZEN (30/360 s for minutes: the
                // element stopped accumulating time — the stream starved
                // behind a player that believes it is paused). The enemy's
                // OWN CODE (player build a5c2aafe, fetched and read at the
                // stack's own line numbers) convicts a POLL, not a reaction:
                // bY (base.js:5850) is the player's onVideoProgress TICK
                // handler — every progress tick it re-evaluates
                //   O.jn ("html5_picture_in_picture_blocking_ontimeupdate")
                //   && visibility.Zj() (== pictureInPicture)
                //   && O.yb ("uniplayer_block_pip")
                //   && !videoData.backgroundable -> this.pauseVideo()
                // and pictureInPicture is latched by gY (base.js:5890), the
                // window RESIZE handler (registered at base.js:4068):
                //   b = window.outerHeight*window.outerWidth (tiny in PiP)
                //   a = window.screen.width*window.screen.height
                //   b/a < .33 -> "we are in a browser PiP window"
                //   -> visibility.Hu(true)  (+ a direct mediaElement pause
                //      in the non-Tn branch)
                // Android's activity-PiP shrink is INDISTINGUISHABLE from
                // browser document-PiP to that check — the entire v1.3.66-68
                // war (gate swallow -> ytApi resync -> next tick pause ->
                // 12-in-3 s limiter -> 5 s pass-through -> the stutter) was
                // one FALSE PREMISE. THE FIX kills the premise at the source:
                // while the session is armed, window.outerWidth/outerHeight
                // report screen.width/height (ratio 1 — "same window as
                // ever"; the same class of lie as document.hidden=false in
                // background play, and the exact state the page enjoyed
                // before PiP). Only two other outer readers exist in the
                // whole player build (a telemetry collector base.js:990 and
                // a chromeless-template sizer base.js:5796) — both inert
                // here. Installed ONCE per page at boot; the getter
                // delegates to the ORIGINAL accessor while disarmed, so the
                // expand-back sees live sizes again and enter/exit/enter
                // cycles reuse the same install. PipVideo.pinJs then hands
                // the player ONE resync resize (marked __boltRS — pipMuted
                // lets exactly that event through) so gY re-evaluates with
                // the spoofed size and UN-latches the premise; without the
                // resync the already-latched pictureInPicture would persist
                // until the next real resize, which the mutes swallow.
                + "var __boltOuterArm=0;\n"
                + "function pipOuterSpoof(){try{\n"
                + "if(window.__boltOuterArm)return;window.__boltOuterArm=1;\n"
                + "var sw=0,sh=0;"
                + "try{if(window.screen){sw=window.screen.width*1||0;sh=window.screen.height*1||0;}}catch(x){}"
                + "if(!sw||!sh)return;"
                + "var defs=[['outerWidth',sw],['outerHeight',sh]];"
                + "for(var i=0;i<defs.length;i++){(function(name,val){try{"
                + "var orig=null;"
                + "try{var d=Object.getOwnPropertyDescriptor(window,name);if(d&&d.get)orig=d.get;}catch(x){}"
                + "if(!orig){try{if(typeof Window!=='undefined'&&Window.prototype){"
                + "var dp=Object.getOwnPropertyDescriptor(Window.prototype,name);"
                + "if(dp&&dp.get)orig=dp.get;}}catch(x){}}"
                + "var fb=0;try{fb=window[name]*1||val;}catch(x){fb=val;}"
                + "Object.defineProperty(window,name,{configurable:true,"
                + "get:function(){try{if(window.__boltpip)return val;}catch(x){}"
                + "if(orig){try{return orig.call(window);}catch(x){}}"
                + "return fb;}});}catch(x){}})(defs[i][0],defs[i][1]);}\n"
                + "}catch(x){}}\n"
                // ---- LAYER 1: THE EXIT GATE ----
                // The mobile player exits element fullscreen ~260 ms into
                // PiP (the v1.3.62 measurement — a debounced resize
                // reaction at the shrunk viewport; the desktop player
                // ignores the shrink, which is the whole mobile/desktop
                // behavioral split). That exit tears the custom view down
                // and pivots the player's state machine — the pivot that
                // pauses and keeps reversing plays. While the session is
                // armed, the page's exitFullscreen calls are swallowed and
                // counted: the custom view STAYS UP and the mobile PiP
                // session takes the desktop configuration.
                // v1.3.68 — THE WINDOW WAS SIZED FOR ONE REACTION. The
                // v1.3.67 field log: exitgate n=1 (the first exit, ~270 ms
                // in, swallowed) but 'fs hide inpip' still landed at +5.7 s
                // and +15.2 s in the two attempts — the player RE-EVALUATES
                // and RETRIES exitFullscreen long after the old 2.5 s entry
                // window expired. The gate now covers the WHOLE armed
                // session; the time window is replaced by a GESTURE escape:
                // a REAL page touch/click/keydown after arming sets
                // window.__boltPipG (onGesture) and re-opens the gate — on
                // Android 10 PiP the page is never touched (nothing
                // changes there), while any interactive surface (12+
                // expanded PiP) keeps its own exit button. pipJs resets the
                // flag once per session at the arming stamp; unpinJs
                // clears it with the disarm.
                + "function __boltPipEntry(){try{"
                + "if(!window.__boltpip)return false;"
                + "if(window.__boltPipG)return false;"
                + "return true;}catch(x){return false;}}\n"
                + "function __boltGateExit(){try{"
                + "if(__boltPipEntry()){try{window.__boltPipExits=(window.__boltPipExits||0)+1;"
                + "Bolt.mediaEvent('pipexit','{\"n\":'+window.__boltPipExits+'}');}catch(x){}"
                + "return true;}}catch(x){}return false;}\n"
                + "function __boltWrapExit(obj,name){try{var orig=obj[name];"
                + "if(typeof orig!=='function'||obj.__boltPXG)return;"
                + "obj.__boltPXG=1;obj[name]=function(){try{"
                // the swallow must still answer a THENABLE — the real API
                // returns a Promise and pages chain .catch() on it (a bare
                // undefined would throw inside the player's own handler)
                + "if(__boltGateExit())return Promise.resolve();}catch(x){}"
                + "return orig.apply(this,arguments);};}catch(x){}}\n"
                + "try{var __bxOk=false;"
                + "if(typeof Document!=='undefined'&&Document.prototype){"
                + "__boltWrapExit(Document.prototype,'exitFullscreen');"
                + "__boltWrapExit(Document.prototype,'webkitExitFullscreen');"
                + "__bxOk=!!Document.prototype.__boltPXG;}"
                + "if(!__bxOk){__boltWrapExit(document,'exitFullscreen');"
                + "__boltWrapExit(document,'webkitExitFullscreen');}}catch(e){}\n"
                + "var gesture=false;\n"
                // ---- audio chain (vocal boost + volume) ----
                // SET.booster=false disables the in-page booster entirely (no
                // AudioContext, no playbackRate enforcement) so userscripts that
                // attach their own audio graph never collide with ours.
                + "function sameOrigin(v){try{var s=v.currentSrc||v.src;if(!s)return false;"
                + "if(s.indexOf('blob:')===0||s.indexOf('data:')===0)return true;"
                + "return new URL(s,location.href).origin===location.origin;}catch(e){return false;}}\n"
                + "function mkChain(v){if(!SET.booster&&!SET.skip)return null;"
                + "if(foreign.has(v))return null;"
                + "try{var C=window.AudioContext||window.webkitAudioContext;if(!C)return null;"
                + "var ctx=new C();var src;"
                + "boltBusy=true;try{src=ctx.createMediaElementSource(v);}finally{boltBusy=false;}"
                + "var hp=ctx.createBiquadFilter();hp.type='highpass';hp.frequency.value=90;"
                + "var ls=ctx.createBiquadFilter();ls.type='lowshelf';ls.frequency.value=300;"
                + "var p1=ctx.createBiquadFilter();p1.type='peaking';p1.frequency.value=1200;p1.Q.value=0.8;"
                + "var p2=ctx.createBiquadFilter();p2.type='peaking';p2.frequency.value=2200;p2.Q.value=0.9;"
                + "var p3=ctx.createBiquadFilter();p3.type='peaking';p3.frequency.value=3500;p3.Q.value=1.2;"
                + "var gain=ctx.createGain();gain.gain.value=1;"
                + "src.connect(hp);hp.connect(ls);ls.connect(p1);p1.connect(p2);p2.connect(p3);p3.connect(gain);gain.connect(ctx.destination);"
                // v1.3.79 — THE SILENCE SKIPPER'S EAR: an AnalyserNode tapped
                // in parallel (p3 -> an, no destination edge — analysers are
                // sinks). Zero audio effect; the skip loop reads RMS off it.
                + "var an=null;try{an=ctx.createAnalyser();an.fftSize=1024;p3.connect(an);}catch(x){}"
                + "var c={ctx:ctx,gain:gain,hp:hp,eq:[ls,p1,p2,p3],an:an,connected:true};chains.set(v,c);try{skEls.add(v);}catch(x){}applyTo(v);return c;}catch(e){return null;}}\n"
                + "function applyTo(v){if(!SET.booster&&!SET.skip)return;var c=chains.get(v);try{"
                // v1.3.79 — ONE RATE AUTHORITY: base speed x skip factor, so
                // the skipper never fights the speed slider (the skipper
                // only touches v.__boltSk, applyTo computes the product).
                // v1.3.84 — THE FACTOR IS RELATIVE (the field report: "make
                // the skip silence relative, like 4 times of what speed I am
                // watching — 1x base skips at 4x, 1.5x base skips at 6x"):
                // 2.5 -> 4, so silence always rides at base x 4 whatever the
                // slider says.
                // v1.3.87 — THE FACTOR IS A SETTING (the field report: "I
                // think relative 4x is a bit too fast, maybe relative 3x
                // would be better. Even better, give control options …
                // relative 1x-8x"): SET.kf carries it (Prefs skipFactor,
                // default 3, range 1-8, tuned from the media sheet and
                // Settings → Media).
                + "var want=(SET.speed||1)*(v.__boltSk?(SET.kf||3):1);"
                + "if(v.playbackRate!==want){v.playbackRate=want;try{v.preservesPitch=true;}catch(e){}}"
                + "if(!c||!SET.booster){return;}\n"
                + "c.gain.gain.value=Math.max(1,SET.vol/100);\n"
                + "var on=!!SET.vocal;\n"
                + "c.hp.frequency.value=on?90:10;\n"
                + "c.gain.gain.value=on?(Math.max(1,SET.vol/100)*1.6):Math.max(1,SET.vol/100);\n"
                + "c.eq[0].gain.value=on?4:0;c.eq[1].gain.value=on?4.5:0;c.eq[2].gain.value=on?7:0;c.eq[3].gain.value=on?5.5:0;\n"
                + "}catch(e){}}\n"
                + "function ensureChain(v){if(!SET.booster&&!SET.skip)return null;if(foreign.has(v))return null;var c=chains.get(v);if(c)return c;"
                + "if(!sameOrigin(v))return null;var nc=mkChain(v);if(nc){try{nc.ctx.resume();}catch(e){}}return nc;}\n"
                // ---- element discovery (booster EQ surface) ----
                // detection itself is event-driven (see below); this scan only
                // ensures every media element has the booster applied.
                + "var vids=[];function scan(){var n=document.querySelectorAll('video,audio');"
                + "for(var i=0;i<n.length;i++){(function(v){"
                + "if(v.__boltBound)return;v.__boltBound=1;"
                + "applyTo(v);})(n[i]);}vids=n;}\n"
                // v1.3.29 — adaptive scan cadence. The old fixed 1 s
                // setInterval ran a full-document querySelectorAll +
                // per-element applyTo every second forever, hidden or not —
                // pure background CPU on huge SPA pages (and it kept running
                // at full speed under the v1.3.28 always-lie). The loop now
                // self-reschedules: 1 s while visible (booster discovery
                // stays instant), 8 s once the page is REALLY hidden.
                // v1.3.30: hidden-with-media was 3 s — but the scan is only
                // belt-and-braces for the booster (NEW media elements are
                // detected event-driven: capture-phase play/playing listeners
                // call scan()+applyTo on the element itself), so a hidden
                // page gains nothing from 3 s discovery of elements that
                // events already surface. 8 s, flat, while hidden.
                // Function-declaration hoisting makes isReallyHidden()
                // callable here regardless of source order.
                + "function scanTick(){try{scan();for(var i=0;i<vids.length;i++)applyTo(vids[i]);}catch(e){}"
                // v1.3.79: media present -> 1 s (booster/skipper discovery);
                // NO media -> 4 s (a full-document querySelectorAll every
                // second on every page was a permanent renderer-CPU tax —
                // the battery report; event-driven detection covers the
                // transition, the tick is only belt-and-braces)
                + "var d=1000;try{if(!vids.length)d=4000;if(isReallyHidden())d=Math.max(d,8000);}catch(x){}"
                + "setTimeout(scanTick,d);}\n"
                + "setTimeout(scanTick,1000);\n"
                // ================= v1.3.79 THE SILENCE SKIPPER =================
                // LibreTube/NewPipe-style silent-stretch skipping for a
                // WebView browser: the decoder is untouchable, so the viable
                // path is playbackRate-through-silence — the analyser each
                // chain already owns reports RMS; a stretch below the quiet
                // floor for ~350 ms engages 4x RELATIVE to the base speed
                // (1x base -> 4x, 1.5x base -> 6x — the v1.3.84 rule), audio
                // above the floor restores the base speed
                // instantly. Guards: never while paused/seeking; a 120 s
                // continuous-skip cap disengages (a detector failure must
                // never race a whole video); cross-origin direct-file media
                // has no chain (a MediaElementSource on non-CORS media
                // outputs silence — the sameOrigin gate) and is skipped
                // from skipping, by design.
                //
                // v1.3.87 — THE HYSTERESIS OVERHAUL (the field report: "it
                // takes a while to activate but also takes a while to
                // deactivate. As a result I end up hearing a bit of the
                // silence and a portion of the non-silent audio ends up
                // being fast forward"). The old loop ticked every 250 ms:
                // ENGAGEMENT cost = tick + 350 ms hold (up to ~600 ms of
                // silence heard), RELEASE cost = up to one tick (250 ms wall
                // = ~1 s of speech consumed at 4x — the garbled onsets the
                // user reported). The new contract, all four knobs settings
                // (ms chosen over seconds — these are sub-second values,
                // seconds would force decimals):
                //   SET.kf  — skip factor, 1-8x RELATIVE to base (default 3)
                //   SET.ska — START AFTER: ms of quiet before engaging
                //             (default 200; 50-2000)
                //   SET.skb — STOP BEFORE: ms rewound when audio returns, so
                //             the onset that was consumed at high speed
                //             replays clean (default 150; 0-600)
                //   SET.skm — IGNORE SILENCES shorter than this many ms
                //             (default 400; 100-3000): measured in CONTENT
                //             time; a stretch that proves shorter UNDOS the
                //             whole skip (rewind to its start) and bans the
                //             region until playback passes the onset — no
                //             skip/rewind loop.
                // The tick itself drops to 60 ms while any element has a
                // chain (400 ms idle polling until one appears): reaction
                // latency ~80 ms + the analyser's 21 ms window, so at 3x
                // only ~240 ms of onset content rides the skip — and skb
                // rewinds it. Smooth, LibreTube-grade.
                + "var skQuiet=new WeakMap(),skSince=new WeakMap();"
                // the ITERABLE registry: chains itself is a WeakMap (no
                // enumeration, by design) — a parallel Set of the elements
                // that have chains. It dies with the page's JS context (no
                // cross-page leak); dead elements inside a long-lived page
                // are pruned by the isConnected check each tick.
                + "var skEls=new Set();"
                // one live loop at most (armSkip can fire from several paths)
                + "var skLoopOn=false;"
                + "function skSet(v,on){try{if(!!v.__boltSk===on)return;v.__boltSk=on?1:0;applyTo(v);}catch(e){}}"
                + "function skTick(){try{"
                + "try{ctxHeal();}catch(x){}"
                + "if(!SET.skip){skLoopOn=false;skEls.forEach(function(v){skSet(v,false);});return;}"
                + "var now=Date.now();var live=0;"
                + "skEls.forEach(function(v){try{"
                + "var c=chains.get(v);if(!c)return;"
                + "if(!v.isConnected)return;"
                + "if(v.paused||v.seeking){skSet(v,false);skQuiet['delete'](v);return;}"
                + "live++;"
                + "var an=c.an;if(!an)return;"
                // v1.3.89 — the buffer is REUSED per chain: the old
                // new-Float32Array(1024) per element PER TICK was ~16
                // allocations/s of pure GC churn while media played.
                + "if(!c.skBuf){try{c.skBuf=new Float32Array(an.fftSize);}catch(x){return;}}"
                + "var buf=c.skBuf;"
                + "try{an.getFloatTimeDomainData(buf);}catch(x){return;}"
                + "var sum=0;for(var i=0;i<buf.length;i++)sum+=buf[i]*buf[i];"
                + "var rms=Math.sqrt(sum/buf.length);"
                + "if(rms<0.006){"
                + "if(!skQuiet.has(v)){skQuiet.set(v,now);try{v.__boltSilT=v.currentTime;}catch(x){}}"
                + "if(now-skQuiet.get(v)>(SET.ska||200)){"
                // the short-silence ban: a stretch that proved < SET.skm was
                // undone; do not re-engage until playback is past its onset
                // (otherwise skip->rewind->skip loops on the same gap)
                + "var ban=(typeof v.__boltSkBan==='number')?v.__boltSkBan:-1;"
                + "if(ban>=0&&v.currentTime<ban){skQuiet.set(v,now);return;}"
                // 120 s continuous-skip safety cap: a stuck detector must
                // never fast-forward a whole video — restore 1x and disarm
                // until real audio returns (skSince keeps the stamp)
                + "if(v.__boltSk&&skSince.has(v)&&now-skSince.get(v)>120000){skSet(v,false);skSince.set(v,now+1e15);return;}"
                + "if(!v.__boltSk){skSet(v,true);skSince.set(v,now);try{v.__boltSkT=v.currentTime;}catch(x){}}}"
                + "}else{"
                + "skQuiet['delete'](v);skSince['delete'](v);"
                + "if(v.__boltSk)"
                // v1.3.87 — THE ONSET REWIND (runs BEFORE skSet clears the
                // flag; currentTime math uses the skip's own stamps).
                // UNITS: currentTime is SECONDS, the knobs are MS — convert
                // at the boundary (the T38 gate caught the raw compare).
                + "{try{"
                + "var sil=(typeof v.__boltSilT==='number'&&isFinite(v.__boltSilT)&&isFinite(v.currentTime))?(v.currentTime-v.__boltSilT)*1000:0;"
                + "if(isFinite(v.currentTime)&&v.currentTime>0&&isFinite(v.duration)&&v.duration>0){"
                + "if(sil>0&&sil<(SET.skm||400)&&typeof v.__boltSkT==='number'&&isFinite(v.__boltSkT)&&v.__boltSkT<v.currentTime){"
                // TOO SHORT TO BOTHER: undo the whole skip — replay the gap
                // at base speed; ban the region until the onset passes
                + "v.__boltSkBan=v.currentTime+0.05;v.currentTime=v.__boltSkT;"
                + "}else{var back=(SET.skb||0)/1000;"
                + "if(back>0){var tgt=v.currentTime-back;"
                + "if(typeof v.__boltSkT==='number'&&isFinite(v.__boltSkT)&&tgt<v.__boltSkT)tgt=v.__boltSkT;"
                + "if(tgt>0)v.currentTime=tgt;}}"
                + "v.__boltSkT=undefined;}"
                + "}catch(x){}}"
                + "skSet(v,false);}"
                + "}catch(e){}});"
                + "}catch(e){}"
                // v1.3.87 — the cadence law: reaction latency is THE
                // feature. v1.3.89 — the ADAPTIVE law: the flat 60ms ran
                // even when every element was PAUSED and even while the
                // page sat HIDDEN in the background — a 16 Hz timer chain
                // that keeps the renderer awake and fights Chromium's
                // hidden-page throttling (battery report). Cadence now
                // follows the real state: 60ms only while an element is
                // ACTIVELY playing and the page is visible; 250ms while
                // playing in the background (the skipper still works, just
                // relaxed); 1s when elements exist but nothing plays;
                // 400ms until one appears (armSkip only starts the loop).
                + "var hid=false;try{hid=isReallyHidden();}catch(x){}"
                + "setTimeout(skTick,live&&!hid?60:live?250:skEls.size?1000:400);}\n"
                + "function armSkip(){try{if(SET.skip){if(!skLoopOn){skLoopOn=true;skTick();}}else{skEls.forEach(function(v){skSet(v,false);});}}catch(e){}}"
                // v1.3.90 — THE CONTEXT SELF-HEAL. mkChain routes a media
                // element through its own AudioContext (the booster EQ /
                // skipper analyser); a SUSPENDED context takes the element's
                // audio WITH it — no sound, no pause event, the notification
                // still says playing: the field reads it as "background
                // playback stopped". Chromium may suspend contexts of
                // hidden renderers (our own concealment dispatch hands it the
                // hidden state on purpose), and a suspended graph also
                // FREEZES the analyser (fake silence -> phantom 4x skips).
                // The heal runs from TWO hooks: skTick (any cadence) and —
                // critically — reportTime, which rides timeupdate (a MEDIA
                // event: it keeps firing while the element plays, immune to
                // hidden-page timer throttling). resume() succeeds without
                // a fresh gesture on a page with sticky activation (the
                // user pressed play there); when it does NOT, the report
                // carries ok:0 and Java rolls concealment back (the
                // always-lie restores working audio, the v1.3.29 contract).
                + "function ctxHeal(){try{"
                + "if(!skEls.size)return;"
                + "var n=0,still=0;"
                + "skEls.forEach(function(v){try{"
                + "var c=chains.get(v);if(!c||!c.ctx)return;"
                + "if(c.ctx.state!=='running'){n++;"
                + "var p=c.ctx.resume();if(p&&p.catch)p.catch(function(){});"
                + "if(c.ctx.state!=='running')still++;}"
                + "}catch(x){}});"
                + "if(n>0){try{Bolt.mediaEvent('ctxheal','{\"n\":'+n+',\"ok\":'+(still>0?0:1)+'}');}catch(x){}}"
                + "}catch(x){}}"
                + "armSkip();\n"
                // gesture unlock for AudioContext (user gesture starts the context)
                + "var lastGesture=0;\n"
                + "function onGesture(){lastGesture=Date.now();"
                // v1.3.68 — a REAL page gesture while a PiP session is
                // armed re-opens the exit gate (interactive surfaces keep
                // their exit button) and momentarily disarms the pause
                // gate's page-pause swallow (a tap on the page's own pause
                // button must work on 12+ expanded PiP). Android 10 PiP
                // never gestures, so neither escape ever fires there.
                + "try{if(window.__boltpip)window.__boltPipG=1;}catch(x){}"
                + "if(gesture)return;gesture=true;"
                + "for(var i=0;i<vids.length;i++){var c=chains.get(vids[i]);if(c){try{c.ctx.resume().then(function(){for(var j=0;j<vids.length;j++)applyTo(vids[j]);});}catch(e){}}}}\n"
                + "document.addEventListener('touchstart',onGesture,true);\n"
                + "document.addEventListener('click',onGesture,true);\n"
                + "document.addEventListener('keydown',onGesture,true);\n"
                // ================= background spoof (v1.3.13) =================
                // While the app is backgrounded the page must keep believing it
                // is focused and visible: players pause on hidden/blur/hasFocus,
                // and that is what killed background playback on most sites.
                // Everything is installed ONLY while SET.bgPlay is on, and only
                // ACTS while the page is really hidden — foreground behaviour is
                // byte-for-byte normal.
                + "var spoofOn=!!SET.bgPlay;var realHiddenGet=null;var overrides=[];\n"
                + "function isReallyHidden(){try{if(realHiddenGet)return !!realHiddenGet.call(document);}catch(e){}return false;}\n"
                + "function captureRealHidden(){try{var d=null;"
                + "try{if(typeof Document!=='undefined'&&Document.prototype)d=Object.getOwnPropertyDescriptor(Document.prototype,'hidden');}catch(x){d=null;}"
                + "if(!d||!d.get)d=Object.getOwnPropertyDescriptor(document,'hidden');"
                + "if(d&&d.get)realHiddenGet=d.get;}catch(e){}}\n"
                + "function defGetter(obj,name,value){try{var d=Object.getOwnPropertyDescriptor(obj,name);"
                + "Object.defineProperty(obj,name,{configurable:true,enumerable:true,get:function(){return value;}});"
                + "overrides.push({obj:obj,name:name,orig:d?d.get:undefined,had:!!d});}catch(e){}}\n"
                + "function applyGetters(){captureRealHidden();"
                + "defGetter(document,'hidden',false);"
                + "defGetter(document,'webkitHidden',false);"
                + "defGetter(document,'visibilityState','visible');"
                + "defGetter(document,'webkitVisibilityState','visible');"
                + "try{var origHasFocus=document.hasFocus?document.hasFocus:null;"
                + "document.hasFocus=function(){return true;};"
                + "overrides.push({obj:document,name:'hasFocus',fn:origHasFocus,isFn:true});}catch(e){}}\n"
                + "function unspoof(){try{for(var i=overrides.length-1;i>=0;i--){var o=overrides[i];try{"
                + "if(o.isFn){if(o.fn){o.obj[o.name]=o.fn;}else{delete o.obj[o.name];}}\n"
                + "else if(o.had&&o.orig){Object.defineProperty(o.obj,o.name,{configurable:true,enumerable:true,get:o.orig});}\n"
                + "else{delete o.obj[o.name];}}catch(x){}}overrides.length=0;}catch(e){}}\n"
                // away-events: page-level visibilitychange/blur/pagehide/freeze are
                // swallowed ONLY while the page is really hidden (players never see
                // them). Element-level blur (inputs) always passes through.
                + "var SWALLOWED={visibilitychange:1,webkitvisibilitychange:1,blur:1,pagehide:1,freeze:1,unload:1};\n"
                // v1.3.67 — LAYER 2: THE REACTION MUTES. The same wrapping
                // machinery, gated on the PiP session instead of real-hidden:
                // while window.__boltpip is armed, fullscreenchange /
                // webkitfullscreenchange are not delivered to page handlers
                // (a fullscreen exit that does reach the renderer inside
                // PiP never reaches the player's state machine — it cannot
                // pivot to inline-and-pause); once the in-PiP custom-view
                // death has run (Java sets window.__boltpipx=1 in
                // hideCustomViewInternal's inPip branch), resize /
                // orientationchange are muted too (the post-exit relayout
                // reactions — exactly the v1.3.66 handoff's named next
                // lever). unpinJs disarms both AND dispatches one synthetic
                // resize + fullscreenchange so the page resyncs from live
                // DOM state instead of locking on a stale one. Desktop is
                // untouched: its custom view survives PiP (no death, no
                // __boltpipx) and no fullscreenchange fires while armed.
                // v1.3.68 — THE BLUR MUTE. In PiP the activity window
                // LOSES KEY FOCUS and the page really does receive a
                // window blur — the away-event swallow only acts while the
                // page is REALLY hidden (in PiP the renderer stays visible,
                // so blur passed straight through to the player). The
                // mobile player is the blur-reacting class (a mobile
                // app-like player pauses when focus leaves; the desktop
                // player ignores it — the whole mobile/desktop split), and
                // blur is the remaining unnuted trigger that fits the
                // v1.3.67 timing (pause ~420 ms into PiP, custom view
                // still up, first exit already gated). While the session
                // is armed, page-level blur (target window/document —
                // element-level blur like form fields ALWAYS passes) is
                // not delivered. Desktop: the armed set was already a
                // field-proven no-op there (no fullscreenchange, no exits,
                // no pauses in the v1.3.67 desktop log) — a blur the
                // desktop player ignores adds nothing to mute.
                + "var PIPSET={fullscreenchange:1,webkitfullscreenchange:1,resize:1,orientationchange:1,blur:1};\n"
                + "function pipMuted(type,ev){try{"
                + "if(type==='fullscreenchange'||type==='webkitfullscreenchange')"
                + "return !!window.__boltpip;"
                // v1.3.69 — the RESYNC EXEMPTION: an event marked __boltRS
                // (PipVideo.pinJs's one post-arm synthetic resize) passes
                // even while the armed mutes hold — the player's gY must
                // re-read the (spoofed) window size and un-latch its
                // pictureInPicture premise; every UNmarked resize stays
                // muted exactly as v1.3.67 shipped it.
                + "if(type==='resize'||type==='orientationchange'){"
                + "if(ev&&ev.__boltRS)return false;"
                + "if(window.__boltpipx)return true;"
                // v1.4.7 — THE BACKGROUND-ROTATION MUTE (the "background
                // video playback stops when I rotate phone to landscape,
                // only plays in portrait" report): while the page is REALLY
                // HIDDEN with the spoof engaged (the background-playback
                // state — exactly the gate the away-events die at), a
                // resize/orientationchange arriving from the device rotation
                // must never reach the player's reaction handlers: the
                // mobile player class pivots its layout on these events and
                // pauses while invisible (the same reaction family the PiP
                // mutes were built for), and the page-side sentinel's 30 s
                // window is long gone by the time anyone rotates a phone.
                // The rotation is invisible-to-invisible noise; the player
                // re-syncs from the live window size on the foreground
                // return (BrowserActivity dispatches one marked __boltRS
                // resize when the orientation changed while away).
                + "if(spoofOn&&typeof isReallyHidden==='function'&&isReallyHidden())return true;"
                + "return false;}"
                + "if(type==='blur'){"
                + "if(!window.__boltpip)return false;"
                + "var bt=(ev&&ev.target);"
                + "return !bt||bt===window||bt===document;}}catch(x){}return false;}\n"
                + "function wrapHandler(handler){return function(ev){try{"
                + "if(spoofOn&&ev&&isReallyHidden())return;"
                + "if(ev&&pipMuted(ev.type,ev))return;"
                + "}catch(x){}"
                + "if(typeof handler==='function')return handler.call(this,ev);"
                + "if(handler&&typeof handler.handleEvent==='function')return handler.handleEvent(ev);};}\n"
                + "function capFlag(o){if(o===true)return true;return !!(o&&o.capture);}\n"
                + "var wrapMap=new Map();var rawDocAdd=null,rawWinAdd=null;\n"
                + "function hookEvents(){[document,window].forEach(function(t){try{"
                + "var origAdd=t.addEventListener.bind(t);var origRemove=t.removeEventListener.bind(t);"
                + "if(t===document)rawDocAdd=origAdd;else rawWinAdd=origAdd;"
                + "t.addEventListener=function(type,handler,opts){try{"
                + "if(handler&&(PIPSET[type]||(spoofOn&&SWALLOWED[type]))){"
                + "var key=type+'|'+capFlag(opts)+'|'+handler;"
                + "var wrapped=wrapHandler(handler);wrapMap.set(key,wrapped);return origAdd(type,wrapped,opts);}}catch(x){}"
                + "return origAdd(type,handler,opts);};"
                + "t.removeEventListener=function(type,handler,opts){try{"
                + "var key=type+'|'+capFlag(opts)+'|'+handler;var wrapped=wrapMap.get(key);"
                + "if(wrapped){wrapMap.delete(key);origRemove(type,wrapped,opts);return;}}catch(x){}"
                + "return origRemove(type,handler,opts);};}catch(e){}});}\n"
                // capture stoppers: registered through the PRISTINE addEventListener
                // so the wrapper above can never swallow the stoppers themselves
                // (that self-swallow bug re-enabled site pause handlers after a
                // settings toggle round-trip — caught by the mock-DOM tests).
                + "function awayStopper(ev){try{if(!spoofOn)return;"
                + "var tg=ev.target;if(tg!==window&&tg!==document)return;"
                + "if(isReallyHidden()){ev.stopImmediatePropagation();ev.stopPropagation();}}catch(e){}}\n"
                + "function hookStoppers(){Object.keys(SWALLOWED).forEach(function(t){try{"
                + "var docAdd=rawDocAdd||document.addEventListener.bind(document);docAdd.call(document,t,awayStopper,true);}catch(x){}"
                + "try{var winAdd=rawWinAdd||window.addEventListener.bind(window);winAdd.call(window,t,awayStopper,true);}catch(x){}});}\n"
                // rAF diversion: hidden pages never fire rAF; some players drive
                // buffering loops off rAF and stall without it. Audible pages are
                // exempt from timer throttling so the timers run at full speed —
                // which is exactly why the diversion must NOT tick at frame
                // rate (v1.3.28's 16 ms = 60 JS wakeups/s for an invisible
                // page). 250 ms is plenty for any buffering loop.
                + "function hookRAF(){try{var origRAF=window.requestAnimationFrame?window.requestAnimationFrame.bind(window):null;"
                + "var origCAF=window.cancelAnimationFrame?window.cancelAnimationFrame.bind(window):null;if(!origRAF)return;"
                + "window.requestAnimationFrame=function(cb){if(spoofOn&&isReallyHidden()){"
                + "return setTimeout(function(){try{cb(performance.now());}catch(e){}},250);}return origRAF(cb);};"
                + "window.cancelAnimationFrame=function(id){clearTimeout(id);if(origCAF)origCAF(id);};}catch(e){}}\n"
                + "function applySpoof(){applyGetters();}\n"
                // ================= media tracking (event-driven) =================
                + "var playingSet=new Set();var active=null;var lastState='none';var lastBeat=0;var lastTitle='',lastArt='',lastDur=-1;var lastMeta=0;\n"
                // v1.3.29 — concealment sentinel state: wasHidden tracks the
                // NATIVE visibility flip (with the spoof armed, the only way a
                // page goes really-hidden is the Java-side concealment
                // dispatch); cs* = the bounded auto-resume that must survive
                // it. safePlay/report are hoisted function declarations.
                + "var wasHidden=false,csUntil=0,csTries=0,csTimer=null,csFlipAt=0;\n"
                + "function csArm(){csUntil=Date.now()+30000;csTries=0;}\n"
                + "function csStop(){if(csTimer){clearTimeout(csTimer);csTimer=null;}csUntil=0;}\n"
                + "function csFight(el){\n"
                + "if(csTimer)clearTimeout(csTimer);\n"
                + "var delay=csTries===0?250:(csTries===1?800:1500);\n"
                + "csTimer=setTimeout(function(){try{\n"
                + "if(!el.isConnected){csStop();return;}\n"
                // an ENDED element must never be restarted by the sentinel —
                // that would replay the previous video over the queue advance
                + "if(el.ended){csStop();report();return;}\n"
                + "if(!el.paused){csStop();report(true);return;}\n"
                // v1.3.41 — the silent give-up was the background-play killer:
                // throttled timers (intensive throttling fires ~1/min) made the
                // 30 s window expire before the sentinel ever RAN, and the pause
                // was then respected with the renderer still concealed — dead
                // playback + a dead notification PLAY. An expiry loss is a
                // conceal failure like any other: resume once, roll concealment
                // back (the service latches per version) and report.
                + "if(el.__userPaused){csStop();report();return;}\n"
                + "if(Date.now()>csUntil){csStop();try{safePlay(el);}catch(x){}"
                + "try{Bolt.mediaEvent('concealfail','{}');}catch(x){}try{report();}catch(x){}return;}\n"
                + "csTries++;\n"
                + "if(csTries>3){csStop();try{Bolt.mediaEvent('concealfail','{}');}catch(x){}try{report();}catch(x){}return;}\n"
                + "safePlay(el);csFight(el);\n"
                + "}catch(x){}},delay);}\n"
                // v1.3.66 — THE PiP PLAY SENTINEL. The four-release mobile
                // report ("I press play, it stops instantly" in the floating
                // window, v1.3.63→65) is the mobile player's own logic
                // reversing a play it did not sanction: the element play
                // Bolt's RemoteAction dispatches bypasses the player's state
                // machine, and the v1.3.65 bare-video pin left the player
                // box collapsed under the pinned video. The sentinel fights
                // the reversal while the PiP window is up (window.__boltpip —
                // armed by PipVideo.pinJs, disarmed by unpinJs): every
                // NON-USER pause of the active element gets a bounded
                // re-play, each reported as a 'pipfight' diag event so the
                // next field log carries the evidence. A pause that survives
                // 2.5s of playback or a user-commanded pause is respected
                // immediately; the tries reset the moment a play holds.
                // v1.3.67 — THE BOUND WAS THE PROBLEM. The v1.3.66 log:
                // 'fs pip fight n=1' + the pause still winning = the
                // reversal outlasted 5 tries (~2.1 s). The fight is now 8
                // tries over ~13 s (200/500/900/1300/1800/2400/3000/3000),
                // and a lost fight schedules ONE 15 s re-arm probe: still
                // armed + connected + paused + not user-paused/ended = a
                // fresh burst (a page that pauses once per state change
                // keeps meeting one bounded fight per change — it can never
                // win by out-waiting a session again). The 'pipfight'
                // events now carry the decoder payload (pipEvt): the
                // pause's JS stack when one was recorded (js=1 st=… — the
                // pausing module, NAMED) or js=0 (no JS caller = a
                // renderer-side pause — a different bug class entirely).
                + "var pipTries=0,pipTimer=null,pipHeld=0,pipDoneAt=0;\n"
                // THE PAUSE GATE's state: pipGateN counts swallows this
                // session; pipGateAt is the rolling window the rate limiter
                // reads; pipGateCool is the pass-through cooldown.
                + "var pipGateN=0,pipGateAt=[],pipGateCool=0;\n"
                // v1.3.69 — THE RECALIBRATION: the v1.3.68 field log proved
                // the mobile player's anti-PiP poll pauses on EVERY
                // progress tick (~4 Hz — one pauseVideo per tick, base.js
                // bY) — 12-in-3 s was that cadence EXACTLY, so the limiter
                // manufactured the very stutter it existed to bound: ~3 s
                // of gate-held audio, then 5 s of pass-through stutter,
                // forever. The poll itself is now dead at the premise (the
                // outer-size spoof), and the limiter is recalibrated to
                // catch only a TRUE pathological burst (24 swallows inside
                // 2 s = 12 Hz — 3x the poll cadence) with a shorter 3 s
                // cooldown. A steady protective swallow rate can never
                // trip it: at 4 Hz, 24 swallows span 6 s (the 2 s window
                // can never contain them).
                + "function pipGateTripped(){try{"
                + "var now=Date.now();"
                + "if(now<pipGateCool)return true;"
                + "pipGateAt.push(now);"
                + "if(pipGateAt.length>24)pipGateAt.shift();"
                + "if(pipGateAt.length===24&&now-pipGateAt[0]<2000){"
                + "pipGateCool=now+3000;pipGateAt.length=0;return true;}"
                + "return false;}catch(x){return false;}}\n"
                + "function pipEvt(el,n){var st='';"
                + "try{st=String((el&&el.__boltPPS)||'');}catch(x){}"
                // NL = the newline char, built (not escaped) so the JS
                // extractor's unescape can never mangle it into a literal
                // v1.3.68 — THE EXCERPT GREW TEETH: the v1.3.67 field
                // stack truncated at 240 chars after just TWO frames —
                // the full base.js URL ate the budget, and the CALLER of
                // g.fZ.pause (the actual trigger chain) was cut off. Now
                // 7 lines, each frame's long URLs shortened to the file
                // name (base.js:5598:266) and capped at 90 chars, total
                // 320 — the next report names the module AND its caller.
                + "var NL=String.fromCharCode(10);"
                + "if(st){try{"
                + "var fr=st.split(NL).slice(0,7);"
                + "for(var fi=0;fi<fr.length;fi++){"
                + "var ln=String(fr[fi]).replace(/\\s+/g,' ').trim();"
                + "try{var fm=ln.match(/(?:https?:\\/\\/)[^)\\s]*/);"
                + "if(fm){var fu=fm[0];var si=fu.lastIndexOf('/');"
                + "if(si>=0)fu=fu.slice(si+1);ln=ln.replace(fm[0],fu);}}catch(x){}"
                + "fr[fi]=ln.slice(0,90);}"
                + "st=fr.join(' | ').slice(0,320);}catch(x){}}"
                + "try{return JSON.stringify({n:n,js:st?1:0,st:st});}"
                + "catch(x){return '{\"n\":'+n+',\"js\":0,\"st\":\"\"}';}}\n"
                + "function pipDelay(n){return n===0?200:(n===1?500:(n===2?900:"
                + "(n===3?1300:(n===4?1800:(n===5?2400:3000)))));}\n"
                + "function pipFight(el){\n"
                + "if(pipTimer)clearTimeout(pipTimer);\n"
                + "try{if(pipTries>7&&pipDoneAt&&Date.now()-pipDoneAt>15000)pipTries=0;}catch(x){}\n"
                + "var delay=pipDelay(pipTries);\n"
                + "pipTries++;\n"
                + "pipTimer=setTimeout(function(){try{\n"
                + "if(!window.__boltpip){pipTries=0;return;}\n"
                + "if(!el.isConnected){pipTries=0;return;}\n"
                + "if(el.ended||el.__userPaused){pipTries=0;return;}\n"
                + "if(!el.paused){if(Date.now()-pipHeld>2500)pipTries=0;report(true);return;}\n"
                + "if(pipTries>7){pipDoneAt=Date.now();"
                + "try{Bolt.mediaEvent('pipfight',pipEvt(el,pipTries));}catch(x){}try{report();}catch(x){}"
                + "setTimeout(function(){try{"
                + "if(!window.__boltpip)return;"
                + "if(!el.isConnected||el.ended||el.__userPaused)return;"
                + "if(!el.paused)return;"
                + "pipTries=0;pipFight(el);"
                + "}catch(x){}},15000);return;}\n"
                + "safePlay(el);ytApi(el,true);\n"
                + "try{Bolt.mediaEvent('pipfight',pipEvt(el,pipTries));}catch(x){}\n"
                + "pipFight(el);\n"
                + "}catch(x){}},delay);}\n"
                // v1.3.66 — THE PLAYER-API PLAY. YouTube's player exposes
                // playVideo/pauseVideo on the player element itself (the
                // same channel its own buttons use); driving the state
                // machine THROUGH it makes the play one the player
                // sanctioned, instead of an element-level play it reverses.
                // Existence-checked: absent on every non-YouTube page (a
                // no-op there).
                + "function ytApi(v,toPlay){try{\n"
                + "var kp=v.closest?v.closest('.html5-video-player,#movie_player,ytm-video-player,ytd-player'):null;\n"
                + "if(kp&&kp!==v&&typeof kp.playVideo==='function'&&typeof kp.pauseVideo==='function'){\n"
                + "if(toPlay)kp.playVideo();else kp.pauseVideo();return true;}}catch(x){}return false;}\n"
                + "function durOf(el){try{var d=el.duration;if(!isFinite(d)||d<0)d=0;return d;}catch(e){return 0;}}\n"
                + "function prune(){playingSet.forEach(function(el){if(!el.isConnected&&el.paused)playingSet.delete(el);});}\n"
                + "function anyPlaying(){prune();var any=false;playingSet.forEach(function(el){if(!el.paused&&!el.ended)any=true;});return any;}\n"
                + "function playingEl(){prune();var out=null;playingSet.forEach(function(el){if(!el.paused&&!el.ended)out=el;});return out;}\n"
                // v1.3.18 title harvest — priority: page MediaSession metadata
                // (YouTube & major players set it PER VIDEO, SPA-safe) >
                // document.title (updates per SPA nav, " - YouTube" suffix
                // trimmed) > og:title (LAST: set at first page load, NEVER
                // updates during SPA navigation — preferring it is exactly why
                // the notification title stuck on the first video).
                + "function metaTitle(){var t='';try{var m=navigator.mediaSession;if(m&&m.metadata&&m.metadata.title)t=m.metadata.title;}catch(x){}"
                + "if(!t){try{t=document.title||'';}catch(x){}}"
                + "if(!t){try{var og=document.querySelector('meta[property=\"og:title\"],meta[name=\"og:title\"]');if(og&&og.content)t=og.content;}catch(x){}}"
                + "try{t=String(t);var sfx=' - youtube';var low=t.toLowerCase();if(low.length>sfx.length&&low.slice(-sfx.length)===sfx)t=t.slice(0,t.length-sfx.length);}catch(x){}return t;}\n"
                // v1.3.28 — deterministic YouTube artwork source: the watch
                // URL's video id maps straight to i.ytimg.com thumbnails.
                // The page's own metadata plumbing (MediaSession artwork /
                // poster / og:image) can lag seconds behind an SPA autoplay
                // switch or never move at all — the URL changes the INSTANT
                // the next queued video starts, so this channel always
                // matches what is actually playing.
                + "function ytId(){try{var l=location;var h=String(l.hostname||'');"
                + "if(h.indexOf('youtube.com')===-1&&h.indexOf('youtube-nocookie.com')===-1)return '';"
                + "var m=String(l.search||'').match(/[?&]v=([a-zA-Z0-9_-]{6,})/);if(m)return m[1];"
                + "var p=String(l.pathname||'').match(/\\/(?:shorts|embed|live)\\/([a-zA-Z0-9_-]{6,})/);if(p)return p[1];}catch(x){}return '';}\n"
                // v1.3.18 artwork harvest — MediaSession artwork array (pick
                // the largest declared size) > video poster > og:image. Java
                // downloads it for the MediaSession metadata + notification
                // large icon (fading thumbnail look on Android 13+).
                + "function metaArt(){try{var yid=ytId();if(yid)return 'https://i.ytimg.com/vi/'+yid+'/mqdefault.jpg';}catch(x){}"
                + "try{var m=navigator.mediaSession;"
                + "if(m&&m.metadata&&m.metadata.artwork&&m.metadata.artwork.length){var best=null,bw=-1;"
                + "for(var i=0;i<m.metadata.artwork.length;i++){var a=m.metadata.artwork[i];if(!a||!a.src)continue;"
                + "var w=0;try{w=(a.sizes||'').split('x')[0]*1||0;}catch(x){}if(w>=bw){bw=w;best=a;}}"
                + "if(best)return best.src;}}catch(x){}"
                + "try{var pv=playingEl()||(active&&!active.ended?active:null);if(pv&&pv.poster)return pv.poster;}catch(x){}"
                + "try{var oi=document.querySelector('meta[property=\"og:image\"],meta[name=\"og:image\"]');if(oi&&oi.content)return oi.content;}catch(x){}return '';}\n"
                + "function report(force){try{var s=anyPlaying()?'playing':(active&&!active.ended?'paused':'none');"
                + "var t=metaTitle(),ar=metaArt();"
                + "var v=playingEl()||(active&&!active.ended?active:null);"
                + "var d=v?durOf(v):0;"
                // dedup includes title + artwork + DURATION: a video switch
                // on an SPA is often playing→playing with the same title
                // momentarily (metadata races the play event), and the new
                // duration can land last — any of the four changing
                // re-sends, so the notification never shows the previous
                // video's length while the next one plays.
                + "if(!force&&s===lastState&&t===lastTitle&&ar===lastArt&&d===lastDur)return;lastState=s;lastTitle=t;lastArt=ar;lastDur=d;"
                + "var obj={playing:s==='playing',dur:d,pos:v?v.currentTime:0,"
                + "boostable:v?((SET.booster&&sameOrigin(v)&&!foreign.has(v))||!!chains.get(v)):false,"
                + "rate:v?v.playbackRate:1,title:t,art:ar};"
                + "Bolt.mediaEvent('state',JSON.stringify(obj));}catch(e){}}\n"
                + "function reportTime(){try{var v=active;if(!v)return;var now=Date.now();\n"
                // v1.3.29 — detect the concealment flip (native hidden) at
                // full timeupdate cadence, BEFORE any pause it provokes can
                // land: the sentinel must be armed ahead of the fight.
                + "try{var hh=isReallyHidden();if(hh!==wasHidden){wasHidden=hh;csFlipAt=hh?Date.now():0;"
                + "if(hh&&(anyPlaying()||active))csArm();}}catch(x){}\n"
                // v1.3.90 — the context heal rides EVERY timeupdate (see
                // ctxHeal): media events are the one clock hidden-page timer
                // throttling cannot take away while the element plays.
                + "try{ctxHeal();}catch(x){}\n"
                // v1.3.29 — hidden beats are 10 s, not 1 s: every beat is a
                // JS→Java bridge crossing + a media-session binder call +
                // SystemUI churn; while backgrounded nobody needs
                // second-level position (the seekbar extrapolates from the
                // last PlaybackState; the &t= restore tolerates 10 s).
                + "var hid=wasHidden;var minMs=hid?10000:900;\n"
                + "if(now-lastBeat<minMs)return;lastBeat=now;\n"
                // 1 Hz metadata race-poll (chases late SPA title/art/dur —
                // loadedmetadata can land after the play event) visible;
                // 3 s hidden — the poll is several DOM queries each time.
                + "try{if(now-lastMeta>=(hid?3000:1000)){lastMeta=now;\n"
                + "var tt=metaTitle(),aa=metaArt(),dd=durOf(v);\n"
                + "if((tt&&tt!==lastTitle)||(aa&&aa!==lastArt)||(dd>0&&dd!==lastDur))report(true);}}catch(x){}\n"
                + "Bolt.mediaEvent('time',JSON.stringify({pos:v.currentTime||0,dur:durOf(v),rate:v.playbackRate||1}));}catch(e){}}\n"
                + "function onMediaEvent(ev){try{var el=ev.target;"
                + "if(!el||!(el.tagName==='VIDEO'||el.tagName==='AUDIO'))return;var type=ev.type;"
                + "if(type==='play'||type==='playing'){playingSet.add(el);active=el;scan();"
                // v1.3.79: a starting element needs its graph when ANY live
                // feature wants one — vol>100/vocal (the booster) OR skip
                // (the analyser). Without this, skip-on with plain volume
                // never built a chain and the skipper had no ear.
                + "if((SET.vol>100||SET.vocal||SET.skip)&&!chains.get(el))ensureChain(el);"
                + "applyTo(el);pipHeld=Date.now();report(true);}"
                + "else if(type==='pause'){\n"
                // v1.3.66 — the PiP play sentinel runs FIRST (a pause while
                // the floating window is up is the mobile reversal unless
                // the user asked for it); the concealment sentinel keeps
                // the really-hidden case for itself (mutually exclusive in
                // practice — pipWatch keeps a watched renderer visible —
                // but the guard keeps the layers honest)
                + "try{if(window.__boltpip&&!(spoofOn&&isReallyHidden())&&active===el&&!el.__userPaused){pipFight(el);return;}}catch(x){}\n"
                // v1.3.29 — concealment sentinel: a NON-USER pause while the
                // page is REALLY hidden can only be the concealment flip
                // fighting back (Chromium or the page objecting to the
                // renderer going hidden — the exact v1.3.13-era failure the
                // always-lie was built to avoid). Fight it, bounded; the
                // pause is only REPORTED (respected) if it wins, was
                // user-commanded, or lands outside the fight window.
                // v1.3.41 — the old (window || flip<5 s) gate missed every
                // DELAYED pause throttled timers delivered long after the flip —
                // those were respected as final while concealment kept the
                // renderer hidden. ANY non-user pause of the active element
                // while REALLY hidden is the concealment fighting back: re-arm
                // (if expired) and fight. A user pause still wins immediately.
                + "try{if(spoofOn&&isReallyHidden()&&active===el&&!el.__userPaused){\n"
                + "if(Date.now()>csUntil)csArm();csFight(el);return;}}catch(x){}\n"
                + "if(active===el||!anyPlaying())report();}\n"
                + "else if(type==='ended'||type==='emptied'){playingSet.delete(el);if(active===el){active=null;report();}}"
                + "else if(type==='loadedmetadata'||type==='durationchange'||type==='ratechange'){if(el===active)report(true);}}"
                + "catch(e){}}\n"
                + "['play','playing','pause','ended','emptied','loadedmetadata','durationchange','ratechange']"
                + ".forEach(function(t){document.addEventListener(t,onMediaEvent,true);});\n"
                + "document.addEventListener('timeupdate',function(ev){try{var el=ev.target;"
                + "if(el!==active||el.tagName!=='VIDEO'&&el.tagName!=='AUDIO')return;reportTime();}catch(e){}},true);\n"
                // ================= commands from Java =================
                + "function safePlay(v){try{var pr=v.play();if(pr&&pr.catch)pr.catch(function(){});}catch(e){}}\n"
                + "function mediaTarget(){return active||playingEl()||vids[0];}\n"
                + "window.__boltCmd=function(cmd,arg){try{var v=mediaTarget();if(!v)return;"
                + "if(cmd==='toggle'){if(!v.paused){v.__userPaused=true;v.pause();ytApi(v,false);}else{v.__userPaused=false;safePlay(v);ytApi(v,true);}}"
                + "else if(cmd==='play'){v.__userPaused=false;safePlay(v);ytApi(v,true);}"
                + "else if(cmd==='pause'){v.__userPaused=true;v.pause();ytApi(v,false);}"
                + "else if(cmd==='stop'){v.__userPaused=true;try{v.pause();}catch(e){}ytApi(v,false);}"
                + "else if(cmd==='seek'){if(isFinite(arg))v.currentTime=Math.max(0,Math.min(v.duration||1e9,v.currentTime+arg));}"
                + "else if(cmd==='seekTo'){if(isFinite(arg))v.currentTime=Math.max(0,Math.min(v.duration||1e9,arg));}"
                + "else if(cmd==='speed'){SET.speed=arg;v.playbackRate=arg;try{v.preservesPitch=true;}catch(e){}applyTo(v);}"
                + "else if(cmd==='vol'){SET.vol=arg;if((SET.vol>100||SET.vocal)&&!chains.get(v))ensureChain(v);applyTo(v);}"
                + "else if(cmd==='vocal'){SET.vocal=!!arg;if((SET.vol>100||SET.vocal)&&!chains.get(v))ensureChain(v);applyTo(v);}"
                + "else if(cmd==='skip'){SET.skip=!!arg;if(SET.skip&&!chains.get(v))ensureChain(v);armSkip();applyTo(v);}"
                + "report(true);}catch(e){}}\n"
                // settings push (live toggle from the settings pages)
                + "window.__boltSetMedia=function(json){try{var c=JSON.parse(json);var wasBg=!!SET.bgPlay;"
                + "SET=c;"
                + "if(SET.bgPlay&&!spoofOn){spoofOn=true;applySpoof();}"
                + "else if(!SET.bgPlay&&spoofOn){spoofOn=false;unspoof();}"
                + "armSkip();"
                + "scan();for(var i=0;i<vids.length;i++)applyTo(vids[i]);}catch(e){}}\n"
                // service asks for a full state push right after (re)creation
                + "window.__boltReport=function(){try{report(true);}catch(e){}}\n"
                // ---- cookie blocker (per-site) ----
                + "if(SET.noCookies){try{document.__defineGetter__('cookie',function(){return '';});"
                + "document.__defineSetter__('cookie',function(){});}catch(e){}}\n"
                // ---- element inspector ----
                + "window.__boltInspect=function(on){try{if(on){window.__insp=1;document.addEventListener('click',function(e){"
                + "if(!window.__insp)return;e.preventDefault();e.stopPropagation();"
                + "var el=e.target;if(!el||!el.tagName)return;"
                + "var sel=el.tagName.toLowerCase();if(el.id)sel+='#'+el.id;"
                + "if(el.className&&typeof el.className==='string'){var cs=el.className.trim().split(/\\s+/).slice(0,3).join('.');if(cs)sel+='.'+cs;}"
                + "var info={tag:el.tagName.toLowerCase(),id:el.id||'',cls:(typeof el.className==='string'?el.className:'').slice(0,120),"
                + "w:el.offsetWidth,h:el.offsetHeight,sel:sel,text:(el.textContent||'').trim().slice(0,80)};"
                + "var old=document.getElementById('__boltOutline');if(old)old.remove();"
                + "var o=document.createElement('div');o.id='__boltOutline';o.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #7C4DFF;background:rgba(124,77,255,0.15)';"
                + "var r=el.getBoundingClientRect();o.style.left=r.left+'px';o.style.top=r.top+'px';o.style.width=r.width+'px';o.style.height=r.height+'px';"
                + "document.documentElement.appendChild(o);"
                + "Bolt.inspectorPick(JSON.stringify(info));},true);}else{window.__insp=0;"
                + "var o=document.getElementById('__boltOutline');if(o)o.remove();}}catch(e){}};\n"
                // ---- text extraction for TTS ----
                + "window.__boltText=function(){try{return (document.body?document.body.innerText:'').slice(0,30000);}catch(e){return '';}};\n"
                // ---- v1.3.31 OTP field detection (built-in authenticator) ----
                // Focusing a 2FA input (autocomplete=one-time-code, 6/8-digit
                // numeric input, name/id naming otp/totp/code/verification)
                // tells Java (Bolt.otpField(true)), which offers the matching
                // authenticator code as a fill chip; __boltFillOtp(code) is
                // what the chip's tap calls back into.
                + "function __boltOtpEl(el){try{if(!el||el.tagName!=='INPUT')return false;"
                + "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();if(ac==='one-time-code')return true;"
                + "var im=(el.getAttribute('inputmode')||'').toLowerCase();var ml=parseInt(el.getAttribute('maxlength')||'0',10)||0;"
                + "var nm=((el.name||'')+' '+(el.id||'')+' '+(el.type||'')+' '+(el.className||'')).toLowerCase();"
                + "if(/otp|totp|2fa|twofa|one[- ]?time|verification|verify|authenticat|security[- ]?code|confirm[- ]?code/.test(nm))return true;"
                + "if((im==='numeric'||el.type==='tel'||el.type==='number')&&ml>=4&&ml<=8)return true;"
                + "if(el.type==='text'&&ml>=6&&ml<=8&&/code|pin|token/.test(nm))return true;"
                + "return false;}catch(e){return false;}}\n"
                + "var __boltOtpArmed=false;"
                + "document.addEventListener('focusin',function(ev){try{var el=ev.target;"
                + "var is=__boltOtpEl(el);"
                + "if(is&&!__boltOtpArmed){__boltOtpArmed=true;window.__boltOtpField=el;"
                + "try{Bolt.otpField(true);}catch(x){}}"
                + "else if(!is&&__boltOtpArmed){__boltOtpArmed=false;window.__boltOtpField=null;"
                + "try{Bolt.otpField(false);}catch(x){}}}catch(e){}},true);\n"
                + "window.__boltFillOtp=function(code){try{var el=window.__boltOtpField;"
                + "if(!el){var ins=document.querySelectorAll('input');for(var i=0;i<ins.length;i++){if(__boltOtpEl(ins[i])&&!ins[i].value){el=ins[i];break;}}}"
                + "if(!el)return false;el.value=String(code);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return true;}catch(e){return false;}};\n"
                // ---- v1.3.38 password vault detection ----
                // A login form SUBMIT (a form carrying a password field with a
                // value) reports host/user/pass to Java (Bolt.pwSubmit → the
                // save offer; Java decides whether to show it and NEVER
                // stores anything without the user's tap on Save). Focusing a
                // password field asks the vault for a fill chip
                // (Bolt.pwField), and __boltFillPw(user,pass) is what the
                // chip's tap calls back into — username + password, with
                // input/change events so modern frameworks notice.
                + "function __boltUserField(f){try{if(!f||!f.querySelectorAll)return null;"
                + "var ins=f.querySelectorAll('input');for(var i=0;i<ins.length;i++){var el=ins[i];"
                + "var ty=(el.type||'').toLowerCase();"
                + "if(ty==='password'||ty==='hidden'||ty==='submit'||ty==='button'||ty==='checkbox'||ty==='radio')continue;"
                + "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();"
                + "if(ac.indexOf('username')>=0||ty==='email'||ty==='text'||ty==='tel')return el;}"
                + "return null;}catch(e){return null;}}\n"
                + "function __boltPwReport(f){try{if(!f)return false;"
                + "var pw=f.querySelector?f.querySelector('input[type=password]'):null;"
                + "if(!pw||!pw.value)return false;var us=f.querySelector?__boltUserField(f):null;"
                + "var u=us&&us.value?us.value:'';"
                // v1.4.4 — THE REMEMBERED USERNAME (the multi-account corpse
                // half): two-step sign-ins ask the username on screen 1 and
                // show the password alone on screen 2 — the submit report
                // carried u='' both times, so every account on such a site
                // saved into ONE (host,'') row and the second account
                // OVERWROTE the first ("unable to save multiple passwords
                // for multiple accounts on the same site"). The LAST
                // username-ish field the page focused is remembered
                // (__boltLastUserEl, set by the focusin detector below) and
                // fills the gap — screen 2's report carries screen 1's
                // username, each account lands in its OWN (host,user) row.
                + "if(!u){var lu=window.__boltLastUserEl;if(lu&&lu.value){u=lu.value;}}"
                + "var k=u+'\u0001'+pw.value;"
                + "if(window.__boltPwSent===k)return false;window.__boltPwSent=k;"
                + "try{Bolt.pwSubmit(location.host,u,pw.value);}catch(x){}return true;}"
                + "catch(e){return false;}}\n"
                // v1.3.39 FIELD FIX + v1.3.40 WIDENING ("I still don't get
                // prompted to save on logins — I get the google prompt only,
                // not the bolt prompt"): SPA sign-ins (Google et al.) never
                // fire a native submit, AND Google's "Next" button is
                // FORM-LESS — the v1.3.39 click-capture bailed at
                // el.closest('form') == null, which is exactly why the
                // prompt never fired there. Now: submit-ish clicks fall back
                // to a DOCUMENT scan for a filled password field; the ENTER
                // key reports from the password field itself (the earliest,
                // never-cleared moment); visibilitychange is the background
                // last-chance alongside pagehide.
                + "document.addEventListener('submit',function(ev){try{__boltPwReport(ev.target);}"
                + "catch(e){}},true);\n"
                + "document.addEventListener('click',function(ev){try{var el=ev.target;"
                + "if(!el||!el.closest)return;"
                + "var isSub=(el.tagName==='INPUT'&&(el.type||'').toLowerCase()==='submit');"
                + "if(!isSub&&el.tagName==='BUTTON'){var tx=(el.textContent||el.value||'');"
                + "isSub=/sign|log|next|continue|submit|verify|create|register|go|enter|access/i.test(tx);}"
                + "if(!isSub)return;"
                + "var f=el.closest('form');"
                + "var pw=f?f.querySelector('input[type=password]'):null;"
                + "if(!pw||!pw.value){var pws=document.querySelectorAll('input[type=password]');"
                + "for(var i=0;i<pws.length;i++){if(pws[i].value){pw=pws[i];f=pw.form||null;break;}}}"
                + "if(!pw||!pw.value)return;"
                + "__boltPwReport(f||document);}catch(e){}},true);\n"
                + "document.addEventListener('keydown',function(ev){try{"
                + "if(ev.keyCode!==13&&ev.key!=='Enter')return;"
                + "var el=ev.target;if(!el||el.tagName!=='INPUT')return;"
                + "var ty=(el.type||'').toLowerCase();"
                + "if(ty!=='password'&&ty!=='text'&&ty!=='email'&&ty!=='tel')return;"
                + "var pw=null;var f=el.form||null;"
                + "if(f){pw=f.querySelector('input[type=password]');}"
                + "if(!pw&&ty==='password')pw=el;"
                + "if(!pw){var pws=document.querySelectorAll('input[type=password]');"
                + "for(var i=0;i<pws.length;i++){if(pws[i].value){pw=pws[i];f=pw.form||null;break;}}}"
                + "if(!pw||!pw.value)return;"
                + "__boltPwReport(f||document);}catch(e){}},true);\n"
                + "window.addEventListener('pagehide',function(){try{var pws="
                + "document.querySelectorAll('input[type=password]');"
                + "for(var i=0;i<pws.length;i++){if(pws[i].value){"
                + "__boltPwReport(pws[i].form?pws[i].form:document);break;}}}catch(e){}});\n"
                + "document.addEventListener('visibilitychange',function(){try{"
                + "if(document.visibilityState!=='hidden')return;var pws="
                + "document.querySelectorAll('input[type=password]');"
                + "for(var i=0;i<pws.length;i++){if(pws[i].value){"
                + "__boltPwReport(pws[i].form?pws[i].form:document);break;}}}catch(e){}});\n"
                // v1.3.39 hardening — three more login paths the nets above
                // can miss: (a) ENTER pressed in the password field (no
                // click, no submit on some SPAs), (b) scripted
                // form.submit() (no submit EVENT fires — the method is
                // called directly), (c) SPA route changes after sign-in
                // (pushState/replaceState/popstate — no pagehide ever
                // comes). All funnel through the same once-per-(user,pass)
                // reporter.
                + "document.addEventListener('keydown',function(ev){try{"
                + "var el=ev.target;"
                + "if(ev.key==='Enter'&&el&&el.tagName==='INPUT'"
                + "&&(el.type||'').toLowerCase()==='password'){"
                + "var f=el.form?el.form:(el.closest?el.closest('form'):null);"
                + "__boltPwReport(f||document);}}catch(e){}},true);\n"
                + "try{if(HTMLFormElement&&HTMLFormElement.prototype"
                + "&&!HTMLFormElement.prototype.__boltPatched){"
                + "var __boltFs=HTMLFormElement.prototype.submit;"
                + "HTMLFormElement.prototype.submit=function(){try{__boltPwReport(this);}catch(e){}"
                + "return __boltFs.apply(this,arguments);};"
                + "HTMLFormElement.prototype.__boltPatched=true;}}catch(e){}\n"
                + "try{var __boltPs=history.pushState;"
                + "history.pushState=function(){try{__boltPwReport(document);}catch(e){}"
                + "return __boltPs.apply(this,arguments);};"
                + "var __boltRs=history.replaceState;"
                + "history.replaceState=function(){try{__boltPwReport(document);}catch(e){}"
                + "return __boltRs.apply(this,arguments);};"
                + "window.addEventListener('popstate',function(){try{__boltPwReport(document);}catch(e){}},true);}catch(e){}\n"
                + "var __boltPwArmed=false;"
                // v1.3.79 — USERNAME-FIRST FLOWS: many sites ask for the
                // username on screen 1 and only reveal the password field
                // on screen 2 (Google, most banks). The chip used to arm
                // ONLY on a password field, so on exactly those sites
                // "the bolt pop up to fill in the info does not appear"
                // while Google's system prompt did. A login field is now
                // a password field OR a username-ish one (autocomplete
                // username, or text/email/tel inside a form — or document —
                // that carries a password field).
                + "function __boltLoginField(el){try{if(!el||el.tagName!=='INPUT')return false;"
                + "var ty=(el.type||'').toLowerCase();"
                + "if(ty==='password')return true;"
                + "if(ty!=='text'&&ty!=='email'&&ty!=='tel')return false;"
                + "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();"
                + "if(ac.indexOf('username')>=0)return true;"
                + "var f=el.form?el.form:(el.closest?el.closest('form'):null);"
                + "var root=(f&&f.querySelectorAll)?f:document;"
                + "if(root.querySelector&&root.querySelector('input[type=password]'))return true;"
                + "return false;}catch(e){return false;}}\n"
                + "document.addEventListener('focusin',function(ev){try{var el=ev.target;"
                + "var is=__boltLoginField(el);"
                // v1.3.91 — THE PAGE HOST RIDES THE EVENT (the v1.3.79
                // favicon law: the PAGE is the one source of truth for
                // where it is — an embedded/SSO login form can live on a
                // different origin than the tab URL, and the fill chip's
                // vault lookup keyed on the tab host found nothing =
                // "I don't get bolt's prompt to fill my saved passwords").
                + "if(is&&!__boltPwArmed){__boltPwArmed=true;window.__boltPwFocused=el;"
                // v1.4.4 — remember the LAST username-ish field (the pw
                // submit's fallback for two-step sign-ins, see
                // __boltPwReport): screen 2's password-only form inherits
                // screen 1's username.
                + "if(el.type!=='password'){window.__boltLastUserEl=el;}"
                + "try{Bolt.pwField(location.host,true);}catch(x){}}"
                + "else if(!is&&__boltPwArmed){__boltPwArmed=false;window.__boltPwFocused=null;"
                + "try{Bolt.pwField('',false);}catch(x){}}}catch(e){}},true);\n"
                // v1.4.4 — THE CLICK RE-ARM (the "does not always offer to
                // fill" corpse): the chip retires after 30 s, but a focused
                // field never re-fires focusin — tapping the SAME password
                // box again did nothing and the chip stayed gone. A click on
                // a login field re-asks Java for the chip (showPwChip
                // no-ops while one is alive, re-shows when retired).
                + "document.addEventListener('click',function(ev){try{var el=ev.target;"
                + "if(el&&el.tagName==='INPUT'&&__boltLoginField(el)){"
                + "window.__boltPwFocused=el;"
                + "try{Bolt.pwField(location.host,true);}catch(x){}}}catch(e){}},true);\n"
                // v1.3.79 — THE FIELD RECT: where the focused login/otp/form
                // field sits (CSS-px viewport coords) — the fill chips
                // anchor to THIS, not to the keyboard.
                + "window.__boltFieldRect=function(){try{"
                + "var el=window.__boltPwFocused||window.__boltOtpField||document.activeElement;"
                + "if(!el||!el.getBoundingClientRect)return null;"
                + "var r=el.getBoundingClientRect();"
                + "return {t:Math.round(r.top),b:Math.round(r.bottom)};}catch(e){return null;}};\n"
                // v1.3.79 — fill honours the username-first flow: focused
                // field is the username -> fill it (the password lands when
                // screen 2's field takes focus and re-arms the chip);
                // focused field (or any) is a password -> user + password.
                + "window.__boltFillPw=function(u,p){try{var el=null;"
                + "var ins=document.querySelectorAll('input[type=password]');"
                + "if(window.__boltPwFocused)el=window.__boltPwFocused;else if(ins.length)el=ins[0];"
                + "if(!el)return false;"
                + "var ty=(el.type||'').toLowerCase();"
                + "if(ty!=='password'){"
                + "el.value=u;el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "if(ins.length){el=ins[0];}else{return true;}}"
                + "var f=el.form?el.form:(el.closest?el.closest('form'):null);"
                + "var us=f?__boltUserField(f):__boltUserField(document);"
                + "if(us&&u&&us!==el){us.value=u;us.dispatchEvent(new Event('input',{bubbles:true}));us.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "el.value=p;el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return true;}catch(e){return false;}};\n"
                // ---- v1.3.39 PROFILES (menu-3 #13): address/card autofill ----
                // Focusing a form-ish field (autocomplete/name/id heuristics)
                // asks Java for the profile chip; __boltFillProfile(json)
                // writes every matching field with input/change events.
                + "function __boltFormish(el){try{if(!el||el.tagName!=='INPUT')return false;"
                + "var ty=(el.type||'').toLowerCase();"
                + "if(ty==='password'||ty==='hidden'||ty==='button'||ty==='submit'||ty==='radio')return false;"
                + "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();"
                + "var nm=((el.name||'')+' '+(el.id||'')+' '+ac).toLowerCase();"
                + "return /cc-|card|exp|cvc|cvv|address|street|city|postal|zip|country|"
                + "region|province|given-name|family-name|fullname|username|email|tel|phone|"
                + "^(name)$/.test(nm)||ac.indexOf('name')>=0;}catch(e){return false;}}\n"
                + "var __boltFormArmed=false;\n"
                + "document.addEventListener('focusin',function(ev){try{var el=ev.target;"
                + "var is=__boltFormish(el);"
                + "if(is&&!__boltFormArmed){__boltFormArmed=true;"
                + "try{Bolt.formField(true);}catch(x){}}"
                + "else if(!is&&__boltFormArmed){__boltFormArmed=false;"
                + "try{Bolt.formField(false);}catch(x){}}}catch(e){}},true);\n"
                + "window.__boltFillProfile=function(json){try{var d=typeof json==='string'?"
                + "JSON.parse(json):json;var n=0;var ins=document.querySelectorAll('input');"
                + "for(var i=0;i<ins.length;i++){var el=ins[i];"
                + "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();"
                + "var nm=((el.name||'')+' '+(el.id||'')+' '+ac).toLowerCase();"
                + "var key=null;"
                + "if(/cc-number|cardnumber|card-number|cardnum/.test(nm))key='card';"
                + "else if(/cc-exp|expir/.test(nm))key='exp';"
                + "else if(/cc-csc|cvc|cvv|securitycode|cardcode/.test(nm))key='cvv';"
                + "else if(/email/.test(nm))key='email';"
                + "else if(/tel|phone/.test(nm))key='phone';"
                + "else if(/address2|address-line2|street2|apt/.test(nm))key='address2';"
                + "else if(/address|street/.test(nm))key='address';"
                + "else if(/city|town/.test(nm))key='city';"
                + "else if(/postal|zip/.test(nm))key='zip';"
                + "else if(/country|nation/.test(nm))key='country';"
                + "else if(/organi[sz]ation|company/.test(nm))key='org';"
                + "else if(/family-name|lastname|last-name|surname/.test(nm))key=null;"
                + "else if(/given-name|firstname|first-name/.test(nm))key=null;"
                + "else if(/fullname|^name$|(^(name)$)/.test(nm)||ac.indexOf('name')>=0)key='name';"
                + "if(key&&d[key]){el.value=d[key];"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));n++;}}"
                + "return n;}catch(e){return 0;}};\n"
                // ================= boot =================
                + "try{if(spoofOn)applySpoof();}catch(e){}\n"
                + "try{hookEvents();}catch(e){}\n"
                // v1.3.69 — the outer-size spoof installs at boot (armed-
                // guarded getter: inert until PipVideo.pinJs sets
                // window.__boltpip, live-native values while disarmed)
                + "try{pipOuterSpoof();}catch(e){}\n"
                + "try{hookStoppers();}catch(e){}\n"
                + "try{hookRAF();}catch(e){}\n"
                // v1.3.18: player-side metadata swaps (YouTube video change) fire
                // metadatachange on the page MediaSession — instant re-report.
                + "try{if(navigator.mediaSession&&navigator.mediaSession.addEventListener)navigator.mediaSession.addEventListener('metadatachange',function(){report(true);});}catch(e){}\n"
                // v1.3.28 — SPA navigation watcher: an autoplay/queue switch is
                // a history.pushState navigation (no page load; the video
                // element is reused, so play/loadedmetadata alone can race the
                // metadata). YouTube fires its own yt-navigate-finish; every
                // SPA player emits popstate/pushState. Re-report a few times
                // while the player swaps sources and metadata lands.
                + "function spaNav(){try{[400,1200,3000].forEach(function(ms){setTimeout(function(){try{report(true);}catch(x){}},ms);});}catch(x){}}\n"
                + "try{window.addEventListener('popstate',function(){try{spaNav();}catch(x){}},true);\n"
                + "document.addEventListener('yt-navigate-finish',function(){try{spaNav();}catch(x){}},true);}catch(e){}\n"
                + "try{if(window.history&&history.pushState){var __hp=history.pushState,__hr=history.replaceState;\n"
                + "history.pushState=function(){var r=__hp.apply(this,arguments);try{spaNav();}catch(x){}return r;};\n"
                + "history.replaceState=function(){var r=__hr.apply(this,arguments);try{spaNav();}catch(x){}return r;};}}catch(e){}\n"
                // ---- v1.3.61 PSEUDO-FULLSCREEN WATCHER ----
                // The v1.3.60 field report: "the scripts overlay reaches
                // desktop site video full screen, in mobile site script works
                // fine". Mobile pages go through the real Fullscreen API
                // (document.fullscreenElement set, app custom view up, the
                // page's ::backdrop covers body-level overlays) — desktop
                // players sometimes CSS-maximize INSTEAD (their own fallback
                // when the request is rejected or never sent): fullscreenElement
                // stays null, NOTHING tells the browser chrome to leave, and
                // every floating surface (Bolt's bars, quick panel, fill
                // chips — and any userscript panels) rides the "fullscreen"
                // video. This watcher reports the state so Java can sweep its
                // own chrome out of the way (Bolt.pseudo). Deliberately cheap:
                // a no-op early-exit when no media is active, ~1 s cadence,
                // edge-triggered reporting (only on state CHANGES).
                + "var __pfLast=0,__pfAt=0;\n"
                + "function __pfCheck(force){try{\n"
                + "var now=Date.now();if(!force&&now-__pfAt<800)return;__pfAt=now;\n"
                + "var on=0;"
                + "try{if(!document.fullscreenElement&&!document.webkitFullscreenElement){"
                + "var v=playingEl();if(!v&&active&&!active.ended&&!active.paused)v=active;"
                + "if(v&&v.tagName==='VIDEO'&&!v.paused&&!v.ended&&v.videoWidth>0){"
                + "var r=v.getBoundingClientRect();var vw=window.innerWidth||1,vh=window.innerHeight||1;"
                + "if(r.width>=vw*0.95&&r.height>=vh*0.95&&r.left<=vw*0.05&&r.top<=vh*0.05)on=1;}}}catch(x){}\n"
                + "if(on!==__pfLast){__pfLast=on;try{Bolt.mediaEvent('pseudo',String(on));}catch(x){}}}catch(e){}}\n"
                + "try{setInterval(function(){try{"
                + "if(playingSet.size===0&&!(active&&!active.ended))return;__pfCheck(false);}catch(e){}},1000);}catch(e){}\n"
                + "try{window.addEventListener('resize',function(){try{__pfCheck(true);}catch(x){}},true);}catch(e){}\n"
                + "try{document.addEventListener('fullscreenchange',function(){try{setTimeout(function(){__pfCheck(true);},200);}catch(x){}},true);}catch(e){}\n"
                + "try{document.addEventListener('webkitfullscreenchange',function(){try{setTimeout(function(){__pfCheck(true);},200);}catch(x){}},true);}catch(e){}\n"
                // v1.3.61 evidence channel: a REJECTED requestFullscreen is
                // the smoking gun of the "API never fired" failure class —
                // surface it to the diag log so the next field report can
                // tell the classes apart at a glance.
                + "try{document.addEventListener('fullscreenerror',function(){try{Bolt.mediaEvent('fserr','1');}catch(x){}},true);}catch(e){}\n"
                // ---- v1.3.63 DOCUMENT-FULLSCREEN OVERLAY CONCEALMENT ----
                // The v1.3.62 field report decoded the desktop mystery:
                // desktop YouTube fullscreens <html> (documentElement) —
                // every body-level floating overlay (userscript panels at
                // z-index 2147483647, the Tampermonkey convention) is INSIDE
                // the fullscreen element, so the DOM top layer cannot cover
                // it ("the script overlay does make it into the fullscreen
                // still"), and it rides along into the PiP window. Mobile
                // fullscreens the PLAYER -> overlays are OUTSIDE -> covered
                // -> "works". While a DOCUMENT-level fullscreen is active,
                // hide every direct child of body/documentElement whose
                // computed position is fixed|absolute AND whose computed
                // z-index is >= 100000000 (script territory — no sane site
                // UI lives above 1e8; YouTube's own fullscreen UI sits in
                // the hundreds/thousands); restore exactly what was hidden
                // when it ends. window.__boltFsSweep(1|0) lets Java force
                // and clear it (the wedged-enter rescue and the in-PiP
                // custom-view-death path both use the force).
                + "var __fsHidden=[];\n"
                + "function __fsIsDoc(){try{var fe=document.fullscreenElement||document.webkitFullscreenElement;"
                + "return fe===document.documentElement||fe===document.body;}catch(x){return false;}}\n"
                + "function __fsSweep(hide){try{\n"
                + "if(hide){var cand=[];"
                + "try{var b=document.body;if(b&&b.children)for(var i=0;i<b.children.length;i++)cand.push(b.children[i]);}catch(x){}\n"
                + "try{var h=document.documentElement;if(h&&h.children)for(var j=0;j<h.children.length;j++)cand.push(h.children[j]);}catch(x){}\n"
                + "for(var k=0;k<cand.length;k++){var el=cand[k];"
                + "if(!el||!el.tagName)continue;var tg=el.tagName;"
                + "if(tg==='SCRIPT'||tg==='STYLE'||tg==='LINK'||tg==='META'||tg==='NOSCRIPT'||tg==='BASE')continue;"
                + "if(el===document.body||el===document.documentElement)continue;"
                + "if(typeof el.__boltFsPrevVis!=='undefined')continue;"
                + "var cs;try{cs=window.getComputedStyle(el);}catch(x){continue;}"
                + "if(!cs)continue;var ps=cs.position;"
                + "if(ps!=='fixed'&&ps!=='absolute')continue;"
                + "var z=parseInt(cs.zIndex,10);if(!isFinite(z)||z<100000000)continue;"
                + "try{el.__boltFsPrevVis=el.style.visibility;el.style.visibility='hidden';__fsHidden.push(el);}catch(x){}}\n"
                + "}else{for(var m=0;m<__fsHidden.length;m++){try{var e2=__fsHidden[m];"
                + "if(typeof e2.__boltFsPrevVis!=='undefined'){e2.style.visibility=e2.__boltFsPrevVis;delete e2.__boltFsPrevVis;}}catch(x){}}"
                + "__fsHidden=[];}\n"
                + "}catch(x){}}\n"
                + "function __fsSync(){try{__fsSweep(__fsIsDoc());}catch(x){}}\n"
                + "try{document.addEventListener('fullscreenchange',function(){try{__fsSync();"
                + "if(__fsIsDoc()){[400,1200,2500].forEach(function(ms){setTimeout(function(){try{if(__fsIsDoc())__fsSweep(true);}catch(x){}},ms);});}"
                + "}catch(x){}},true);}catch(e){}\n"
                + "try{document.addEventListener('webkitfullscreenchange',function(){try{__fsSync();}catch(x){}},true);}catch(e){}\n"
                + "window.__boltFsSweep=function(on){try{__fsSweep(!!on);}catch(x){}};\n"
                + "scan();report(true);\n"
                + "})();";
    }

    public static String mediaSettingsJson(Prefs p) {
        // v1.3.79: + skip (THE SILENCE SKIPPER — the video-control FAB's
        // second independent toggle)
        // v1.3.87: + kf/ska/skb/skm — the four hysteresis knobs (skip
        // factor 1-8x relative, start-after ms, stop-before rewind ms,
        // ignore-silences-under ms; Prefs clamps each to its legal range)
        return "{\"vol\":" + p.lastVolume() + ",\"vocal\":" + p.vocalOn()
                + ",\"speed\":" + p.lastSpeed() + ",\"bgPlay\":" + p.bgPlay()
                + ",\"booster\":" + p.mediaBooster()
                + ",\"skip\":" + p.mediaSkip()
                + ",\"kf\":" + p.skipFactor()
                + ",\"ska\":" + p.skipAttack()
                + ",\"skb\":" + p.skipRelease()
                + ",\"skm\":" + p.skipMinSilence()
                + ",\"noCookies\":false}";
    }

    /** element-hiding CSS (adblock cosmetic + zaps) injected at onPageFinished. */
    public static String cssInject(String css) {
        if (css == null || css.isEmpty()) return "";
        String safe = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        return "(function(){try{var s=document.createElement('style');s.textContent='"
                + safe + "';(document.head||document.documentElement).appendChild(s);}catch(e){}})();";
    }

    /** v1.3.80 — IDEMPOTENT CSS injection: one <style> per id. A re-run with
     *  the same id REPLACES its textContent instead of stacking another
     *  element (the adblock cosmetic pass now runs at BOTH commit-visible
     *  and page-finished — replacement, not duplication, is what makes that
     *  safe; the finish pass extends the early css with the dark fallback). */
    public static String cssInjectOnce(String id, String css) {
        if (css == null || css.isEmpty()) return "";
        String safe = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        return "(function(){try{var d=document,s=d.getElementById('" + id + "');"
                + "if(!s){s=d.createElement('style');s.id='" + id
                + "';(d.head||d.documentElement).appendChild(s);}"
                + "s.textContent='" + safe + "';}catch(e){}})();";
    }

    /** fallback dark CSS when webDark enabled.
     *  v1.3.87 — THE MEDIA EXCLUSIONS widened (the field report: "it
     *  inverts images which causes problem and makes ugly"): the static
     *  rule now un-inverts embed/object/[role=img] too, and .__boltNI
     *  is the class the background-image scan pins on every element
     *  whose COMPUTED background-image carries a url() — the old
     *  [style*="background-image"] attribute selector only ever saw
     *  INLINE styles, so every photo/icon set through a CSS CLASS came
     *  out negative. (The scan itself is [darkSmartJs]; both
     *  inversions must stay hue-rotate-paired to approximate the
     *  original colors.)
     *  v1.3.92 — THE PARITY CLASS: .__boltDK cancels the global
     *  inversion for a subtree the parity walk ([darkSmartJs]) proved
     *  already paints DARK — an already-dark component keeps its exact
     *  signature tones instead of being blindly flipped (the v1.3.91
     *  report: "it makes some darker coloured elements (like dark grey)
     *  bright (to light grey) by blind inversion. Also it ends up making
     *  some white elements become black on black hiding buttons"). The
     *  cancel carries contrast(1.111) so the html-level contrast(0.9)
     *  composes back to EXACTLY the original colors (contrast is affine
     *  around the 0.5 pivot — the two applications multiply).
     *  v1.3.93 — THE MEDIA-IN-DK RULE (the bug-hunt pass): an img/video/
     *  .__boltNI inside a preserved dark subtree stacked THREE inversions
     *  (html global + the DK cancel + the media un-invert) = an ODD
     *  count — photos and class-set backgrounds inside dark cards came
     *  out as X-RAY NEGATIVES. Inside .__boltDK the ancestor pair
     *  already cancels to identity, so the media rule must contribute
     *  NOTHING: filter:none (the descendant selectors out-specify the
     *  element/class rules they override). */
    public static String darkFallbackCss() {
        // v1.3.94 — THE CLARITY PASS (the field report: "Also ensure to keep
        // contrast high and thicken up thin elements a bit to increase
        // clarity"): the html contrast raised 0.9 → 1.05 (mid-greys stop
        // washing out against dark grounds; photos/media ride their own
        // un-inverted filter and only meet the mild raster contrast) and a
        // hairline currentColor halo thickens every glyph (thin fonts and
        // hairline UI read clearly on dark; NOT !important so any site-set
        // text-shadow still wins). The DK compensation is the exact
        // inverse: 1/1.05 = 0.9524 (the two compose to identity — contrast
        // is affine around the 0.5 pivot and the applications multiply).
        return "html{filter:invert(1) hue-rotate(180deg) contrast(1.05)!important;}"
                + "img,video,picture,canvas,svg,iframe,embed,object,[role=\"img\"],"
                + "[style*=\"background-image\"],.__boltNI"
                + "{filter:invert(1) hue-rotate(180deg)!important;}"
                + ".__boltDK{filter:invert(1) hue-rotate(180deg) contrast(0.9524)!important;}"
                + ".__boltDK img,.__boltDK video,.__boltDK picture,.__boltDK canvas,"
                + ".__boltDK svg,.__boltDK iframe,.__boltDK embed,.__boltDK object,"
                + ".__boltDK [role=\"img\"],.__boltDK [style*=\"background-image\"],"
                + ".__boltDK .__boltNI{filter:none!important;}"
                // v1.4.20 — THE UI-ICON RULE: the walk classes small svgs
                // (<= 64 px — hamburger menus, chevrons, tool glyphs) with
                // .__boltIC and this rule takes their OWN un-invert filter
                // away, so they ride the page-level inversion like text: a
                // dark glyph flips to light and reappears on the black
                // ground (the faint-hamburger field report). Class beats the
                // type selector above; inside .__boltDK the DK svg rule and
                // this one agree on filter:none either way.
                // v1.4.21 — the class now covers small IMGs in UI context
                // (menu.png inside buttons/headers — media-family members
                // that were double-un-inverted and stayed black) and small
                // background-image icons on interactive elements (pinned by
                // pinEl/both). One class, one meaning: filter:none = this
                // element rides the page inversion.
                + ".__boltIC{filter:none!important;}"
                + "html body *:not(img):not(video):not(picture):not(canvas)"
                + ":not(svg):not(iframe):not(embed):not(object)"
                + "{text-shadow:0 0 0.35px currentColor;}";
    }

    /** v1.3.94 — THE ASK STEP (the field spec, verbatim: "If browser is in
     *  dark mode, make bolt ask the website to turn on the websites designed
     *  dark mode it self. If there is no dark mode designed by the website,
     *  then fall back to manual smart inversion"). The ASKING half is
     *  already wired: the dark skins carry android:isLightTheme=false, so
     *  WebView (93+) answers prefers-color-scheme: dark for every page —
     *  github/vscode then paint their OWN dark a beat after onLoad (the
     *  v1.3.93 field log: "dark native-site" then "dark late-flip" on the
     *  same load = the old flow inverted the light first paint and pulled
     *  2.5 s later — a white flash + flip on EVERY page view of a
     *  scheme-aware site). This probe answers the OTHER half: does THIS
     *  page implement a dark mode at all? A page that declares one must
     *  NEVER see the inversion — Bolt waits (bounded) for its own dark
     *  instead; a page with no declaration can never self-dark, so the
     *  fallback inversion lands immediately, exactly as before.
     *  Signals, cheap-to-trusted:
     *  (a) <meta name="color-scheme"> mentioning dark;
     *  (b) computed color-scheme on <html> mentioning dark;
     *  (c) ANY accessible stylesheet carrying an @media rule keyed on
     *      prefers-color-scheme (the page RESPONDS to the hint —
     *      cross-origin sheets are skipped per-sheet by the try/catch;
     *      capped at 2000 rules a sheet so a huge SPA never janks);
     *  (d) a theme MACHINERY on <html> (data-theme/data-mode/
     *      data-color-mode set to anything = a themed SPA that may flip
     *      after its data arrives, or a dark/night class already set). */
    public static String askDarkJs() {
        return "(function(){try{"
                // (a) meta color-scheme mentions dark
                + "try{var m=document.querySelector('meta[name=\"color-scheme\"]');"
                + "if(m&&/dark/i.test(m.getAttribute('content')||''))return '1';}catch(e){}"
                // (b) computed color-scheme mentions dark
                + "try{var s=getComputedStyle(document.documentElement).colorScheme||'';"
                + "if(/dark/i.test(s))return '1';}catch(e){}"
                // (c) a stylesheet rule keyed on prefers-color-scheme
                + "try{var ss=document.styleSheets;"
                + "for(var i=0;i<ss.length;i++){var rs=null;"
                + "try{rs=ss[i].cssRules;}catch(e){continue;}"
                + "if(!rs)continue;"
                + "for(var j=0;j<rs.length&&j<2000;j++){var r=rs[j];"
                + "if(r&&r.media&&((r.media.mediaText||'').indexOf('prefers-color-scheme')>=0))return '1';}}}catch(e){}"
                // (d) themed-SPA machinery on <html>
                + "try{var h=document.documentElement;"
                + "var at=['data-theme','data-mode','data-color-mode','data-theme-mode'];"
                + "for(var k=0;k<at.length;k++){if(h.getAttribute(at[k]))return '1';}"
                + "if(/(^|\\s)(dark|theme-dark|dark-mode|night|nightmode)(\\s|$)/i.test(h.className||''))return '1';}catch(e){}"
                + "return '0';}catch(e){return '0'}})()";
    }

    /** v1.3.87 — THE ALREADY-DARK PROBE (the field report: "the dark site
     *  is not properly detecting if the website is already dark and ends
     *  up inversing on the dark website making it white"). Runs BEFORE the
     *  inversion CSS is injected; returns "1" when the page paints itself
     *  dark already.
     *  v1.3.91 — THE SMART PROBE (round 2; the v1.3.90 report: "the dark
     *  mode still sucks, does not properly detect sites with good dark
     *  modes already and inverts it"). Six signals now, in trust order:
     *  (1) html/body computed background (a real painted color, alpha-gated);
     *  (2) color-scheme whose FIRST token is dark (the page declares its
     *  own canvas dark);
     *  (3) a dark meta theme-color (GitHub & friends);
     *  (4) THEME ATTRIBUTES — html[data-theme=data-mode=data-color-mode
     *  ~=dark] or html.dark / [data-dark] / dark-mode theme classes (the
     *  YouTube [dark], Reddit, X [data-theme] family — most popular sites
     *  flip their theme through exactly these);
     *  (5) THE TEXT TEST — body text luminance >= 0.55 over a background
     *  <= 0.35 = light-on-dark typography (a dark page that paints its
     *  bg through a wrapper div the html/body check never sees);
     *  (6) ELEMENT SAMPLING — up to 80 visible elements' OPAQUE
     *  backgrounds; >= 60% dark = a dark SPA (the wrapper-div case,
     *  generalized; catches the sites that paint late or through
     *  absolutely-positioned roots).
     *  Everything else returns "0" — unknown is NOT dark, so inversion
     *  stays the fallback (a wrong white flash beats a double-dark). */
    public static String darkProbeJs() {
        return "(function(){try{"
                + "function lumOf(c){try{if(!c||c==='transparent')return null;"
                + "if(c.charAt(0)==='#'){var h=c.slice(1);"
                + "if(h.length===3)h=h.charAt(0)+h.charAt(0)+h.charAt(1)+h.charAt(1)+h.charAt(2)+h.charAt(2);"
                + "if(h.length===6&&!/[^0-9a-f]/i.test(h))"
                + "return (0.2126*parseInt(h.slice(0,2),16)+0.7152*parseInt(h.slice(2,4),16)"
                + "+0.0722*parseInt(h.slice(4,6),16))/255;return null;}"
                + "var m=c.match(/rgba?\\(([\\d.]+)[,\\s]+([\\d.]+)[,\\s]+([\\d.]+)(?:[,/\\s]+([\\d.]+))?\\)/);"
                + "if(!m)return null;var a=m.length>4?parseFloat(m[4]):1;if(a<0.35)return null;"
                + "return (0.2126*parseFloat(m[1])+0.7152*parseFloat(m[2])+0.0722*parseFloat(m[3]))/255;}catch(e){return null;}}"
                // (1) html then body computed background
                + "var els=[document.documentElement,document.body];"
                + "var bodyBg=null;"
                + "for(var i=0;i<els.length;i++){try{if(!els[i])continue;"
                + "var l=lumOf(getComputedStyle(els[i]).backgroundColor);"
                + "if(l!==null){if(l<0.16)return '1';if(bodyBg===null)bodyBg=l;}}catch(x){}}"
                // (2) color-scheme: dark (first token)
                + "try{var sch=getComputedStyle(document.documentElement).colorScheme||'';"
                + "if(/^\\s*dark\\b/i.test(sch))return '1';}catch(x){}"
                // (3) meta theme-color
                + "try{var mt=document.querySelector('meta[name=\"theme-color\"]');"
                + "if(mt){var c=mt.getAttribute('content');var l2=lumOf(c);"
                + "if(l2!==null&&l2<0.16)return '1';}}catch(x){}"
                // (4) theme attributes on <html> (the YouTube/Reddit/X family)
                + "try{var h=document.documentElement;"
                + "var attrs=['data-theme','data-mode','data-color-mode','data-theme-mode','color-scheme'];"
                + "for(var a2=0;a2<attrs.length;a2++){var v=h.getAttribute(attrs[a2])||'';"
                + "if(/(^|[^a-z])dark([^a-z]|$)/i.test(v))return '1';}"
                + "if(h.hasAttribute('dark'))return '1';"
                + "var hc=(h.className||'')+' '+(h.getAttribute('data-color-mode')||'');"
                + "if(/(^|\\s)(dark|theme-dark|dark-mode|night|nightmode)(\\s|$)/i.test(hc))return '1';}catch(x){}"
                // (5) the text test: light text over a dark-ish ground
                + "try{if(document.body){"
                + "var t=lumOf(getComputedStyle(document.body).color);"
                + "if(t!==null&&t>=0.55&&(bodyBg!==null&&bodyBg<=0.35))return '1';}}catch(x){}"
                // (6) element sampling: >=60% of OPAQUE visible bgs are dark
                + "try{if(document.body){var all=document.body.querySelectorAll('*');"
                + "var dark=0,opaque=0,n2=0;"
                + "for(var j=0;j<all.length&&n2<80;j++){var e2=all[j];"
                + "try{var r2=e2.getBoundingClientRect();"
                + "if(r2.width<50||r2.height<20)continue;"
                + "var bl=lumOf(getComputedStyle(e2).backgroundColor);"
                + "if(bl===null)continue;opaque++;n2++;"
                + "if(bl<0.16)dark++;}catch(x){}}"
                + "if(opaque>=4&&dark/opaque>=0.6)return '1';}}catch(x){}"
                + "return '0';}catch(e){return '0'}})()";
    }

    /** v1.3.87 — THE BACKGROUND-IMAGE SCAN + v1.3.92 — THE PARITY WALK,
     *  unified as ONE pass ([darkSmartJs]).
     *
     *  (A) THE NI PIN: .__boltNI on every element whose COMPUTED
     *  background-image carries a url() (un-inverted by
     *  [darkFallbackCss]) — photos and icons set through CSS classes,
     *  which the attribute selector could never see.
     *
     *  (B) THE PARITY WALK — the v1.3.91 report: "this dark mode … still
     *  blindly inverts colours … it makes some darker coloured elements
     *  (like dark grey) bright (to light grey) by blind inversion. Also
     *  it ends up making some white elements become black on black
     *  hiding buttons. make the inversion identifier smart." The global
     *  invert(1) hue-rotate(180deg) flips EVERY paint; the walk decides,
     *  PER ELEMENT, whether a subtree should be inverted at all:
     *    - an OPAQUE DARK background (luminance <= 0.35 — #333, #444,
     *      #4a4a4a, the dark grey family) PRESERVES the subtree: the
     *      cancel class .__boltDK restores its exact colors (dark grey
     *      stays dark grey, its white labels stay white — never black on
     *      black);
     *    - an OPAQUE LIGHT background keeps the inversion (light card →
     *      dark card);
     *    - a url()-carrying background preserves (photos keep their
     *      text overlays readable);
     *    - everything transparent inherits the parent's parity (text and
     *      borders follow whatever ground they actually sit on).
     *  The walk maintains the FILTER PARITY (odd = inverted once, the
     *  global filter's contribution; adding .__boltDK toggles it) so a
     *  light element inside a preserved dark one can still be inverted
     *  — the per-element decision composes down the tree.
     *
     *  Chunked (16 ms slices, 40 ms gaps, 3500-element cap) so a huge SPA
     *  never janks; a MutationObserver processes added subtrees through
     *  BOTH passes for 12 s (the interval SPAs mount their content in;
     *  capped so a runaway page can never keep one alive forever). */
    public static String darkSmartJs() {
        return "(function(){try{if(window.__boltDKrun)return;window.__boltDKrun=1;"
                + "var DK='__boltDK',NI='__boltNI',SV='__boltSV',IC='__boltIC';var n=0;"
                // luminance of a css color (mirrors darkProbeJs); null =
                // transparent / too-transparent / unparseable
                + "function lumOf(c){try{if(!c||c==='transparent')return null;"
                + "var m=c.match(/rgba?\\(([\\d.]+)[,\\s]+([\\d.]+)[,\\s]+([\\d.]+)(?:[,/\\s]+([\\d.]+))?\\)/);"
                + "if(!m)return null;var a=m.length>4?parseFloat(m[4]):1;if(a<0.45)return null;"
                + "return (0.2126*parseFloat(m[1])+0.7152*parseFloat(m[2])+0.0722*parseFloat(m[3]))/255;}catch(e){return null;}}"
                // v1.4.19 — THE STROKE VISIBILITY FLOOR (the forced-dark
                // thin-lines/hamburger-icons-too-faint report):
                // invert(1) maps a light-gray stroke (#ddd, lum ~0.87) to
                // ~#222 on the inverted dark ground — near-zero contrast.
                // clampCss remaps a LIGHT source color DOWN to luminance
                // 0.6 (153/255) so the post-inversion result is a visible
                // mid-gray on black. PURE — the arithmetic is the gate's
                // probe-pinned transcription (scripts/test_v1419.py).
                // (NOTE: no double quotes in body comments — check_dark_js
                // extracts every quoted literal as JS.)
                + "function clampCss(c){try{"
                + "var m=c.match(/rgba?\\(([\\d.]+)[,\\s]+([\\d.]+)[,\\s]+([\\d.]+)(?:[,/\\s]+([\\d.]+))?\\)/);"
                + "if(!m)return null;"
                + "var r=parseFloat(m[1]),g=parseFloat(m[2]),b=parseFloat(m[3]);"
                + "var a=m.length>4?parseFloat(m[4]):1;"
                + "var y=0.2126*r+0.7152*g+0.0722*b;"
                + "if(y<=153)return null;"
                + "var f=153/y;r=Math.round(r*f);g=Math.round(g*f);b=Math.round(b*f);"
                + "return a<1?('rgba('+r+','+g+','+b+','+a+')'):('rgb('+r+','+g+','+b+')');"
                + "}catch(e){return null}}"
                // v1.4.19 — mark + remember: the inline writes are sticky, so
                // the ORIGINAL inline values are stored on the element and
                // darkClearJs restores them exactly when the inversion lifts
                // v1.4.20 — the store grew: stroke/fill/stroke-width (SVG UI
                // icons), border-width (the thickness bump) and the icon-font
                // text stroke ride the same one-shot save + exact restore
                + "function svSave(el){try{"
                + "if(el.classList.contains(SV))return;"
                + "el.classList.add(SV);"
                + "el.setAttribute('data-boltsv',JSON.stringify({c:el.style.color||null,b:el.style.borderColor||null"
                + ",s:el.style.stroke||null,f:el.style.fill||null,w:el.style.strokeWidth||null"
                + ",W:el.style.borderWidth||null,t:el.style.webkitTextStroke||null}));"
                + "}catch(e){}}"
                // v1.4.20 — THE ICON-CLASSIFIER (pure; probe-pinned in
                // scripts/test_v1420.py): a SMALL svg (longest edge <= 64 px)
                // is a UI icon — hamburger menus, chevrons, tool glyphs;
                // anything larger is CONTENT (charts, illustrations, big
                // logos) and stays in the media family. The size cut is the
                // classifier the field report asked for: dark-stroked UI
                // icons were left un-inverted and vanished on the black
                // ground, while content SVGs must never be inverted.
                // v1.4.21 — THE IMG HALF (the hamburger still black, round
                // 2): menus served as IMG (menu.svg / menu.png) are MEDIA —
                // the media rule un-inverts them (double invert = keeps
                // the original black) and they vanish on the black ground
                // exactly like the SVGs did. Context FIRST, size second
                // (the field ask): a small IMG inside UI chrome (button,
                // header, nav, label, summary, role=button, an icon-named
                // ancestor) is chrome; a small IMG inside a bare link is
                // only chrome when TINY (<= 48 — links wrap thumbnails
                // too) or icon-named itself; everything else is content.
                // (no double quotes in comments — check_dark_js reads them)
                + "function iconNamed(el){try{"
                + "var cn=el.className;"
                + "if(cn&&cn.baseVal!==undefined)cn=cn.baseVal;"
                + "var s=((cn||'')+' '+(el.id||''));"
                + "return /(^|[^a-z])(icon|menu|hamburger|burger|nav|toggle|chevron|arrow|close|cancel|gear|search)([^a-z]|$)/i.test(s);"
                + "}catch(e){return false}}"
                + "function uiCtx(el){try{"
                + "var p=el.parentElement,lvl=0,weak=0;"
                + "while(p&&lvl<6){var t=p.tagName;"
                + "if(t==='BUTTON'||t==='HEADER'||t==='NAV'||t==='LABEL'||t==='SUMMARY')return 2;"
                + "if(p.getAttribute&&p.getAttribute('role')==='button')return 2;"
                + "if(iconNamed(p))return 2;"
                + "if(t==='A')weak=1;"
                + "p=p.parentElement;lvl++;}"
                + "return weak;}catch(e){return 0}}"
                + "function uiIcon(el){try{"
                + "if(!el)return false;"
                + "var t=el.tagName;"
                + "if(t!=='SVG'&&t!=='IMG')return false;"
                + "var r=el.getBoundingClientRect();"
                + "var m=Math.max(r.width,r.height);"
                + "if(!(m>0&&m<=64))return false;"
                + "if(t==='SVG')return true;"
                + "if(iconNamed(el))return true;"
                + "var ctx=uiCtx(el);"
                + "if(ctx===2)return true;"
                + "if(ctx===1)return m<=48;"
                + "return false;}catch(e){return false}}"
                // v1.4.21 — THE BACKGROUND-ICON HALF: CSS background-image
                // icons on SMALL INTERACTIVE elements (button/a/label/
                // header/nav or icon-named) ride the IC class too — the NI
                // pin un-inverts them and a dark glyph vanished exactly
                // like the img/svg family. Size + interactivity keep big
                // banner photos out.
                + "function bgUiIcon(el){try{"
                + "var r=el.getBoundingClientRect();"
                + "var m=Math.max(r.width,r.height);"
                + "if(!(m>0&&m<=64))return false;"
                + "if(iconNamed(el))return true;"
                + "var t=el.tagName;"
                + "if(t==='BUTTON'||t==='A'||t==='LABEL'||t==='SUMMARY'||t==='HEADER'||t==='NAV')return true;"
                + "if(el.getAttribute&&el.getAttribute('role')==='button')return true;"
                + "return false;}catch(e){return false}}"
                // v1.4.20 — THE THICKNESS RESOLVER (pure; probe-pinned): a
                // low-contrast UI stroke thinner than 1.5 px is bumped to
                // 2 px so it reads clearly after the inversion; anything
                // else keeps the site-drawn width
                + "function bumpW(px){try{"
                + "var w=parseFloat(px);"
                + "if(!(w>0)||w>=1.5)return null;"
                + "return '2px';}catch(e){return null}}"
                // v1.4.20 — the icon-font detector (pure-ish): glyphs drawn
                // by an icon FONT ride the text-color floor already; the
                // matcher only decides whether the extra glyph-thickening
                // (text stroke) is safe to apply (never on body text)
                + "function iconFont(cs){try{"
                + "var f=cs.fontFamily||'';"
                + "return /icon|glyph|awesome|feather|typicon|octicon|remix|dashicons|icomoon/i.test(f);"
                + "}catch(e){return false}}"
                // v1.4.19 — the stroke floor application: ONLY text color and
                // border colors (the thin-strokes/icons/text surfaces),
                // never backgrounds, never the media family (photos keep
                // their pixels; isMedia is checked by parityEl's early return
                // before this runs). Rides the existing walk — the same
                // single getComputedStyle, the same 3500 cap, the same
                // MutationObserver; no extra pass, no extra budget.
                // v1.4.20 — TWO EXTENSIONS: (a) THICKNESS — a hairline
                // (< 1.5 px) border whose color was clamped is bumped to
                // 2 px (bumpW, pure): a mid-gray 1 px line is still faint on
                // black; (b) ICON FONTS — a clamped glyph in an icon font
                // also gets a 0.45 px text stroke so the glyph itself
                // thickens (body text never matches iconFont).
                + "function strokeFloor(el,cs){try{"
                + "var l=lumOf(cs.color);"
                + "var fontIc=iconFont(cs);"
                + "if(l!==null&&l>0.8){var nc=clampCss(cs.color);"
                + "if(nc){svSave(el);el.style.color=nc;"
                + "if(fontIc){try{el.style.webkitTextStroke='0.45px currentColor';}catch(x){}}}}"
                + "if(parseFloat(cs.borderTopWidth)>0||parseFloat(cs.borderBottomWidth)>0"
                + "||parseFloat(cs.borderLeftWidth)>0||parseFloat(cs.borderRightWidth)>0){"
                + "var bl=lumOf(cs.borderTopColor);"
                + "if(bl!==null&&bl>0.8){var nb=clampCss(cs.borderTopColor);"
                + "if(nb){svSave(el);el.style.borderColor=nb;"
                + "var bw=bumpW(cs.borderTopWidth);"
                + "if(bw){try{el.style.borderWidth=bw;}catch(x){}}}}}"
                + "}catch(e){}}"
                // v1.4.20 — THE UI-ICON FLOOR (the SVG half of the faint-
                // icons report): small svgs are now WALKED (isMedia below)
                // and get the IC class, whose CSS (filter:none) lets them
                // ride the page inversion — a dark hamburger glyph inverts
                // to light and reappears on the black ground. LIGHT strokes
                // or fills (a white glyph on an inverted-dark button) are
                // clamped to the same 0.6 floor BEFORE the flip (clampCss),
                // and thin strokes are bumped (bumpW). Inside a preserved
                // dark subtree (inv 0) the icon was drawn FOR that ground —
                // untouched. All writes ride svSave (exact restore).
                + "function iconFloor(el,cs,inv){try{"
                + "if(inv!==1)return;"
                + "el.classList.add(IC);"
                + "var st=cs.stroke,fl=cs.fill;"
                + "var sl=(st&&st!=='none')?lumOf(st):null;"
                + "var fll=(fl&&fl!=='none')?lumOf(fl):null;"
                + "if(sl!==null&&sl>0.8){var ns=clampCss(st);"
                + "if(ns){svSave(el);el.style.stroke=ns;}}"
                + "if(fll!==null&&fll>0.8){var nf=clampCss(fl);"
                + "if(nf){svSave(el);el.style.fill=nf;}}"
                + "var sw=parseFloat(cs.strokeWidth)||0;"
                + "if(sw>0&&sw<1.5&&(sl!==null||fll!==null)){"
                + "svSave(el);try{el.style.strokeWidth='2';}catch(x){}}"
                + "}catch(e){}}"
                // v1.4.20 — THE PSEUDO FLOOR (bounded): ::before/::after
                // glyph icons that set their OWN light color invert into
                // near-black like every other thin stroke, but no inline
                // write can reach a pseudo — those get a generated rule keyed
                // on a data attribute (numeric, unquoted selector). Only
                // LEAVES are probed (icon placeholders are empty elements),
                // the lookups are capped (600) and the rules capped (150);
                // pseudos that merely INHERIT the element color are already
                // cured by the element-side clamp above.
                + "var psLook=0,psN=0;"
                + "function psRule(r){try{"
                + "var s=document.getElementById('bolt-psfl');"
                + "if(!s){s=document.createElement('style');s.id='bolt-psfl';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent+=r;}catch(e){}}"
                + "function pseudoFloor(el,cs){try{"
                + "if(psLook>=600||psN>=150||el.children.length>0)return;"
                + "psLook++;"
                + "var pb=null;try{pb=getComputedStyle(el,'::before');}catch(x){}"
                + "if(pb&&pb.content&&pb.content!=='none'&&pb.content!=='normal'"
                + "&&pb.color&&pb.color!==cs.color){"
                + "var pl=lumOf(pb.color);"
                + "if(pl!==null&&pl>0.8){var nc=clampCss(pb.color);"
                + "if(nc){psN++;var k=el.getAttribute('data-boltpb');"
                + "if(!k){k=''+psN;el.setAttribute('data-boltpb',k);}"
                + "psRule('[data-boltpb='+k+']::before{color:'+nc+'}');}}}"
                + "var pa=null;try{pa=getComputedStyle(el,'::after');}catch(x){}"
                + "if(pa&&pa.content&&pa.content!=='none'&&pa.content!=='normal'"
                + "&&pa.color&&pa.color!==cs.color){"
                + "var al=lumOf(pa.color);"
                + "if(al!==null&&al>0.8){var na=clampCss(pa.color);"
                + "if(na){psN++;var j=el.getAttribute('data-boltpa');"
                + "if(!j){j=''+psN;el.setAttribute('data-boltpa',j);}"
                + "psRule('[data-boltpa='+j+']::after{color:'+na+'}');}}}"
                + "}catch(e){}}"
                // the media family is un-inverted by CSS already — a leaf.
                // v1.4.20: a SMALL svg is NOT media — it is a UI icon: the
                // walk processes it (iconFloor) and it inverts with the page
                // (the dark-glyph-on-black-ground vanish). Large svgs stay
                // content (charts/illustrations keep their pixels).
                // v1.4.21: a small IMG in UI CONTEXT is chrome too — menu
                // images (menu.svg/.png) were un-inverted media and stayed
                // black; classed IC they ride the page inversion and a
                // black menu glyph flips light (context first, size second
                // — see uiIcon).
                + "function isMedia(el){var t=el.tagName;"
                + "if((t==='SVG'||t==='IMG')&&uiIcon(el))return false;"
                + "return t==='IMG'||t==='VIDEO'||t==='PICTURE'||t==='CANVAS'||t==='SVG'"
                + "||t==='IFRAME'||t==='EMBED'||t==='OBJECT'||el.getAttribute('role')==='img';}"
                // the per-element decision; inv = current filter parity of
                // the subtree this element paints into (1 = inverted once)
                + "function parityEl(el,inv){"
                + "if(isMedia(el))return inv;"
                // v1.4.21 — an IC-pinned element (background-image icon on
                // a small interactive element, pinned by pinEl/both) is a
                // LEAF like media: the walk never adds DK on top of the IC
                // filter (the two classes would fight in the sheet and the
                // parity math would drift for the subtree — one class, one
                // meaning).
                + "try{if(el.classList&&el.classList.contains(IC))return inv;}catch(x){}"
                + "var cs;try{cs=getComputedStyle(el);}catch(x){return inv;}"
                + "try{if(cs.filter&&cs.filter!=='none')return inv;}catch(x){}"   // never clobber a site's own filter
                + "var want=inv;"
                + "try{var bi=cs.backgroundImage;"
                + "if(bi&&bi.indexOf('url(')>=0){want=0;"        // photo ground keeps its overlays
                + "}else{var l=lumOf(cs.backgroundColor);"
                + "if(l!==null)want=(l<=0.35)?0:1;}}catch(x){}"
                + "if(want!==inv){try{el.classList.add(DK);n++;}catch(x){}inv=want;}"
                // v1.4.19 — a subtree that STAYS INVERTED gets the stroke
                // floor: light text/border colors that would invert into
                // near-black on the dark ground are clamped to a visible
                // mid-gray BEFORE the global filter flips them
                + "if(inv===1){try{strokeFloor(el,cs);}catch(x){}}"
                // v1.4.20 — the icon + pseudo floors ride the SAME inverted-
                // subtree gate (the parity law composes; a UI icon or pseudo
                // glyph inside a preserved dark subtree keeps its pixels).
                // v1.4.21 — IMG UI icons join the SVGs in iconFloor (the
                // class is the point; stroke/fill are no-ops on an img).
                + "if(inv===1&&(el.tagName==='SVG'||(el.tagName==='IMG'&&uiIcon(el))))"
                + "{try{iconFloor(el,cs,inv);}catch(x){}}"
                + "if(inv===1){try{pseudoFloor(el,cs);}catch(x){}}"
                + "var ch=el.children;"
                + "for(var i=0;i<ch.length;i++){if(n>=3500)break;parityEl(ch[i],inv);}"
                + "return inv;}"
                // one element through BOTH passes (the observer path)
                // v1.3.93 — the starting parity is the ANCESTOR parity
                // (the bug-hunt pass): a node mounted INSIDE a preserved
                // dark subtree inherits parity 0, not the global 1 — the
                // hardcoded 1 double-preserved late-mounted dark cards
                // (DK on top of DK = an odd inversion count = an INVERTED
                // PATCH inside the dark card it was supposed to match).
                // Each DK ancestor toggles the parity exactly once.
                + "function ancInv(e){var inv=1;try{var p=e.parentElement;"
                + "while(p&&p!==document.body){if(p.classList&&p.classList.contains(DK))inv=1-inv;"
                + "p=p.parentElement;}}catch(x){}return inv;}"
                + "function both(e){try{var cs=getComputedStyle(e);"
                + "var b=cs.backgroundImage;if(b&&b.indexOf('url(')>=0){e.classList.add(NI);n++;"
                + "if(bgUiIcon(e))e.classList.add(IC);}}catch(x){}"
                + "parityEl(e,ancInv(e));}"
                + "var all=document.body?document.body.querySelectorAll('*'):[];"
                + "var i=0;"
                + "function pinEl(e){try{var cs=getComputedStyle(e);var b=cs.backgroundImage;"
                + "if(b&&b.indexOf('url(')>=0){e.classList.add(NI);n++;"
                + "if(bgUiIcon(e))e.classList.add(IC);}}catch(x){}}"
                + "function chunk(){try{var t0=Date.now();"
                + "for(;i<all.length&&n<3500;i++){pinEl(all[i]);"
                + "if((i&63)===0&&Date.now()-t0>16)break;}}catch(x){}"
                + "if(i<all.length&&n<3500)setTimeout(chunk,40);"
                + "else{try{if(document.body)parityEl(document.body,1);}catch(x){}"
                + "try{"
                // the late-nodes observer (12 s, then rest) — added subtrees
                // go through BOTH passes (v1.3.91 ran NI only here; the
                // parity half keeps SPA-mounted dark cards preserved too)
                + "var mo=new MutationObserver(function(muts){"
                + "for(var m=0;m<muts.length;m++){var ad=muts[m].addedNodes;"
                + "for(var k=0;k<ad.length;k++){var nd=ad[k];"
                + "if(!nd||nd.nodeType!==1)continue;"
                + "try{if(nd.querySelectorAll){var sub=nd.querySelectorAll('*');"
                + "for(var s2=0;s2<sub.length&&n<4500;s2++)both(sub[s2]);}both(nd);}catch(x){}}}});"
                + "mo.observe(document.body,{childList:true,subtree:true});"
                + "setTimeout(function(){try{mo.disconnect();}catch(x){}},12000);"
                + "}catch(x){}}"
                + "}chunk();}catch(e){}})()";
    }

    /** v1.3.92 — THE FULL CLEAR: pulling the inversion (already-dark page,
     *  late re-probe flip) must also STRIP the pin/walk classes — .__boltNI
     *  and .__boltDK each carry an ACTIVE filter of their own, so leaving
     *  them on a de-inverted page paints INVERTED PATCHES over the site's
     *  own colors. Also clears the run flag so a re-inject of darkSmartJs
     *  (same document, SPA) starts clean. */
    public static String darkClearJs() {
        return "(function(){try{"
                + "var s=document.getElementById('bolt-darkcss');if(s)s.remove();"
                // v1.4.20 — the pseudo-floor sheet rides out with the rest
                + "var ps=document.getElementById('bolt-psfl');if(ps)ps.remove();"
                + "var cl=document.querySelectorAll('.__boltNI,.__boltDK,.__boltIC');"
                + "for(var i=0;i<cl.length;i++){cl[i].classList.remove('__boltNI');"
                + "cl[i].classList.remove('__boltDK');"
                + "cl[i].classList.remove('__boltIC');}"
                // v1.4.19 — THE STROKE-FLOOR RESTORE: the walk's inline
                // color/border-color writes are sticky, so pulling the
                // inversion must put the ORIGINAL inline values back (the
                // exact pre-write strings are stored in data-boltsv; null
                // means the property was never inline — remove it).
                // v1.4.20 — the store grew with the icon floor (stroke,
                // fill, stroke-width), the thickness bump (border-width)
                // and the icon-font text stroke — every field restores the
                // same exact-or-remove way.
                + "var sv=document.querySelectorAll('.__boltSV');"
                + "for(var k=0;k<sv.length;k++){var el=sv[k];var o=null;"
                + "try{o=JSON.parse(el.getAttribute('data-boltsv')||'{}');}catch(e){}"
                + "try{if(o&&o.c)el.style.color=o.c;else el.style.removeProperty('color');}catch(e){}"
                + "try{if(o&&o.b)el.style.borderColor=o.b;else el.style.removeProperty('border-color');}catch(e){}"
                + "try{if(o&&o.s)el.style.stroke=o.s;else el.style.removeProperty('stroke');}catch(e){}"
                + "try{if(o&&o.f)el.style.fill=o.f;else el.style.removeProperty('fill');}catch(e){}"
                + "try{if(o&&o.w)el.style.strokeWidth=o.w;else el.style.removeProperty('stroke-width');}catch(e){}"
                + "try{if(o&&o.W)el.style.borderWidth=o.W;else el.style.removeProperty('border-width');}catch(e){}"
                + "try{if(o&&o.t)el.style.webkitTextStroke=o.t;else el.style.removeProperty('-webkit-text-stroke');}catch(e){}"
                + "try{el.removeAttribute('data-boltsv');}catch(e){}"
                + "try{el.removeAttribute('data-boltpb');}catch(e){}"
                + "try{el.removeAttribute('data-boltpa');}catch(e){}"
                + "el.classList.remove('__boltSV');}"
                + "try{delete window.__boltDKrun;}catch(e){}"
                + "}catch(e){}})();";
    }

    /** v1.3.87 — remove an id'd style element (the already-dark gate must
     *  be able to clear an inversion a previous page state asked for —
     *  cssInjectOnce's contract with the adblock pass is no-op on empty). */
    public static String styleRemoveJs(String id) {
        return "(function(){try{var s=document.getElementById('" + id + "');if(s)s.remove();}catch(e){}})();";
    }

    /** page-data wipe executed on the live page before cookie expiry (site data clearing). */
    public static String wipePageData() {
        return "(function(){try{"
                + "try{localStorage.clear();}catch(e){}"
                + "try{sessionStorage.clear();}catch(e){}"
                + "try{var cs=document.cookie.split(';');for(var i=0;i<cs.length;i++){var k=cs[i].split('=')[0].trim();"
                + "document.cookie=k+'=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';"
                + "var ds=document.location.hostname.split('.');var dom='';"
                + "for(var j=ds.length-1;j>=0;j--){dom=(j===ds.length-1?ds[j]:ds[j]+'.'+dom);"
                + "document.cookie=k+'=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/;domain='+dom;}}}catch(e){}"
                + "try{indexedDB.databases().then(function(dbs){dbs.forEach(function(db){indexedDB.deleteDatabase(db.name);});});}catch(e){}"
                + "try{caches.keys().then(function(ks){ks.forEach(function(k){caches.delete(k);});});}catch(e){}"
                + "try{navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){r.unregister();});});}catch(e){}"
                + "try{window.stop();}catch(e){}"
                + "}catch(e){}})();";
    }
}
