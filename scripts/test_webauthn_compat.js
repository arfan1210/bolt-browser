#!/usr/bin/env node
/**
 * Bolt Browser — webauthn_compat.js test suite (v1.3.24).
 *
 * Runs the REAL shipped asset (app/src/main/assets/webauthn_compat.js)
 * inside a mocked WebView environment (node vm), covering the three field
 * failure layers: the PublicKeyCredential probe surface (GitHub's
 * "partial passkey support"), the Chrome identity spoof, and the ceremony
 * path through bolt's native authenticator bridge (window.BoltWebAuthn —
 * BoltAuthenticator.java). Placeholders are filled exactly like
 * PasskeyCompat.prepareScript does on device.
 *
 * Usage: node scripts/test_webauthn_compat.js   (exit 1 on any failure)
 */
'use strict'

const fs = require('fs')
const path = require('path')
const vm = require('vm')

const ASSET = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'webauthn_compat.js')

// ---------------------------------------------------------------- helpers

function b64uToBytes (s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)
  return Buffer.from(b64, 'base64')
}
function bytesToB64u (buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function ab (bytes) { return new Uint8Array(bytes).buffer }   // host ArrayBuffer

// fills placeholders exactly like PasskeyCompat.prepareScript()
function prepareScript () {
  let js = fs.readFileSync(ASSET, 'utf8')
  js = js.replace('__CHROME_MAJOR__', '156')
      .replace('__CHROME_FULL__', '156.0.8078.47')
      .replace('__PLATFORM_VERSION__', '14.0.0')
      .replace('__DEVICE_MODEL__', 'RMX2020')
  return js
}

/**
 * Build a mocked WebView page environment and run the compat script in it.
 * opts: { secure, nativePKC, probesTrue, bridge, origin }
 *  - nativePKC: the page's window.PublicKeyCredential exists (Chromium 156)
 *  - probesTrue: the NATIVE statics answer true (Android 14+ shape); false
 *    = the Android <=13 GMS-gated shape (the user's device)
 *  - bridge: mock BoltAuthenticator (records calls; configurable results)
 */
function makePage (opts) {
  const o = Object.assign({
    secure: true, nativePKC: true, probesTrue: false, bridge: true,
    origin: 'https://github.com',
    // v1.3.88: the live UA the WebView reports (WebSettings.setUserAgentString
    // is the source of truth for the client-hints spoof shape)
    ua: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36'
  }, opts)
  const calls = []
  let createResult = null
  let getResult = null
  let createError = null
  let getError = null

  function NativePKC () { throw new TypeError('Illegal constructor') }
  if (o.nativePKC) {
    const v = o.probesTrue
    NativePKC.isUserVerifyingPlatformAuthenticatorAvailable =
        () => Promise.resolve(v)
    NativePKC.isConditionalMediationAvailable = () => Promise.resolve(v)
    NativePKC.isPasskeyPlatformAuthenticatorAvailable = () => Promise.resolve(v)
    NativePKC.getClientCapabilities = () => Promise.resolve({
      conditionalCreate: false, conditionalGet: v, hybridTransport: false,
      passkeyPlatformAuthenticator: v, userVerifyingPlatformAuthenticator: v,
      relatedOrigins: false, signalAllAcceptedCredentials: false,
      signalCurrentUserDetails: false, signalUnknownCredential: false
    })
    NativePKC.parseCreationOptionsFromJSON = (j) => {
      // behave like real Chromium: binary fields become ArrayBuffers
      const o = JSON.parse(j)
      const conv = (s) => new Uint8Array(Buffer.from(String(s || ''), 'base64')).buffer
      if (o.user) { o.user = Object.assign({}, o.user, { id: conv(o.user.id) }) }
      o.challenge = conv(o.challenge)
      if (o.excludeCredentials) {
        o.excludeCredentials = o.excludeCredentials.map(c => Object.assign({}, c, { id: conv(c.id) }))
      }
      return o
    }
    NativePKC.parseRequestOptionsFromJSON = (j) => {
      const o = JSON.parse(j)
      const conv = (s) => new Uint8Array(Buffer.from(String(s || ''), 'base64')).buffer
      o.challenge = conv(o.challenge)
      if (o.allowCredentials) {
        o.allowCredentials = o.allowCredentials.map(c => Object.assign({}, c, { id: conv(c.id) }))
      }
      return o
    }
    NativePKC.prototype.toJSON = function () { return {} }
  }

  // the NATIVE (dead-on-Android-10) ceremony path
  const nativeCreate = () => Promise.reject(new DOMException('network error', 'NotReadyError'))
  const nativeGet = () => Promise.reject(new DOMException('network error', 'NotReadyError'))

  const credentials = { create: nativeCreate, get: nativeGet,
    store: () => Promise.resolve(), preventSilentAccess: () => Promise.resolve() }

  function Navigator () {}
  const navigator = Object.create(Navigator.prototype)
  Object.assign(navigator, { credentials, platform: 'Linux x86_64',
    userAgent: o.ua })

  const bridgeObj = {
    isAvailable: () => true,
    createPasskey: (json) => {
      calls.push(['create', JSON.parse(json)])
      if (createError) return JSON.stringify({ ok: false, error: createError })
      return JSON.stringify({ ok: true, result: createResult })
    },
    getPasskey: (json) => {
      calls.push(['get', JSON.parse(json)])
      if (getError) return JSON.stringify({ ok: false, error: getError })
      return JSON.stringify({ ok: true, result: getResult })
    },
    cancel: () => { calls.push(['cancel', null]) }
  }

  const host = o.origin.split('://')
  const location = { origin: o.origin, protocol: host[0] + ':',
    hostname: host[1], href: o.origin + '/' }
  const document = {
    hidden: false, visibilityState: 'visible',
    addEventListener () {}, removeEventListener () {},
    querySelector: () => null
  }
  const window = {
    isSecureContext: o.secure,
    PublicKeyCredential: o.nativePKC ? NativePKC : undefined,
    BoltWebAuthn: o.bridge ? bridgeObj : undefined
  }

  const sandbox = {
    window, navigator, document, location,
    console, Promise, JSON, Object, Array, Error, TypeError, Symbol, Map, Set,
    Math, Date, parseInt, parseFloat, isNaN, isFinite, String, Number, Boolean,
    ArrayBuffer, Uint8Array, Uint16Array, Int8Array, DataView,
    DOMException, btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    setTimeout, clearTimeout, setInterval, clearInterval, Function
  }
  sandbox.globalThis = sandbox
  sandbox.self = window
  vm.createContext(sandbox)
  vm.runInContext(prepareScript(), sandbox, { filename: 'webauthn_compat.js' })

  return {
    window, navigator, sandbox, calls,
    get bridge () { return bridgeObj },
    set createResult (r) { createResult = r },
    set getResult (r) { getResult = r },
    set createError (e) { createError = e },
    set getError (e) { getError = e }
  }
}

function assert (cond, msg) { if (!cond) throw new Error(msg || 'assertion failed') }
function eq (a, b, msg) {
  if (a !== b) throw new Error((msg || 'eq') + ': ' + JSON.stringify(a) + ' !== ' + JSON.stringify(b))
}
function deepEq (a, b, msg) {
  const ja = JSON.stringify(a), jb = JSON.stringify(b)
  if (ja !== jb) throw new Error((msg || 'deepEq') + ': ' + ja + ' !== ' + jb)
}

const tests = []
const test = (name, fn) => tests.push({ name, fn })
const results = { pass: 0, fail: 0 }

// ---------------------------------------------------------- probe surface

test('native probes answering FALSE are force-REPLACED (the v1.3.23 field bug)', async () => {
  const page = makePage({ probesTrue: false })       // Android 10 shape
  const PKC = page.window.PublicKeyCredential
  assert(typeof PKC.isUserVerifyingPlatformAuthenticatorAvailable === 'function', 'static missing')
  eq(await PKC.isUserVerifyingPlatformAuthenticatorAvailable(), true, 'UVPA must be true')
  eq(await PKC.isConditionalMediationAvailable(), true, 'conditional must be true')
  eq(await PKC.isPasskeyPlatformAuthenticatorAvailable(), true, 'passkey PA must be true')
  const caps = await PKC.getClientCapabilities()
  eq(caps.userVerifyingPlatformAuthenticator, true, 'caps UV')
  eq(caps.conditionalGet, true, 'caps conditionalGet')
  eq(caps.passkeyPlatformAuthenticator, true, 'caps passkey PA')
})

test('probes stay true when the native ones already answer true', async () => {
  const page = makePage({ probesTrue: true })        // Android 14+ shape
  const PKC = page.window.PublicKeyCredential
  eq(await PKC.isUserVerifyingPlatformAuthenticatorAvailable(), true, 'UVPA')
  eq(await PKC.getClientCapabilities().then(c => c.userVerifyingPlatformAuthenticator), true, 'caps')
})

test('parseCreationOptionsFromJSON present and functional', async () => {
  const page = makePage({})
  const PKC = page.window.PublicKeyCredential
  assert(typeof PKC.parseCreationOptionsFromJSON === 'function', 'missing')
  const o = PKC.parseCreationOptionsFromJSON(JSON.stringify({
    rp: { id: 'github.com', name: 'GitHub' },
    user: { id: 'dXNlcg', name: 'octocat', displayName: 'Octocat' },
    challenge: 'Y2hhbGxlbmdl', pubKeyCredParams: [{ type: 'public-key', alg: -7 }]
  }))
  eq(o.rp.id, 'github.com', 'rp')
  eq(o.user.name, 'octocat', 'user name')
  assert(o.challenge && o.challenge.byteLength === 9, 'challenge not converted to ArrayBuffer')
  assert(o.user.id && o.user.id.byteLength === 4, 'user.id not converted')
})

// ---------------------------------------------------------- identity spoof

test('userAgentData spoofed as Chrome / Android 14 / device model', async () => {
  const page = makePage({})
  const ua = page.navigator.userAgentData
  assert(ua, 'userAgentData missing')
  const brands = ua.brands.map(b => b.brand)
  assert(brands.includes('Google Chrome'), 'no Google Chrome brand: ' + brands)
  assert(brands.includes('Chromium'), 'no Chromium brand')
  eq(ua.mobile, true, 'mobile')
  eq(ua.platform, 'Android', 'platform')
  const hev = await ua.getHighEntropyValues(['platformVersion', 'model', 'fullVersionList', 'uaFullVersion'])
  eq(hev.platformVersion, '14.0.0', 'platformVersion (the OS claim sites gate on)')
  eq(hev.model, 'RMX2020', 'device model (placeholder filled)')
  eq(hev.uaFullVersion, '156.0.8078.47', 'full version')
  assert(hev.fullVersionList.some(b => b.brand === 'Google Chrome' && b.version === '156.0.8078.47'),
    'fullVersionList Google Chrome full version')
})

test('navigator.platform spoofed', async () => {
  const page = makePage({})
  eq(page.navigator.platform, 'Linux armv8l', 'platform')
})

// ------------------------------------------- v1.3.88: the spoof agrees with the live UA
// The desktop-site toggle / UA chooser change navigator.userAgent via
// WebSettings.setUserAgentString; a spoof hardcoded to mobile:true made
// every hints-reading site serve the mobile layout on a desktop UA.

test('v1.3.88 — desktop UA (Chrome/Windows): hints report desktop', async () => {
  const page = makePage({ ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36' })
  const ua = page.navigator.userAgentData
  assert(ua, 'userAgentData missing')
  eq(ua.mobile, false, 'mobile must be FALSE on a Windows UA')
  eq(ua.platform, 'Windows', 'platform')
  eq(page.navigator.platform, 'Win32', 'navigator.platform')
  const hev = await ua.getHighEntropyValues(['architecture', 'model', 'platformVersion'])
  eq(hev.architecture, 'x86', 'architecture')
  eq(hev.model, '', 'model must be empty on desktop (real Chrome)')
  eq(hev.platformVersion, '15.0.0', 'platformVersion')
})

test('v1.3.88 — desktop UA (Chrome/macOS): hints report desktop', async () => {
  const page = makePage({ ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36' })
  const ua = page.navigator.userAgentData
  eq(ua.mobile, false, 'mobile')
  eq(ua.platform, 'macOS', 'platform')
  eq(page.navigator.platform, 'MacIntel', 'navigator.platform')
})

test('v1.3.88 — Firefox UA: NO userAgentData (like the real browser)', async () => {
  const page = makePage({ ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0' })
  eq(page.navigator.userAgentData, undefined, 'Firefox never shipped userAgentData')
  eq(page.navigator.platform, 'Win32', 'navigator.platform still derived')
})

test('v1.3.88 — Safari UA: NO userAgentData (like the real browser)', async () => {
  const page = makePage({ ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15' })
  eq(page.navigator.userAgentData, undefined, 'Safari never shipped userAgentData')
  eq(page.navigator.platform, 'MacIntel', 'navigator.platform')
})

test('v1.3.88 — iPhone UA: NO userAgentData (real iOS Safari ships none)', async () => {
  const page = makePage({ ua: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1' })
  eq(page.navigator.userAgentData, undefined, 'iOS Safari never shipped userAgentData')
  eq(page.navigator.platform, 'iPhone', 'navigator.platform')
})

// ---------------------------------------------------------- ceremonies

function makeCreateResult () {
  const raw = bytesToB64u(Buffer.from('credential-id-32-bytes-xxxxxxxxxxx'))
  return {
    id: raw, rawId: raw, type: 'public-key',
    authenticatorAttachment: 'platform', clientExtensionResults: {},
    response: {
      attestationObject: bytesToB64u(Buffer.from([0xa3, 0x63, 0x66, 0x6d, 0x74])),
      clientDataJSON: bytesToB64u(Buffer.from('{"type":"webauthn.create"}')),
      transports: ['internal'],
      // v1.3.26: BoltAuthenticator now backs the modern response methods
      publicKeyAlgorithm: -7,
      publicKey: bytesToB64u(Buffer.from('cose-ec2-key-bytes')),
      authenticatorData: bytesToB64u(Buffer.from('authenticator-data-bytes'))
    }
  }
}
function makeGetResult () {
  const raw = bytesToB64u(Buffer.from('credential-id-32-bytes-xxxxxxxxxxx'))
  return {
    id: raw, rawId: raw, type: 'public-key',
    authenticatorAttachment: 'platform', clientExtensionResults: {},
    response: {
      authenticatorData: bytesToB64u(Buffer.from([0x01, 0x02, 0x03])),
      clientDataJSON: bytesToB64u(Buffer.from('{"type":"webauthn.get"}')),
      signature: bytesToB64u(Buffer.from([0x30, 0x44])),
      userHandle: bytesToB64u(Buffer.from('user'))
    }
  }
}

test('create() routes through the native BoltAuthenticator bridge', async () => {
  const page = makePage({})
  page.createResult = makeCreateResult()
  const challengeB64u = bytesToB64u(Buffer.from('top-secret-challenge'))
  const cred = await page.navigator.credentials.create({ publicKey: {
    challenge: ab(b64uToBytes(challengeB64u)),
    rp: { id: 'github.com', name: 'GitHub' },
    user: { id: new Uint8Array(Buffer.from('user-1')).buffer, name: 'octocat', displayName: 'The Octocat' },
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
    authenticatorSelection: { residentKey: 'preferred', userVerification: 'preferred' },
    timeout: 90000
  }})
  eq(page.calls.length, 1, 'exactly one bridge call')
  eq(page.calls[0][0], 'create', 'call kind')
  const payload = page.calls[0][1]
  eq(payload.rpId, 'github.com', 'rpId')
  eq(payload.origin, 'https://github.com', 'origin')
  eq(payload.challenge, challengeB64u, 'challenge b64url round-trip (ArrayBuffer -> b64url)')
  eq(payload.user.id, bytesToB64u(Buffer.from('user-1')), 'user.id b64url')
  eq(payload.user.name, 'octocat', 'user.name')
  eq(payload.user.displayName, 'The Octocat', 'user.displayName')
  eq(payload.residentKey, 'preferred', 'residentKey')
  eq(payload.timeout, 90000, 'timeout')
  // shaped credential
  assert(cred && cred.rawId && cred.rawId.byteLength > 0, 'rawId must be an ArrayBuffer')
  assert(cred.response && cred.response.attestationObject.byteLength > 0, 'attestationObject ArrayBuffer')
  assert(cred.response.clientDataJSON.byteLength > 0, 'clientDataJSON ArrayBuffer')
  eq(cred.response.transports[0], 'internal', 'transports')
  eq(cred.authenticatorAttachment, 'platform', 'attachment')
  assert(typeof cred.toJSON === 'function', 'toJSON()')
  const j = cred.toJSON()
  eq(j.id, page.calls[0][1] === null ? '' : makeCreateResult().id, 'toJSON().id')
  eq(typeof j.response.attestationObject, 'string', 'toJSON b64url strings')
})

test('get() routes through the bridge and shapes the assertion', async () => {
  const page = makePage({})
  page.getResult = makeGetResult()
  const challengeB64u = bytesToB64u(Buffer.from('get-challenge'))
  const idB64u = bytesToB64u(Buffer.from('credential-id-32-bytes-xxxxxxxxxxx'))
  const cred = await page.navigator.credentials.get({ publicKey: {
    challenge: challengeB64u, rpId: 'github.com',
    allowCredentials: [{ type: 'public-key', id: ab(b64uToBytes(idB64u)) }],
    userVerification: 'required', timeout: 45000
  }})
  eq(page.calls[0][0], 'get', 'call kind')
  const payload = page.calls[0][1]
  eq(payload.rpId, 'github.com', 'rpId')
  eq(payload.challenge, challengeB64u, 'challenge b64url')
  eq(payload.allowCredentials[0], idB64u, 'allowCredentials id b64url')
  eq(payload.userVerification, 'required', 'userVerification')
  assert(cred.response.authenticatorData.byteLength > 0, 'authenticatorData ArrayBuffer')
  assert(cred.response.signature.byteLength > 0, 'signature ArrayBuffer')
  assert(cred.response.userHandle && cred.response.userHandle.byteLength === 4, 'userHandle ArrayBuffer')
})

test('bridge NotAllowedError maps to a DOMException with the right name', async () => {
  const page = makePage({})
  page.createError = { name: 'NotAllowedError', message: 'cancelled' }
  let err = null
  try { await page.navigator.credentials.create({ publicKey: {
    challenge: 'x', rp: { id: 'github.com' } } }) } catch (e) { err = e }
  assert(err, 'must reject')
  eq(err.name, 'NotAllowedError', 'error name')
})

test('bridge InvalidStateError (passkey already exists) survives mapping', async () => {
  const page = makePage({})
  page.createError = { name: 'InvalidStateError', message: 'a passkey already exists for this site' }
  let err = null
  try { await page.navigator.credentials.create({ publicKey: {
    challenge: 'x', rp: { id: 'github.com' } } }) } catch (e) { err = e }
  assert(err, 'must reject')
  eq(err.name, 'InvalidStateError', 'error name preserved (sites rely on it)')
})

test('rpId mismatching the page origin is rejected before the bridge', async () => {
  const page = makePage({})       // origin github.com
  page.createResult = makeCreateResult()
  let err = null
  try { await page.navigator.credentials.create({ publicKey: {
    challenge: 'x', rp: { id: 'evil.example' } } }) } catch (e) { err = e }
  assert(err, 'must reject')
  eq(page.calls.length, 0, 'bridge must NOT be called')
  assert(err.name === 'SecurityError' || err.name === 'NotAllowedError', 'error name: ' + err.name)
})

test('conditional get() parks silently, superseded by a modal ceremony', async () => {
  const page = makePage({})
  page.getResult = makeGetResult()
  const parked = page.navigator.credentials.get({ mediation: 'conditional', publicKey: {
    challenge: 'c', rpId: 'github.com' }})
  let parkedErr = null
  parked.catch(e => { parkedErr = e })
  await new Promise(r => setTimeout(r, 10))
  // now a modal get arrives -> the parked one must reject NotAllowedError
  await page.navigator.credentials.get({ publicKey: { challenge: 'c2', rpId: 'github.com' }})
  await new Promise(r => setTimeout(r, 10))
  assert(parkedErr, 'parked promise must settle')
  eq(parkedErr.name, 'NotAllowedError', 'superseded -> NotAllowedError (Chrome semantics)')
})

// ---------------------------------------------------------- fallbacks / guards

test('without the bridge, ceremonies fall back to the native engine path', async () => {
  const page = makePage({ bridge: false })   // Android 14+ device shape
  let err = null
  try { await page.navigator.credentials.create({ publicKey: {
    challenge: 'x', rp: { id: 'github.com' } } }) } catch (e) { err = e }
  assert(err, 'must reject (the mock native engine always fails)')
  // mapBridgeError deliberately translates ad-hoc native failures into a
  // spec-correct NotAllowedError ("user declined") — sites show their normal
  // retry UI instead of a generic failure.
  eq(err.name, 'NotAllowedError', 'mapped to the spec-safe default')
  eq(page.calls.length, 0, 'no bridge calls')
})

test('insecure context (http page): layer disabled, native untouched', async () => {
  const page = makePage({ secure: false, origin: 'http://neverssl.com' })
  eq(page.window.BoltWebAuthnCompat && page.window.BoltWebAuthnCompat.active, false, 'inactive')
  // native create untouched -> the mock's rejection
  let err = null
  try { await page.navigator.credentials.create({ publicKey: {
    challenge: 'x', rp: { id: 'neverssl.com' } } }) } catch (e) { err = e }
  eq(err.name, 'NotReadyError', 'native path untouched')
  eq(page.calls.length, 0, 'no bridge calls')
})

test('old WebView with NO native PublicKeyCredential: stub + bridge still work', async () => {
  const page = makePage({ nativePKC: false })
  assert(page.window.PublicKeyCredential, 'stub must exist')
  eq(await page.window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable(), true, 'UVPA')
  page.createResult = makeCreateResult()
  const cred = await page.navigator.credentials.create({ publicKey: {
    challenge: bytesToB64u(Buffer.from('ch')), rp: { id: 'github.com' } }})
  assert(cred && cred.rawId && cred.rawId.byteLength > 0, 'credential shaped')
})

test('challenge may arrive as base64url STRING (JSON-API path)', async () => {
  const page = makePage({})
  page.createResult = makeCreateResult()
  const b = bytesToB64u(Buffer.from('stringy-challenge'))
  await page.navigator.credentials.create({ publicKey: {
    challenge: b, rp: { id: 'github.com', name: 'GitHub' },
    user: { id: 'dXNlcg', name: 'octocat', displayName: 'octocat' } }})
  eq(page.calls[0][1].challenge, b, 'string challenge passes through normalized')
})

// ------------------------------------------------- v1.3.26 method surface
// github.com's bundled webauthn-json shim (zj-*.js) derives its JSON from
// credential METHODS, not properties:
//     clientExtensionResults: e => e.getClientExtensionResults()   // NO fallback
//     transports:             e => e.getTransports ? e.getTransports() || [] : []
// A shaped credential with properties only crashed it -> "Passkey
// registration failed" (create) / "Authentication failed" (get).

test('shaped credential carries the full METHOD surface (the v1.3.26 fix)', async () => {
  const page = makePage({})
  page.createResult = makeCreateResult()
  const cred = await page.navigator.credentials.create({ publicKey: {
    challenge: ab(b64uToBytes(bytesToB64u(Buffer.from('ch-1')))),
    rp: { id: 'github.com', name: 'GitHub' },
    user: { id: ab(Buffer.from('user-1')), name: 'octocat', displayName: 'Octocat' },
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }]
  }})
  eq(typeof cred.getClientExtensionResults, 'function', 'getClientExtensionResults() must be a METHOD')
  eq(typeof cred.isUserVerifying, 'function', 'isUserVerifying() must be a method')
  eq(typeof cred.response.getTransports, 'function', 'response.getTransports() must be a method')
  eq(typeof cred.response.getPublicKeyAlgorithm, 'function', 'response.getPublicKeyAlgorithm() must be a method')
  eq(typeof cred.response.getPublicKey, 'function', 'response.getPublicKey() must be a method')
  eq(typeof cred.response.getAuthenticatorData, 'function', 'response.getAuthenticatorData() must be a method')
  deepEq(cred.getClientExtensionResults(), {}, 'getClientExtensionResults() -> {}')
  eq(cred.isUserVerifying(), true, 'isUserVerifying() -> true (consent dialog = UV)')
  eq(cred.response.getTransports().length, 1, 'getTransports() -> ["internal"]')
  eq(cred.response.getTransports()[0], 'internal', 'getTransports()[0]')
  eq(cred.response.getPublicKeyAlgorithm(), -7, 'getPublicKeyAlgorithm() -> -7')
  assert(cred.response.getPublicKey() && cred.response.getPublicKey().byteLength > 0, 'getPublicKey() -> COSE bytes')
  assert(cred.response.getAuthenticatorData() && cred.response.getAuthenticatorData().byteLength > 0, 'getAuthenticatorData() -> authData bytes')
})

// github.com's serializer, ported VERBATIM from their bundle (zj-*.js):
// schema nodes {required, schema, derive}; "copy" | "convert"; object walk
// with derive-based MUTATION of the source object before serialization.
function githubSerialize (credential, kind) {
  const COPY = 'copy', CONV = 'convert'
  const conv = (buf) => bytesToB64u(Buffer.from(buf))   // their l(): ArrayBuffer -> b64url
  function s (schema, derive) { return { required: true, schema, derive } }
  function c (schema) { return { required: true, schema } }
  function u (schema) { return { required: false, schema } }
  function walk (converter, schema, n) {
    if (schema === COPY) return n
    if (schema === CONV) return converter(n)
    if (schema instanceof Array) return n.map(x => walk(converter, schema[0], x))
    if (schema instanceof Object) {
      const r = {}
      for (const [key, node] of Object.entries(schema)) {
        if (node.derive) { const d = node.derive(n); if (d !== undefined) n[key] = d }
        if (!(key in n)) { if (node.required) throw new Error('Missing key: ' + key); continue }
        if (n[key] == null) { r[key] = null; continue }
        r[key] = walk(converter, node.schema, n[key])
      }
      return r
    }
  }
  const g = {   // their create schema
    type: c(COPY), id: c(COPY), rawId: c(CONV), authenticatorAttachment: u(COPY),
    response: c({
      clientDataJSON: c(CONV), attestationObject: c(CONV),
      transports: s(COPY, e => { const t = e.getTransports ? e.getTransports.call(e) : null; return t || [] })
    }),
    clientExtensionResults: s({ appid: u(COPY), appidExclude: u(COPY), credProps: u(COPY) },
      e => e.getClientExtensionResults())   // the NO-FALLBACK crash line
  }
  const b = {   // their get schema
    type: c(COPY), id: c(COPY), rawId: c(CONV), authenticatorAttachment: u(COPY),
    response: c({ clientDataJSON: c(CONV), authenticatorData: c(CONV), signature: c(CONV), userHandle: c(CONV) }),
    clientExtensionResults: s({ appid: u(COPY), appidExclude: u(COPY), credProps: u(COPY) },
      e => e.getClientExtensionResults())
  }
  return walk(conv, kind === 'create' ? g : b, credential)
}

test("GitHub's exact derive serializer completes on create() (the field bug)", async () => {
  const page = makePage({})
  page.createResult = makeCreateResult()
  const cred = await page.navigator.credentials.create({ publicKey: {
    challenge: ab(b64uToBytes(bytesToB64u(Buffer.from('ch-1')))),
    rp: { id: 'github.com', name: 'GitHub' },
    user: { id: ab(Buffer.from('user-1')), name: 'octocat', displayName: 'Octocat' },
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
    authenticatorSelection: { residentKey: 'required', userVerification: 'required' }
  }})
  // GitHub's E() overwrites toJSON with their serializer before JSON.stringify:
  cred.toJSON = () => githubSerialize(cred, 'create')
  const json = JSON.parse(JSON.stringify(cred))    // must NOT throw
  eq(json.type, 'public-key', 'type')
  eq(json.id, cred.id, 'id')
  assert(typeof json.rawId === 'string' && json.rawId.length > 0, 'rawId as b64url')
  eq(json.authenticatorAttachment, 'platform', 'attachment')
  assert(typeof json.response.clientDataJSON === 'string', 'clientDataJSON b64url')
  assert(typeof json.response.attestationObject === 'string', 'attestationObject b64url')
  eq(json.response.transports.length, 1, 'transports via getTransports()')
  eq(json.response.transports[0], 'internal', 'transports value')
  deepEq(json.clientExtensionResults, {}, 'clientExtensionResults via method')
})

test("GitHub's exact derive serializer completes on get() results", async () => {
  const page = makePage({})
  page.getResult = makeGetResult()
  const assertion = await page.navigator.credentials.get({ publicKey: {
    challenge: ab(b64uToBytes(bytesToB64u(Buffer.from('ch-2')))),
    rpId: 'github.com', userVerification: 'required'
  }})
  assertion.toJSON = () => githubSerialize(assertion, 'get')
  const json = JSON.parse(JSON.stringify(assertion))  // must NOT throw
  eq(json.response.userHandle, bytesToB64u(Buffer.from('user')), 'userHandle b64url round trip')
  assert(typeof json.response.signature === 'string', 'signature b64url')
  assert(typeof json.response.authenticatorData === 'string', 'authenticatorData b64url')
  deepEq(json.clientExtensionResults, {}, 'clientExtensionResults via method')

  // the v1.3.25 null-degrade shape (record with no userId):
  const page2 = makePage({})
  const r = makeGetResult()
  r.response.userHandle = null
  page2.getResult = r
  const a2 = await page2.navigator.credentials.get({ publicKey: {
    challenge: ab(b64uToBytes(bytesToB64u(Buffer.from('ch-3')))), rpId: 'github.com' }})
  a2.toJSON = () => githubSerialize(a2, 'get')
  const json2 = JSON.parse(JSON.stringify(a2))     // must NOT throw
  eq(json2.response.userHandle, null, 'userHandle null degrades (not missing, not empty)')
})

test('native-engine fallback path keeps method delegation (Android 14+ shape)', async () => {
  // shapeCredential must delegate its methods to the RAW native credential
  // when that credential already carries them (the native-engine fallback
  // path on Android 14+), and answer from its own fields on the bridge path.
  const page = makePage({ bridge: false })
  const nativeCred = {
    type: 'public-key',
    id: bytesToB64u(Buffer.from('native-id')),
    rawId: ab(Buffer.from('native-id')),
    authenticatorAttachment: 'platform',
    clientExtensionResults: {},
    getClientExtensionResults () { return { credProps: { rk: true } } },
    isUserVerifying () { return false },
    response: {
      clientDataJSON: ab(Buffer.from('{"type":"webauthn.create"}')),
      attestationObject: ab(Buffer.from([0xa3])),
      getTransports () { return ['hybrid'] },
      getPublicKeyAlgorithm () { return -257 },
      getPublicKey () { return ab(Buffer.from('cose')) },
      getAuthenticatorData () { return ab(Buffer.from('authdata')) }
    }
  }
  const shaped = page.window.BoltWebAuthnCompat.internals.shapeCredential('create', nativeCred)
  eq(shaped.getClientExtensionResults().credProps.rk, true, 'delegates to raw getClientExtensionResults()')
  eq(shaped.isUserVerifying(), false, 'delegates to raw isUserVerifying()')
  eq(shaped.response.getTransports()[0], 'hybrid', 'delegates to raw getTransports()')
  eq(shaped.response.getPublicKeyAlgorithm(), -257, 'delegates to raw getPublicKeyAlgorithm()')
  eq(shaped.response.getPublicKey().byteLength, 4, 'delegates to raw getPublicKey()')
  eq(shaped.response.getAuthenticatorData().byteLength, 8, 'delegates to raw getAuthenticatorData()')
  // and the bridge path (plain JSON result, no raw methods) answers itself
  const bridgeShaped = page.window.BoltWebAuthnCompat.internals.shapeCredential('create', {
    id: 'eA', rawId: 'eA', response: { clientDataJSON: 'eA', attestationObject: 'eA' }
  })
  deepEq(bridgeShaped.getClientExtensionResults(), {}, 'bridge path: {} extensions')
  eq(bridgeShaped.isUserVerifying(), true, 'bridge path: consent dialog = UV -> true')
  eq(bridgeShaped.response.getTransports()[0], 'internal', 'bridge path: default transports')
  eq(bridgeShaped.response.getPublicKeyAlgorithm(), -7, 'bridge path: ES256 default')
  eq(bridgeShaped.response.getPublicKey(), null, 'bridge path: no key -> null (Chrome shape)')
  eq(bridgeShaped.response.getAuthenticatorData(), null, 'bridge path: no authData -> null')
})

// ---------------------------------------------------------------- runner

;(async () => {
  for (const t of tests) {
    try {
      await t.fn()
      results.pass++
      console.log('  PASS  ' + t.name)
    } catch (e) {
      results.fail++
      console.log('  FAIL  ' + t.name)
      console.log('        ' + (e && e.message ? e.message : e))
    }
  }
  console.log('=========================================')
  console.log('passed: ' + results.pass + '   failed: ' + results.fail)
  process.exit(results.fail ? 1 : 0)
})()
