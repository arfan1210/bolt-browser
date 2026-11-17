/*!
 * Bolt WebAuthn Compatibility Layer — v1.2 (bolt v1.3.26)
 * =====================================================
 * Makes Bolt Browser's in-app WebAuthn bridge look like a fully capable
 * Chrome/Android passkey client to relying parties (github.com,
 * accounts.google.com, webauthn.io, ...).
 *
 * WHAT IT FIXES
 * -------------
 *  1. GitHub's "This browser or device is reporting partial passkey
 *     support. Authentication failed."  — caused by an incomplete
 *     PublicKeyCredential surface: missing getClientCapabilities(),
 *     missing parseCreationOptionsFromJSON()/parseRequestOptionsFromJSON(),
 *     missing isConditionalMediationAvailable()/isUserVerifying*Available(),
 *     and credentials that don't implement toJSON() / proper rawId shape.
 *  2. Google's "unsupported browser / passkeys not available here" gates —
 *     caused by WebView fingerprints: navigator.userAgentData missing or
 *     exposing no "Google Chrome" brand, no platformVersion hint, etc.
 *  3. GitHub's "Passkey registration failed" (v1.3.26): their bundled
 *     webauthn-json serializer derives JSON from credential METHODS
 *     (getClientExtensionResults(), response.getTransports()) — a shape
 *     with properties only crashed it. The shaped credential now carries
 *     the full method surface of a real PublicKeyCredential.
 *
 * HOW TO USE
 * ----------
 *  Inject this script at DOCUMENT START in the main world, on every page
 *  (see BoltPasskeyCompat.kt — it also fills the __PLACEHOLDER__ values).
 *
 *  If your native bridge is NOT installed as navigator.credentials, bind it:
 *      BoltWebAuthnCompat.registerBridge({
 *          create: function (options) { ... },   // -> Promise<rawCredential>
 *          get:   function (options) { ... },    // -> Promise<rawCredential>
 *          cancel: function () { ... }           // optional
 *      });
 *
 *  Runtime overrides — set BEFORE this script runs (document-start):
 *      window.BoltWebAuthnCompat = { platformVersion: "14.0.0", debug: true, ... }
 *
 * DESIGN NOTES
 * ------------
 *  - "Wrap, don't replace": every API that already exists (e.g. your own
 *    bridge's create/get) is kept; only MISSING pieces are polyfilled.
 *  - The reported capability matrix mirrors real Chrome 133+ on Android,
 *    which is the shape GitHub/Google's detection code expects.
 *  - Conditional mediation ("autofill passkeys") is reported as supported;
 *    pending conditional requests are parked silently and get superseded
 *    (NotAllowedError) the moment a modal ceremony starts — exactly how
 *    Chrome aborts them, and how GitHub tolerates it.
 *  - All rejections use real DOMException names (NotAllowedError,
 *    InvalidStateError, SecurityError, ...) — GitHub relies on these.
 *
 * License: MIT. Tested against Chromium 156 WebView (Android 10+).
 */
;(function () {
  'use strict'

  if (window.__BOLT_WEBAUTHN_COMPAT_LOADED__) { return }
  window.__BOLT_WEBAUTHN_COMPAT_LOADED__ = true

  /* =====================================================================
   * 1. CONFIGURATION
   * ===================================================================== */

  // placeholder(): lets BoltPasskeyCompat.kt substitute real values into
  // this file before injection; falls back to sane defaults otherwise.
  function placeholder (v, fallback) {
    return (typeof v === 'string' && v.length > 0 && v.indexOf('__') !== 0) ? v : fallback
  }

  var DEFAULTS = {
    // ---- engine identity (auto-filled by BoltPasskeyCompat.kt) ----
    chromeMajor: parseInt(placeholder('__CHROME_MAJOR__', '156'), 10) || 156,
    chromeFullVersion: placeholder('__CHROME_FULL__', '156.0.8078.47'),

    // ---- OS identity seen by websites (user-agent client hints) ----
    // Real Chrome freezes the UA string at "Android 10; K" for EVERY device
    // (UA reduction) and reports the true version only via the
    // platformVersion client hint. So we keep the UA Chrome-accurate and
    // control the OS claim here. "14.0.0" = claim Android 14.
    platformVersion: placeholder('__PLATFORM_VERSION__', '14.0.0'),
    deviceModel: placeholder('__DEVICE_MODEL__', ''),

    // ---- identity spoofing switches ----
    spoofUserAgentData: true,      // navigator.userAgentData -> Chrome (shape DERIVED from the live UA, v1.3.88)
    spoofNavigatorPlatform: true,  // navigator.platform -> derived from the live UA (v1.3.88)
    spoofUserAgent: false,         // only needed if UA not fixed on the Kotlin side
    userAgentOverride: '',         // used when spoofUserAgent === true

    // ---- capability matrix (mirror of Chrome 133+ / Android) ----
    conditionalGet: true,          // claim conditional mediation (autofill) support
    conditionalMode: 'silent',     // 'silent' (park & supersede) | 'immediate' (prompt at once) | 'off'
    userVerifyingPlatformAuthenticator: true,
    passkeyPlatformAuthenticator: true,
    hybridTransport: true,         // cross-device / QR (caBLE) flow
    claimSignalAPIs: false,        // report signal*Available(): true (impls are no-ops)

    // ---- ceremony behaviour ----
    ceremonyTimeoutMs: 0,          // >0 auto-rejects a hung native ceremony (ms)
    trustExistingCredentials: true,// fall back to pre-existing navigator.credentials

    // v1.3.24: WebView 156+ ships the full modern PublicKeyCredential surface
    // NATIVELY — but on Android <=13 the native probes answer FALSE (the GMS
    // FIDO2 browser gate), which is exactly what GitHub reads as "partial
    // passkey support". The layer must REPLACE those native probes, not
    // merely fill in missing ones. When a working bridge is bound (ours),
    // every claimed capability is backed by a real ceremony.
    forceStaticProbes: true,       // override native static probes that report false

    // ---- misc ----
    debug: false
  }

  var CONFIG = {}
  for (var dkey in DEFAULTS) {
    if (Object.prototype.hasOwnProperty.call(DEFAULTS, dkey)) { CONFIG[dkey] = DEFAULTS[dkey] }
  }
  // Native glue may pre-set window.BoltWebAuthnCompat = {...} before us.
  if (typeof window.BoltWebAuthnCompat === 'object' && window.BoltWebAuthnCompat !== null) {
    for (var ukey in window.BoltWebAuthnCompat) {
      if (Object.prototype.hasOwnProperty.call(window.BoltWebAuthnCompat, ukey)) {
        CONFIG[ukey] = window.BoltWebAuthnCompat[ukey]
      }
    }
  }

  /* =====================================================================
   * 2. SMALL HELPERS
   * ===================================================================== */

  var LOG = 'BoltWebAuthn'
  function log () {
    if (CONFIG.debug && window.console) {
      try { console.info.apply(console, ['[' + LOG + ']'].concat([].slice.call(arguments))) } catch (e) {}
    }
  }

  function webauthnError (name, message) {
    try { return new DOMException(message, name) } catch (e) {
      var err = new Error(message)
      err.name = name
      return err
    }
  }

  function toArrayBuffer (buf) {
    if (buf == null) { return null }
    if (buf instanceof ArrayBuffer) { return buf }
    if (ArrayBuffer.isView(buf)) {
      var ab = new ArrayBuffer(buf.byteLength)
      new Uint8Array(ab).set(new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength))
      return ab
    }
    return null
  }

  // Tolerant base64url -> ArrayBuffer (also passes ArrayBuffers through).
  function b64uToBuf (s) {
    if (s == null) { return null }
    if (s instanceof ArrayBuffer || ArrayBuffer.isView(s)) { return toArrayBuffer(s) }
    if (typeof s !== 'string') { return null }
    var b64 = s.replace(/-/g, '+').replace(/_/g, '/')
    while (b64.length % 4 !== 0) { b64 += '=' }
    var bin
    try { bin = atob(b64) } catch (e) { return null }
    var bytes = new Uint8Array(bin.length)
    for (var i = 0; i < bin.length; i++) { bytes[i] = bin.charCodeAt(i) }
    return bytes.buffer
  }

  function bufToB64u (buf) {
    if (buf == null) { return null }
    var bytes = (buf instanceof Uint8Array) ? buf : new Uint8Array(toArrayBuffer(buf) || new ArrayBuffer(0))
    var bin = ''
    for (var i = 0; i < bytes.length; i += 0x8000) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000))
    }
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  }

  function copyObj (o) {
    if (o == null || typeof o !== 'object') { return o }
    var out = {}
    for (var k in o) {
      if (Object.prototype.hasOwnProperty.call(o, k)) { out[k] = o[k] }
    }
    return out
  }

  /* =====================================================================
   * 3. CHROME / ANDROID IDENTITY (User-Agent Client Hints spoof)
   * =====================================================================
   * accounts.google.com and github.com read navigator.userAgentData
   * (brands + getHighEntropyValues) to decide whether passkeys are
   * "supported on this device". WebView exposes no "Google Chrome" brand
   * (and often none at all), which fails their gates. We shadow it with a
   * Chrome-shaped object that is consistent with the UA string set from
   * BoltPasskeyCompat.kt.
   *
   * v1.3.88 — THE SPOOF MUST AGREE WITH THE LIVE UA. The desktop-site
   * toggle and the UA chooser change navigator.userAgent via
   * WebSettings.setUserAgentString; a spoof hardcoded to mobile:true /
   * platform:'Android' made every hints-reading site (Google, YouTube,
   * X...) serve the MOBILE layout on a desktop UA — "desktop site does
   * nothing". The shape is now DERIVED from the live UA string (read
   * BEFORE any override installs): Windows/macOS desktop UAs report
   * mobile:false + the matching platform; Firefox/Safari UAs expose NO
   * userAgentData at all (like the real browsers, which never shipped
   * UA-CH); Android keeps the original Chrome-on-Android shape. Request
   * headers need no work: WebView only attaches Sec-CH-* headers for the
   * DEFAULT UA string (Google's UA-reduction note), so a custom UA cannot
   * contradict the JS side.
   */

  var LIVE_UA = ''
  try { LIVE_UA = String(navigator.userAgent || '') } catch (eL) { LIVE_UA = '' }
  var UA_WIN = LIVE_UA.indexOf('Windows NT') !== -1
  var UA_MAC = LIVE_UA.indexOf('Macintosh') !== -1
    || (LIVE_UA.indexOf('Mac OS X') !== -1
      && LIVE_UA.indexOf('iPhone') === -1 && LIVE_UA.indexOf('iPad') === -1)
  var UA_IOS = LIVE_UA.indexOf('iPhone') !== -1 || LIVE_UA.indexOf('iPad') !== -1
  var UA_ANDROID = !UA_WIN && !UA_MAC && !UA_IOS && LIVE_UA.indexOf('Android') !== -1
  var UA_FIREFOX = LIVE_UA.indexOf('Firefox') !== -1
    && LIVE_UA.indexOf('Chrome') === -1 && LIVE_UA.indexOf('Chromium') === -1
  var UA_SAFARI = !UA_FIREFOX && LIVE_UA.indexOf('Safari') !== -1
    && LIVE_UA.indexOf('Chrome') === -1 && LIVE_UA.indexOf('Chromium') === -1
  // real Firefox/Safari never implemented navigator.userAgentData
  var UA_NO_HINTS = UA_FIREFOX || UA_SAFARI
  var UA_MOBILE = !UA_WIN && !UA_MAC && LIVE_UA.indexOf('Mobile') !== -1
  var UA_PLATFORM = UA_WIN ? 'Windows' : UA_MAC ? 'macOS' : UA_IOS ? 'iOS' : 'Android'
  var UA_PLATFORM_VERSION = UA_WIN ? '15.0.0' : UA_MAC ? '14.5.0'
    : UA_IOS ? '17.4.0' : CONFIG.platformVersion
  var UA_NAV_PLATFORM = UA_WIN ? 'Win32' : UA_MAC ? 'MacIntel'
    : UA_IOS ? 'iPhone' : 'Linux armv8l'

  // v1.3.99 — THE VERSION COMES FROM THE LIVE UA STRING (the identity-
  // agreement law, JS-fallback half). The brands and high-entropy hints
  // used to report the DETECTED WebView engine (e.g. 156) even when the
  // live UA string was the frozen desktop preset (Chrome/124): a
  // navigator.userAgent and a navigator.userAgentData that disagree about
  // the browser version is a bot fingerprint, and grok.com's Cloudflare
  // gate (accept-ch + critical-ch) reads exactly that pair. On WebViews
  // with the native metadata feature this spoof shadows the JS surface
  // while the Sec-CH headers come from the native side — BOTH must agree
  // with the string. Whatever Chrome/N the STRING claims, the hints now
  // claim the same N (fallback: the detected engine — which is also what
  // Tab.applyUa composes the desktop UA from now, so all three agree in
  // the steady state).
  var UA_CHROME_MAJOR = CONFIG.chromeMajor
  var UA_CHROME_FULL = CONFIG.chromeFullVersion
  try {
    var mCh = /Chrome\/(\d+)(?:\.(\d+)\.(\d+)\.(\d+))?/.exec(LIVE_UA)
    if (mCh) {
      var parsedMaj = parseInt(mCh[1], 10)
      if (parsedMaj > 0) {
        UA_CHROME_MAJOR = parsedMaj
        // same engine as detected -> the DETECTED full version is the honest
        // hint (real Chrome: the REDUCED UA string says .0.0.0 while the
        // high-entropy hints carry the true build — a reduction, not a
        // contradiction). a DIFFERENT major (a stale preset string) can
        // only honestly claim what the string itself says.
        if (parsedMaj !== (parseInt(CONFIG.chromeMajor, 10) || -1)) {
          UA_CHROME_FULL = mCh[0].substring('Chrome/'.length)
        }
      }
    }
  } catch (eV) {}

  // GREASE brands rotate by Chromium version; any well-formed GREASE is fine.
  var GREASE_TABLE = ['Not(A:Brand', 'Not-A.Brand', 'Not/A)Brand', 'Not;A=Brand', 'Not A(Brand', 'Not?A_Brand']
  function greaseBrand () {
    var v = UA_CHROME_MAJOR || 0
    return GREASE_TABLE[(v + 2) % GREASE_TABLE.length]
  }

  function brandList (fullVersion) {
    var v = fullVersion ? UA_CHROME_FULL : String(UA_CHROME_MAJOR)
    return [
      { brand: 'Chromium', version: v },
      { brand: greaseBrand(), version: '99' },
      { brand: 'Google Chrome', version: v }
    ]
  }

  var uaDataObject = {
    brands: brandList(false),
    mobile: UA_MOBILE,
    platform: UA_PLATFORM,
    getHighEntropyValues: function (hints) {
      var requested = []
      if (hints && typeof hints[Symbol.iterator] === 'function') {
        try { requested = [].slice.call(hints) } catch (e) { requested = [] }
      }
      var values = {}
      for (var i = 0; i < requested.length; i++) {
        var h = requested[i]
        switch (h) {
          case 'architecture': values.architecture = (UA_WIN || UA_MAC) ? 'x86' : 'arm'; break
          case 'bitness': values.bitness = '64'; break
          case 'model': values.model = UA_ANDROID ? (CONFIG.deviceModel || '') : ''; break
          case 'platformVersion': values.platformVersion = UA_PLATFORM_VERSION; break
          case 'uaFullVersion': values.uaFullVersion = UA_CHROME_FULL; break
          case 'fullVersionList': values.fullVersionList = brandList(true); break
          case 'version': values.version = String(UA_CHROME_MAJOR); break
          case 'wow64': values.wow64 = false; break
          default: break // unknown hint -> simply not returned (Chrome behaves similarly)
        }
      }
      return Promise.resolve(values)
    }
  }

  if (CONFIG.spoofUserAgentData) {
    if (UA_NO_HINTS) {
      // Firefox / Safari ship no navigator.userAgentData — remove it so
      // the page cannot read a Chromium object that contradicts the UA
      try { delete Navigator.prototype.userAgentData } catch (eD) {}
      try {
        Object.defineProperty(Navigator.prototype, 'userAgentData', {
          get: function () { return undefined }, configurable: true
        })
      } catch (e) {}
      try {
        Object.defineProperty(navigator, 'userAgentData', {
          get: function () { return undefined }, configurable: true
        })
      } catch (e2) {}
      log('userAgentData removed — ' + (UA_FIREFOX ? 'Firefox' : 'Safari') + ' UA')
    } else {
      var installedUAData = false
      try {
        Object.defineProperty(Navigator.prototype, 'userAgentData', {
          get: function () { return uaDataObject },
          configurable: true
        })
        installedUAData = true
      } catch (e) { /* frozen prototype? fall back to instance */ }
      if (!installedUAData) {
        try { Object.defineProperty(navigator, 'userAgentData', { get: function () { return uaDataObject }, configurable: true }) } catch (e2) {}
      }
      log('userAgentData spoofed as Chrome ' + UA_CHROME_MAJOR + ' / ' + UA_PLATFORM
        + (UA_MOBILE ? ' (mobile)' : ' (desktop)'))
    }
  }

  if (CONFIG.spoofNavigatorPlatform) {
    var platformInstalled = false
    try {
      Object.defineProperty(Navigator.prototype, 'platform', { get: function () { return UA_NAV_PLATFORM }, configurable: true })
      platformInstalled = true
    } catch (e) { /* no Navigator global / frozen prototype */ }
    if (!platformInstalled) {
      try { Object.defineProperty(navigator, 'platform', { get: function () { return UA_NAV_PLATFORM }, configurable: true }) } catch (e2) {}
    }
  }

  if (CONFIG.spoofUserAgent && CONFIG.userAgentOverride) {
    try { Object.defineProperty(Navigator.prototype, 'userAgent', { get: function () { return CONFIG.userAgentOverride }, configurable: true }) } catch (e) {}
  }

  /* =====================================================================
   * 3b. v1.3.89 — THE DESKTOP VIEWPORT (desktop site / user agent,
   * round 3). The UA string + client-hints spoofs alone do NOT flip a
   * responsive site: apps like code.qwen.ai and the z.ai dashboard pick
   * their layout from CSS media queries / window.innerWidth, and a
   * phone-width viewport serves the mobile layout (or the "please use a
   * desktop device" wall) no matter what the UA says. Chrome's own
   * "Request desktop site" widens the layout viewport for exactly this
   * reason. When the LIVE UA is a desktop one (Windows/macOS, not
   * mobile), every <meta name=viewport> is forced to width=1280 — wide
   * enough for the >=1024 desktop breakpoints — while
   * loadWithOverviewMode scales the wide layout to fit the screen.
   * v1.3.98 — THE FORCE IS A LIVE GUARD, NOT A LOAD-TIME PASS (the field:
   * "the desktop site works temporarily and [falls back] maybe when the
   * link changes like within the same website"). The v1.3.89 shape missed
   * BOTH ways an SPA re-collapses the viewport after load: (a) frameworks
   * REWRITE the existing meta's content attribute on route change /
   * hydration (react-helmet, Next.js metadata, vue-meta) — an ATTRIBUTE
   * mutation, which the old observer never even subscribed to (it read
   * only addedNodes); (b) the observer disconnected 4 s after
   * DOMContentLoaded, so any rewrite a minute later — every soft
   * navigation — landed unanswered and innerWidth fell back under the
   * site's mobile breakpoint (qwen's store: window.innerWidth < 768 ->
   * setMobile(true)). The observer now subscribes to attribute changes
   * too and stays for the document's whole life — it wakes only when a
   * viewport meta is actually inserted or rewritten (a resting observer
   * costs nothing; the callback is a tagName check per mutation record),
   * and dwForce is idempotent (>=1024 is left alone), so the page's own
   * rewrites are answered in one round-trip and never loop. Plus: a page
   * with NO viewport meta at sweep time gets one INJECTED (WebView's
   * meta-less fallback is 980 px — below the >=1024 desktop breakpoints),
   * and a pageshow sweep covers bfcache restores.
   * Mobile/iPhone UAs are untouched — the page's own viewport rules.
   * =====================================================================
   */
  if ((UA_WIN || UA_MAC) && !UA_MOBILE) {
    try {
      var DW_W = 1280
      function dwForce (m) {
        try {
          var c = m.getAttribute('content') || ''
          var w = /width\s*=\s*([\d.]+)/.exec(c)
          if (w && parseFloat(w[1]) >= 1024) return   // already desktop-wide
          m.setAttribute('content', 'width=' + DW_W)
        } catch (e) {}
      }
      function dwIsViewportMeta (n) {
        try {
          return n && n.nodeType === 1 && n.tagName === 'META'
            && (n.getAttribute('name') || '').toLowerCase() === 'viewport'
        } catch (e) { return false }
      }
      function dwSweep () {
        try {
          var ms = document.querySelectorAll('meta[name="viewport" i], meta[name=viewport]')
          for (var i = 0; i < ms.length; i++) dwForce(ms[i])
          // v1.3.98 — the missing-meta corpse: a desktop page with NO
          // viewport meta lays out at WebView's 980 px fallback and misses
          // the >=1024 breakpoints — inject the wide viewport ourselves.
          if (!ms.length) {
            var m = document.createElement('meta')
            m.setAttribute('name', 'viewport')
            m.setAttribute('content', 'width=' + DW_W)
            ;(document.head || document.documentElement).appendChild(m)
          }
        } catch (e) {}
      }
      var dwObs = new MutationObserver(function (muts) {
        try {
          for (var i = 0; i < muts.length; i++) {
            var mu = muts[i]
            if (mu.type === 'attributes') {
              // v1.3.98 — the page REWROTE an existing meta (the SPA route
              // change corpse): answer it at once
              if (dwIsViewportMeta(mu.target)) dwForce(mu.target)
              continue
            }
            var added = mu.addedNodes
            for (var j = 0; j < added.length; j++) {
              var n = added[j]
              if (!n || n.nodeType !== 1) continue
              var tag = n.tagName
              if (tag === 'META') {
                var nm = (n.getAttribute('name') || '').toLowerCase()
                if (nm === 'viewport') dwForce(n)
              } else if (tag === 'HEAD') {
                dwSweep()
              }
            }
          }
        } catch (e) {}
      })
      // v1.3.98 — attributes:true + attributeFilter: content/name changes on
      // existing metas are now visible; NO disconnect — the guard lives as
      // long as the document does (see the block comment for why that is
      // cheap). The parser-inserted meta still gets rewritten before first
      // layout, exactly as in v1.3.89.
      try {
        dwObs.observe(document.documentElement, {
          childList: true, subtree: true,
          attributes: true, attributeFilter: ['content', 'name']
        })
      } catch (eO) {}
      document.addEventListener('DOMContentLoaded', function () {
        dwSweep()
        // v1.3.98 — the 4 s rest is GONE (see the block comment): a soft
        // navigation a minute later must find the guard still standing.
      })
      // v1.3.98 — bfcache restores re-sweep (the metas may have been left
      // mid-rewrite when the page was frozen)
      window.addEventListener('pageshow', function () { dwSweep() })
      log('desktop viewport forced to ' + DW_W + 'px (live guard)')
    } catch (eDW) {}
  }

  /* =====================================================================
   * 4. WEBAUTHN CONTEXT GUARD
   * =====================================================================
   * WebAuthn only exists in secure contexts. Never fake it on http://
   * pages — that would break spec-sensitive relying parties.
   */
  var secureContext = (function () {
    if (typeof window.isSecureContext === 'boolean') { return window.isSecureContext }
    var h = (location.hostname || '').toLowerCase()
    return location.protocol === 'https:' || h === 'localhost' || h === '127.0.0.1' || h === '[::1]'
  })()

  if (!secureContext) {
    log('insecure context — WebAuthn compat layer disabled')
    window.BoltWebAuthnCompat = {
      version: '1.0', config: CONFIG, active: false,
      registerBridge: function (b) { window.__BOLT_PENDING_BRIDGE__ = b }
    }
    return
  }

  /* =====================================================================
   * 5. PublicKeyCredential SURFACE COMPLETION
   * =====================================================================
   * GitHub's detection path (same root cause as VS Code's browser panel,
   * qutebrowser and gitify "partial passkey support" reports):
   *   - window.PublicKeyCredential must exist
   *   - PublicKeyCredential.parseCreationOptionsFromJSON / .parseRequestOptionsFromJSON
   *     must be functions (WebAuthn L3 JSON API)
   *   - PublicKeyCredential.isConditionalMediationAvailable() must exist
   *   - PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()
   *     must exist and resolve true
   *   - PublicKeyCredential.getClientCapabilities() (Chrome 133+) must
   *     return a complete matrix
   * We patch ONLY what is missing — an already-working bridge keeps its
   * own implementations.
   */

  var nativePKC = (typeof window.PublicKeyCredential === 'function')
  var PKC = window.PublicKeyCredential
  var stubbedPKC = false

  if (typeof PKC !== 'function') {
    stubbedPKC = true
    PKC = function PublicKeyCredential () {
      throw new TypeError('Illegal constructor')
    }
    PKC.__boltStub = true
    try { window.PublicKeyCredential = PKC } catch (e) { /* read-only? bail out */ }
  }

  function defineStatic (name, fn) {
    if (typeof PKC[name] !== 'function') {
      try { PKC[name] = fn } catch (e) { log('cannot install static', name) }
    } else {
      log('keeping existing static', name)
    }
  }

  // v1.3.24: force-REPLACE a native static. The probe statics exist natively
  // in WebView 156+, and on Android <=13 they answer false (GMS FIDO2 gate) —
  // "partial passkey support" at every relying party. "Patch only what is
  // missing" kept the broken natives; these four are overridden outright,
  // backed by our real bridge when one is bound.
  function forceDefineStatic (name, fn) {
    if (!CONFIG.forceStaticProbes) { return defineStatic(name, fn) }
    try {
      if (typeof PKC[name] === 'function') { log('replacing native static', name) }
      PKC[name] = fn
    } catch (e) {
      try { Object.defineProperty(PKC, name, { value: fn, writable: true, configurable: true }) }
      catch (e2) { log('cannot force static', name) }
    }
  }

  function capabilityMatrix () {
    return {
      conditionalCreate: false,                       // not shipped in Chrome either
      conditionalGet: !!(CONFIG.conditionalGet && CONFIG.conditionalMode !== 'off'),
      hybridTransport: !!CONFIG.hybridTransport,
      passkeyPlatformAuthenticator: !!CONFIG.passkeyPlatformAuthenticator,
      userVerifyingPlatformAuthenticator: !!CONFIG.userVerifyingPlatformAuthenticator,
      relatedOrigins: false,                          // Chrome: not shipped by default
      signalAllAcceptedCredentials: !!CONFIG.claimSignalAPIs,
      signalCurrentUserDetails: !!CONFIG.claimSignalAPIs,
      signalUnknownCredential: !!CONFIG.claimSignalAPIs
    }
  }

  // v1.3.24: forced too — the native matrix reports the REAL device state
  // (userVerifyingPlatformAuthenticator: false, conditionalGet: false on a
  // gated Android <=13 WebView), which reads as "partial support". Ours is
  // backed by the bound bridge.
  forceDefineStatic('getClientCapabilities', function () {
    return Promise.resolve(capabilityMatrix())
  })

  // v1.3.24: these three are the probes GitHub/Google read directly — force.
  forceDefineStatic('isConditionalMediationAvailable', function () {
    return Promise.resolve(!!(CONFIG.conditionalGet && CONFIG.conditionalMode !== 'off'))
  })

  forceDefineStatic('isUserVerifyingPlatformAuthenticatorAvailable', function () {
    var b = resolveBridge()
    if (b && typeof b.isUserVerifyingPlatformAuthenticatorAvailable === 'function') {
      try {
        return Promise.resolve(b.isUserVerifyingPlatformAuthenticatorAvailable())
          .then(function (v) { return v !== false })
      } catch (e) { /* fall through */ }
    }
    return Promise.resolve(!!CONFIG.userVerifyingPlatformAuthenticator)
  })

  forceDefineStatic('isPasskeyPlatformAuthenticatorAvailable', function () {
    var b = resolveBridge()
    if (b && typeof b.isPasskeyPlatformAuthenticatorAvailable === 'function') {
      try {
        return Promise.resolve(b.isPasskeyPlatformAuthenticatorAvailable())
          .then(function (v) { return v !== false })
      } catch (e) { /* fall through */ }
    }
    return Promise.resolve(!!CONFIG.passkeyPlatformAuthenticator)
  })

  // ---- WebAuthn L3 JSON serialization API -------------------------------
  function parseCreationOptions (input) {
    var o = (typeof input === 'string') ? JSON.parse(input) : input
    if (!o || typeof o !== 'object') { throw new TypeError('PublicKeyCredentialCreationOptionsJSON: expected object') }
    var user = o.user || {}
    var out = {
      rp: copyObj(o.rp || {}),
      user: {
        id: b64uToBuf(user.id),
        name: user.name,
        displayName: user.displayName
      },
      challenge: b64uToBuf(o.challenge),
      pubKeyCredParams: (o.pubKeyCredParams || []).map(copyObj)
    }
    if (o.timeout != null) { out.timeout = o.timeout }
    if (o.excludeCredentials) {
      out.excludeCredentials = o.excludeCredentials.map(function (c) {
        var x = copyObj(c); x.id = b64uToBuf(c.id); return x
      })
    }
    if (o.authenticatorSelection) { out.authenticatorSelection = copyObj(o.authenticatorSelection) }
    if (o.attestation != null) { out.attestation = o.attestation }
    if (o.attestationFormats) { out.attestationFormats = o.attestationFormats.slice() }
    if (o.extensions) { out.extensions = copyObj(o.extensions) }
    return out
  }

  function parseRequestOptions (input) {
    var o = (typeof input === 'string') ? JSON.parse(input) : input
    if (!o || typeof o !== 'object') { throw new TypeError('PublicKeyCredentialRequestOptionsJSON: expected object') }
    var out = {
      challenge: b64uToBuf(o.challenge)
    }
    if (o.timeout != null) { out.timeout = o.timeout }
    if (o.rpId != null) { out.rpId = o.rpId }
    if (o.allowCredentials) {
      out.allowCredentials = o.allowCredentials.map(function (c) {
        var x = copyObj(c); x.id = b64uToBuf(c.id); return x
      })
    }
    if (o.userVerification != null) { out.userVerification = o.userVerification }
    if (o.attestation != null) { out.attestation = o.attestation }
    if (o.extensions) { out.extensions = copyObj(o.extensions) }
    return out
  }

  defineStatic('parseCreationOptionsFromJSON', function (optionsJSON) { return parseCreationOptions(optionsJSON) })
  defineStatic('parseRequestOptionsFromJSON', function (optionsJSON) { return parseRequestOptions(optionsJSON) })

  // ---- WebAuthn signals (Chrome-style no-ops) ---------------------------
  defineStatic('signalAllAcceptedCredentials', function (rpId, userId, allAcceptedCredentials) {
    log('signalAllAcceptedCredentials ignored', rpId)
    return Promise.resolve()
  })
  defineStatic('signalCurrentUserDetails', function (rpId, currentUserDetails) {
    log('signalCurrentUserDetails ignored', rpId)
    return Promise.resolve()
  })
  defineStatic('signalUnknownCredential', function (rpId, credentialId) {
    log('signalUnknownCredential ignored', rpId)
    return Promise.resolve()
  })

  // prototype-level toJSON() (WebAuthn L3)
  if (typeof PKC.prototype.toJSON !== 'function') {
    try {
      PKC.prototype.toJSON = function () { return credentialToJSON(this) }
    } catch (e) { /* non-configurable prototype */ }
  }

  /* =====================================================================
   * 6. NATIVE BRIDGE BINDING
   * =====================================================================
   * Resolution order (first match wins):
   *   1. BoltWebAuthnCompat.registerBridge({...})  — explicit glue
   *   2. well-known injected interfaces (probed by name)
   *   3. the pre-existing navigator.credentials.create/get — this is the
   *      common case for Bolt's "browser mode" bridge, which installs
   *      itself as navigator.credentials before this layer runs.
   * A bridge result may be shaped loosely: { rawId|id, response|itself,
   * ... } with binary fields as ArrayBuffer, TypedArray OR base64url
   * strings — everything is normalized before the page sees it.
   */

  var nativeBridge = null

  // v1.3.24: Bolt's NATIVE Java authenticator (BoltAuthenticator.java),
  // injected as window.BoltWebAuthn via addJavascriptInterface — available
  // at document start in EVERY document with zero injection-timing risk.
  // Protocol: createPasskey(json)/getPasskey(json) take a request JSON string
  // and return a response JSON string synchronously (the JavaBridge thread
  // blocks on the user-consent dialog); {ok:true,result:{...}} on success,
  // {ok:false,error:{name,message}} on failure. This adapter turns that
  // into the Promise-based bridge shape the ceremony wrapper expects.
  function boltNativeBridge () {
    var iface = null
    try { iface = window.BoltWebAuthn } catch (e) {}
    if (!iface || typeof iface.createPasskey !== 'function'
        || typeof iface.getPasskey !== 'function') { return null }
    function b64uOf (v) {
      if (v == null) { return '' }
      if (typeof v === 'string') { return v }
      var ab = toArrayBuffer(v)
      return ab ? bufToB64u(ab) : ''
    }
    function callNative (method, payload) {
      var raw
      try { raw = method.call(iface, JSON.stringify(payload)) }
      catch (e) { throw mapBridgeError(e) }
      var res
      try { res = JSON.parse(raw) } catch (e) {
        throw webauthnError('NotAllowedError', 'authenticator protocol error')
      }
      if (res && res.ok) { return res.result }
      throw mapBridgeError(res && res.error)
    }
    var adapter = {
      __boltNative: true,
      create: function (options) {
        var rpId = (options.rp && options.rp.id) || location.hostname
        var sel = options.authenticatorSelection || {}
        var payload = {
          origin: location.origin,
          rpId: rpId,
          rpName: (options.rp && options.rp.name) || rpId,
          challenge: b64uOf(options.challenge),
          user: options.user ? {
            id: b64uOf(options.user.id),
            name: options.user.name || '',
            displayName: options.user.displayName || options.user.name || ''
          } : null,
          residentKey: sel.residentKey || 'preferred',
          userVerification: sel.userVerification || 'preferred',
          attestation: options.attestation || 'none',
          excludeCredentials: (options.excludeCredentials || []).map(function (c) {
            return b64uOf(c.id)
          }),
          timeout: options.timeout || 180000
        }
        return Promise.resolve().then(function () {
          return callNative(iface.createPasskey, payload)
        })
      },
      get: function (options) {
        var payload = {
          origin: location.origin,
          rpId: options.rpId || location.hostname,
          challenge: b64uOf(options.challenge),
          allowCredentials: (options.allowCredentials || []).map(function (c) {
            return b64uOf(c.id)
          }),
          userVerification: options.userVerification || 'preferred',
          timeout: options.timeout || 180000
        }
        return Promise.resolve().then(function () {
          return callNative(iface.getPasskey, payload)
        })
      },
      cancel: function () {
        try { iface.cancel() } catch (e) {}
      },
      isUserVerifyingPlatformAuthenticatorAvailable: function () {
        try { return Promise.resolve(iface.isAvailable() !== false) }
        catch (e) { return Promise.resolve(true) }
      },
      isPasskeyPlatformAuthenticatorAvailable: function () {
        try { return Promise.resolve(iface.isAvailable() !== false) }
        catch (e) { return Promise.resolve(true) }
      }
    }
    log('native BoltAuthenticator bridge bound (window.BoltWebAuthn)')
    return adapter
  }

  // keep references to whatever existed before us
  var credsContainer = (function () {
    try { return navigator.credentials || null } catch (e) { return null }
  })()
  var origCreate = (credsContainer && typeof credsContainer.create === 'function')
    ? credsContainer.create.bind(credsContainer) : null
  var origGet = (credsContainer && typeof credsContainer.get === 'function')
    ? credsContainer.get.bind(credsContainer) : null
  var origStore = (credsContainer && typeof credsContainer.store === 'function')
    ? credsContainer.store.bind(credsContainer) : null
  var origPreventSilentAccess = (credsContainer && typeof credsContainer.preventSilentAccess === 'function')
    ? credsContainer.preventSilentAccess.bind(credsContainer) : null

  function adaptBridge (b) {
    if (!b) { return null }
    var out = {
      create: b.create || b.register || b.createCredential || b.attest || null,
      get: b.get || b.authenticate || b.assert || b.discover || null,
      cancel: b.cancel || b.abort || null,
      isUserVerifyingPlatformAuthenticatorAvailable: b.isUserVerifyingPlatformAuthenticatorAvailable || b.hasPlatformAuthenticator || null,
      isPasskeyPlatformAuthenticatorAvailable: b.isPasskeyPlatformAuthenticatorAvailable || b.hasPasskeySupport || null
    }
    return (out.create || out.get) ? out : null
  }

  function probeInjectedInterfaces () {
    var names = ['__boltWebAuthn', 'BoltWebAuthn', 'boltWebAuthn', 'WebAuthnBridge',
                 '__webauthnBridge', '__WEB_AUTHN_BRIDGE__', '__bolt_webauthn__']
    for (var i = 0; i < names.length; i++) {
      try {
        var cand = window[names[i]]
        var adapted = adaptBridge(cand)
        if (adapted) { log('bridge found on window.' + names[i]); return adapted }
      } catch (e) { /* cross-origin proxy? skip */ }
    }
    return null
  }

  function resolveBridge () {
    if (nativeBridge) { return nativeBridge }
    // 1. Bolt's own native authenticator — always preferred when present
    var nativeIface = boltNativeBridge()
    if (nativeIface) { nativeBridge = nativeIface; return nativeBridge }
    var probed = probeInjectedInterfaces()
    if (probed) { nativeBridge = probed; return nativeBridge }
    // Fall back to pre-existing navigator.credentials (Bolt browser mode).
    // v1.3.24: also as a LAST RESORT when the native PublicKeyCredential
    // exists (Android 14+ devices without our interface) — better to let the
    // real engine try than to reject with "no bridge bound".
    if (CONFIG.trustExistingCredentials && (origCreate || origGet)) {
      nativeBridge = { create: origCreate, get: origGet, cancel: null }
      log('using pre-existing navigator.credentials as bridge')
      return nativeBridge
    }
    return null
  }

  /* =====================================================================
   * 7. ERROR MAPPING + RP ID VALIDATION
   * ===================================================================== */

  // GitHub differentiates user-cancel vs real failure via DOMException
  // names; a bridge that rejects with strings gets translated here.
  function mapBridgeError (err) {
    var name = (err && (err.name || err.code || err.error || '')) || ''
    var msg = (err && (err.message || err.msg)) || String(err || 'WebAuthn ceremony failed')
    var s = (String(name) + ' ' + String(msg)).toLowerCase()
    if (s.indexOf('cancel') >= 0 || s.indexOf('notallowed') >= 0 || s.indexOf('timeout') >= 0 || s.indexOf('timed out') >= 0) {
      return webauthnError('NotAllowedError', msg)
    }
    if (s.indexOf('notsupported') >= 0 || s.indexOf('unsupported') >= 0 || s.indexOf('not supported') >= 0) {
      return webauthnError('NotSupportedError', msg)
    }
    if (s.indexOf('invalidstate') >= 0 || s.indexOf('already registered') >= 0 || s.indexOf('excluded') >= 0) {
      return webauthnError('InvalidStateError', msg)
    }
    if (s.indexOf('security') >= 0) { return webauthnError('SecurityError', msg) }
    if (s.indexOf('constraint') >= 0) { return webauthnError('ConstraintError', msg) }
    if (s.indexOf('abort') >= 0) { return webauthnError('AbortError', msg) }
    if (s.indexOf('encoding') >= 0 || s.indexOf('not a valid base64') >= 0) {
      return webauthnError('TypeError', msg)
    }
    // Spec-safe default: treated as "user declined" by every relying party.
    return webauthnError('NotAllowedError', msg)
  }

  var PUBLIC_SUFFIXES = { 'com': 1, 'net': 1, 'org': 1, 'io': 1, 'dev': 1, 'app': 1, 'edu': 1, 'gov': 1, 'uk': 1, 'co': 1 }

  function validateRpId (rpId) {
    var host = (location.hostname || '').toLowerCase()
    var id = (rpId == null || rpId === '') ? host : String(rpId).toLowerCase()
    if (!host) { throw webauthnError('SecurityError', 'no origin') }
    if (id === host) { return id }
    if (host.length > id.length && host.charAt(host.length - id.length - 1) === '.' && host.slice(-id.length) === id) {
      var labels = id.split('.')
      if (labels.length > 1 && !PUBLIC_SUFFIXES[id]) { return id } // registrable suffix
    }
    throw webauthnError('SecurityError', "The provided 'rpId' is not a registrable domain suffix of, nor equal to, the current origin.")
  }

  /* =====================================================================
   * 8. RESULT NORMALIZATION (shape + toJSON)
   * ===================================================================== */

  function shapeResponse (kind, r) {
    if (r == null) { throw webauthnError('NotAllowedError', 'missing authenticator response') }
    var out
    if (kind === 'create') {
      out = {
        clientDataJSON: toArrayBuffer(b64uToBuf(r.clientDataJSON)),
        attestationObject: toArrayBuffer(b64uToBuf(r.attestationObject)),
        transports: (r.transports && r.transports.slice ? r.transports.slice() : ['internal'])
      }
      if (r.authenticatorData != null) { out.authenticatorData = toArrayBuffer(b64uToBuf(r.authenticatorData)) }
      if (r.publicKeyAlgorithm != null) { out.publicKeyAlgorithm = r.publicKeyAlgorithm }
      if (r.publicKey != null) { out.publicKey = toArrayBuffer(b64uToBuf(r.publicKey)) }
    } else {
      out = {
        clientDataJSON: toArrayBuffer(b64uToBuf(r.clientDataJSON)),
        authenticatorData: toArrayBuffer(b64uToBuf(r.authenticatorData != null ? r.authenticatorData : r.authData)),
        signature: toArrayBuffer(b64uToBuf(r.signature)),
        userHandle: (r.userHandle == null) ? null : toArrayBuffer(b64uToBuf(r.userHandle))
      }
    }
    if (out.clientDataJSON == null) { throw webauthnError('NotAllowedError', 'missing clientDataJSON') }
    return out
  }

  function responseToPlain (response, kind) {
    var out = { clientDataJSON: bufToB64u(response.clientDataJSON) }
    if (kind === 'create') {
      out.attestationObject = bufToB64u(response.attestationObject)
      out.transports = (response.transports || []).slice()
      if (response.authenticatorData != null) { out.authenticatorData = bufToB64u(response.authenticatorData) }
      if (response.publicKeyAlgorithm != null) { out.publicKeyAlgorithm = response.publicKeyAlgorithm }
      if (response.publicKey != null) { out.publicKey = bufToB64u(response.publicKey) }
    } else {
      out.authenticatorData = bufToB64u(response.authenticatorData)
      out.signature = bufToB64u(response.signature)
      out.userHandle = (response.userHandle == null) ? null : bufToB64u(response.userHandle)
    }
    return out
  }

  function credentialToJSON (c) {
    if (!c || !c.response) { return {} }
    var kind = (c.response.attestationObject != null) ? 'create' : 'get'
    return {
      id: c.id,
      rawId: bufToB64u(c.rawId),
      type: c.type || 'public-key',
      authenticatorAttachment: c.authenticatorAttachment || null,
      clientExtensionResults: c.clientExtensionResults || {},
      response: responseToPlain(c.response, kind)
    }
  }

  function shapeCredential (kind, raw) {
    if (!raw) { throw webauthnError('NotAllowedError', 'empty credential result') }
    var rawResponse = raw.response || raw
    var rawId = toArrayBuffer(b64uToBuf(raw.rawId != null ? raw.rawId : raw.id))
    var id = raw.id || (rawId ? bufToB64u(rawId) : '')
    var out = {
      type: raw.type || 'public-key',
      id: id,
      rawId: rawId || new ArrayBuffer(0),
      authenticatorAttachment: raw.authenticatorAttachment || 'platform',
      clientExtensionResults: (raw.clientExtensionResults && typeof raw.clientExtensionResults === 'object')
        ? raw.clientExtensionResults : {},
      response: shapeResponse(kind, rawResponse)
    }

    /* v1.3.26 — THE METHOD SURFACE (the GitHub registration crash).
     *
     * github.com's own webauthn-json shim (bundled in their JS) does not
     * read the credential's PROPERTIES — it derives its JSON from METHOD
     * CALLS inside its schema:
     *     clientExtensionResults: e => e.getClientExtensionResults()   // NO fallback
     *     transports:             e => e.getTransports ? e.getTransports() || [] : []
     * A shaped credential that carries only the properties makes their
     * serializer throw "getClientExtensionResults is not a function" —
     * which GitHub's UI renders as "Passkey registration failed"
     * (registration) and "Authentication failed" (sign-in). Real
     * PublicKeyCredential instances expose these as METHODS; our shape
     * must too. Method calls delegate to the RAW object first when it
     * already has them (the native-engine fallback path on Android 14+),
     * so a native result keeps its own semantics.
     */
    var resp = out.response
    out.getClientExtensionResults = function () {
      if (raw !== out && typeof raw.getClientExtensionResults === 'function') {
        try { return raw.getClientExtensionResults() } catch (e) { /* fall through */ }
      }
      return out.clientExtensionResults
    }
    out.isUserVerifying = function () {
      if (raw !== out && typeof raw.isUserVerifying === 'function') {
        try { return !!raw.isUserVerifying() } catch (e) { /* fall through */ }
      }
      return true // the consent dialog IS this authenticator's user verification
    }
    if (kind === 'create') {
      resp.getTransports = function () {
        if (rawResponse !== resp && typeof rawResponse.getTransports === 'function') {
          try { var t = rawResponse.getTransports(); if (t) { return t } } catch (e) { /* fall through */ }
        }
        return resp.transports || []
      }
      resp.getPublicKeyAlgorithm = function () {
        if (rawResponse !== resp && typeof rawResponse.getPublicKeyAlgorithm === 'function') {
          try { var a = rawResponse.getPublicKeyAlgorithm(); if (a != null) { return a } } catch (e) { /* fall through */ }
        }
        return resp.publicKeyAlgorithm != null ? resp.publicKeyAlgorithm : -7 // ES256
      }
      resp.getPublicKey = function () {
        if (resp.publicKey != null) { return resp.publicKey }
        if (rawResponse !== resp && typeof rawResponse.getPublicKey === 'function') {
          try { return toArrayBuffer(rawResponse.getPublicKey()) } catch (e) { /* fall through */ }
        }
        return null
      }
      resp.getAuthenticatorData = function () {
        if (resp.authenticatorData != null) { return resp.authenticatorData }
        if (rawResponse !== resp && typeof rawResponse.getAuthenticatorData === 'function') {
          try { return toArrayBuffer(rawResponse.getAuthenticatorData()) } catch (e) { /* fall through */ }
        }
        return null
      }
    }

    out.toJSON = function () { return credentialToJSON(out) }
    try { Object.setPrototypeOf(out, PKC.prototype) } catch (e) { /* ok */ }
    return out
  }

  /* =====================================================================
   * 9. CredentialsContainer WRAPPERS
   * ===================================================================== */

  var pendingConditional = []

  function abortPendingConditional (reason) {
    var list = pendingConditional.splice(0, pendingConditional.length)
    for (var i = 0; i < list.length; i++) {
      try { list[i].reject(webauthnError('NotAllowedError', 'Conditional request superseded (' + reason + ')')) } catch (e) {}
    }
  }

  function bridgeFor (kind) {
    var b = resolveBridge()
    if (!b) {
      var hint = 'Bolt WebAuthn compat: no bridge bound. Call BoltWebAuthnCompat.registerBridge({create, get}) from your native glue.'
      log(hint)
      throw webauthnError('NotSupportedError', hint)
    }
    if (kind === 'create' && !b.create) { throw webauthnError('NotSupportedError', 'bridge has no create()') }
    if (kind === 'get' && !b.get) { throw webauthnError('NotSupportedError', 'bridge has no get()') }
    return b
  }

  function runCeremony (kind, options, signal) {
    return new Promise(function (resolve, reject) {
      var settled = false
      var timer = 0
      var onAbort = null

      function ok (v) {
        if (settled) { return }
        settled = true
        if (timer) { clearTimeout(timer) }
        if (signal && onAbort) { try { signal.removeEventListener('abort', onAbort) } catch (e) {} }
        try { resolve(shapeCredential(kind, v)) } catch (e) { reject(e) }
      }
      function fail (err) {
        if (settled) { return }
        settled = true
        if (timer) { clearTimeout(timer) }
        if (signal && onAbort) { try { signal.removeEventListener('abort', onAbort) } catch (e) {} }
        reject(err)
      }

      if (signal) {
        if (signal.aborted) { return fail(webauthnError('AbortError', 'ceremony aborted')) }
        onAbort = function () { fail(webauthnError('AbortError', 'ceremony aborted by AbortSignal')) }
        try { signal.addEventListener('abort', onAbort) } catch (e) {}
      }

      if (CONFIG.ceremonyTimeoutMs > 0) {
        timer = setTimeout(function () {
          fail(webauthnError('NotAllowedError', 'ceremony timed out after ' + CONFIG.ceremonyTimeoutMs + 'ms'))
        }, CONFIG.ceremonyTimeoutMs)
      }

      var bridge
      try { bridge = bridgeFor(kind) } catch (e) { return fail(e) }

      var invoke = (kind === 'create') ? bridge.create : bridge.get
      log('ceremony', kind, 'rpId=' + (options.rpId || (options.rp && options.rp.id) || location.hostname))

      Promise.resolve()
        .then(function () { return invoke(options) })
        .then(ok, function (err) { fail(mapBridgeError(err)) })
        .catch(function (err) { fail(mapBridgeError(err)) })
    })
  }

  function parkedConditionalPromise (signal) {
    return new Promise(function (resolve, reject) {
      var entry = { reject: reject }
      pendingConditional.push(entry)
      var onAbort = function () {
        var idx = pendingConditional.indexOf(entry)
        if (idx >= 0) { pendingConditional.splice(idx, 1) }
        reject(webauthnError('AbortError', 'conditional request aborted'))
      }
      if (signal) {
        if (signal.aborted) { return onAbort() }
        try { signal.addEventListener('abort', onAbort) } catch (e) {}
      }
      log('conditional get() parked (will surface via modal path)')
    })
  }

  function normalizePublicKey (pk) {
    // tolerate a JSON string / pre-parsed object handed in directly
    if (typeof pk === 'string') { pk = JSON.parse(pk) }
    if (!pk || typeof pk !== 'object') { throw new TypeError('options.publicKey must be an object') }
    var out = copyObj(pk)
    // binary fields may arrive as base64url strings — normalize to buffers
    if (typeof out.challenge === 'string') { out.challenge = b64uToBuf(out.challenge) }
    if (out.user && typeof out.user.id !== 'undefined' && !(out.user.id instanceof ArrayBuffer) && !ArrayBuffer.isView(out.user.id)) {
      out.user = copyObj(out.user); out.user.id = b64uToBuf(out.user.id)
    }
    if (out.excludeCredentials) {
      out.excludeCredentials = out.excludeCredentials.map(function (c) {
        var x = copyObj(c)
        if (typeof x.id === 'string') { x.id = b64uToBuf(x.id) }
        return x
      })
    }
    if (out.allowCredentials) {
      out.allowCredentials = out.allowCredentials.map(function (c) {
        var x = copyObj(c)
        if (typeof x.id === 'string') { x.id = b64uToBuf(x.id) }
        return x
      })
    }
    return out
  }

  function installCredentialsWrappers () {
    var container = credsContainer
    if (!container) {
      container = {}
      try { Object.defineProperty(navigator, 'credentials', { value: container, writable: true, configurable: true }) }
      catch (e) { try { navigator.credentials = container } catch (e2) {} }
    }

    try {
      container.create = function (options) {
        try {
          if (!options || !options.publicKey) {
            return origCreate ? origCreate(options) : Promise.reject(new TypeError('create(): options.publicKey required'))
          }
          abortPendingConditional('create()')
          var pk = normalizePublicKey(options.publicKey)
          validateRpId(pk.rp && pk.rp.id)
          if (pk.rp && !pk.rp.id) { pk.rp = copyObj(pk.rp); pk.rp.id = location.hostname }
          if (!pk.rp) { pk.rp = { id: location.hostname } }
          return runCeremony('create', pk, options.signal)
        } catch (e) { return Promise.reject(e) }
      }

      container.get = function (options) {
        try {
          if (!options || !options.publicKey) {
            return origGet ? origGet(options) : Promise.reject(new TypeError('get(): options.publicKey required'))
          }
          var pk = normalizePublicKey(options.publicKey)
          var isConditional = (options.mediation === 'conditional')

          if (isConditional) {
            if (!CONFIG.conditionalGet || CONFIG.conditionalMode === 'off') {
              return Promise.reject(webauthnError('NotAllowedError', 'conditional mediation unavailable'))
            }
            if (CONFIG.conditionalMode === 'immediate') {
              // fake it: run the modal ceremony right away
              validateRpId(pk.rpId)
              return runCeremony('get', pk, options.signal)
            }
            // 'silent': Chrome semantics — park until a modal ceremony
            // supersedes it or the page goes away.
            validateRpId(pk.rpId)
            return parkedConditionalPromise(options.signal)
          }

          abortPendingConditional('get()')
          validateRpId(pk.rpId)
          return runCeremony('get', pk, options.signal)
        } catch (e) { return Promise.reject(e) }
      }

      if (origStore) { container.store = origStore }
      if (origPreventSilentAccess) {
        container.preventSilentAccess = origPreventSilentAccess
      } else {
        container.preventSilentAccess = function () { return Promise.resolve() }
      }
    } catch (e) {
      log('failed to install credentials wrappers', e)
    }
  }

  installCredentialsWrappers()

  /* =====================================================================
   * 10. PUBLIC API
   * ===================================================================== */

  window.BoltWebAuthnCompat = {
    version: '1.2',
    active: true,
    config: CONFIG,
    registerBridge: function (b) {
      nativeBridge = adaptBridge(b)
      log('bridge registered:', nativeBridge ? 'ok' : 'invalid')
      return !!nativeBridge
    },
    capabilities: capabilityMatrix,
    uaData: uaDataObject,
    internals: {
      nativePKC: nativePKC,
      stubbedPKC: stubbedPKC,
      hasBridge: function () { return !!resolveBridge() },
      parseCreationOptions: parseCreationOptions,
      parseRequestOptions: parseRequestOptions,
      credentialToJSON: credentialToJSON,
      shapeCredential: shapeCredential,
      mapBridgeError: mapBridgeError,
      validateRpId: validateRpId,
      bufToB64u: bufToB64u,
      b64uToBuf: b64uToBuf
    }
  }

  log('compat layer active — chrome', CONFIG.chromeMajor, '/ android', CONFIG.platformVersion,
      '/ nativePKC', nativePKC, '/ stub', stubbedPKC,
      '/ bridge', !!resolveBridge())
})()
