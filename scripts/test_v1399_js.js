#!/usr/bin/env node
/**
 * v1.3.99 gate, JS half — THE IDENTITY-AGREEMENT PASS, functional probes.
 *
 * Runs the REAL shipped asset (webauthn_compat.js) in the mocked WebView
 * environment (the same harness shape as test_webauthn_compat.js) and
 * behaviorally verifies the version agreement:
 *   1. THE STEADY STATE — a desktop UA at the detected engine (the living
 *      desktop UA, Chrome/156 with a 156 engine): brands claim 156.
 *   2. THE STALE STRING — a UA string frozen at Chrome/124 while the
 *      detected engine is 156: brands claim 124 (the string wins — the
 *      124-vs-156 mismatch fingerprint dies), and the full-version hints
 *      honestly claim what the string says (124.0.0.0).
 *   3. THE LIVE ENGINE — the mobile UA (Chrome/156, engine 156): the
 *      full-version hints carry the TRUE detected build (156.0.8078.47 —
 *      a reduction, not a contradiction).
 *   4. THE GREASE ROTATION keys on the string's major (124 and 156 pick
 *      different GREASE brands, like real Chromium versions do).
 */
'use strict'

const fs = require('fs')
const path = require('path')
const vm = require('vm')

const ASSET = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'webauthn_compat.js')

const UA_DESKTOP_LIVE = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36'
const UA_DESKTOP_STALE = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
const UA_MOBILE = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36'

let fails = 0
function need (cond, why) {
  console.log('  ' + (cond ? 'PASS' : 'FAIL') + ' ' + why)
  if (!cond) fails++
}

function prepareScript () {
  // exactly like PasskeyCompat.prepareScript(): the DETECTED engine is 156
  let js = fs.readFileSync(ASSET, 'utf8')
  js = js.replace('__CHROME_MAJOR__', '156')
      .replace('__CHROME_FULL__', '156.0.8078.47')
      .replace('__PLATFORM_VERSION__', '14.0.0')
      .replace('__DEVICE_MODEL__', 'RMX2020')
  return js
}

function makePage (ua) {
  const document = { addEventListener () {}, querySelector: () => null }
  const window = { isSecureContext: true }
  const navigator = { userAgent: ua, credentials: {} }
  const sandbox = {
    window, navigator, document, location: { href: 'https://x.test/', hostname: 'x.test', protocol: 'https:' },
    console, Promise, JSON, Object, Array, Error, TypeError, Symbol, Map, Set,
    Math, Date, parseInt, parseFloat, isNaN, isFinite, String, Number, Boolean,
    ArrayBuffer, Uint8Array, DataView, DOMException,
    setTimeout: () => 0, clearTimeout: () => {}, setInterval: () => 0, clearInterval: () => {},
    Function
  }
  sandbox.globalThis = sandbox
  sandbox.self = window
  vm.createContext(sandbox)
  vm.runInContext(prepareScript(), sandbox, { filename: 'webauthn_compat.js' })
  return { window, navigator }
}

async function run () {
  console.log('A — the steady state: string == engine == hints')

  { // A1: the living desktop UA (156 string, 156 engine)
    const p = makePage(UA_DESKTOP_LIVE)
    const ua = p.navigator.userAgentData
    const brand = ua.brands.find(b => b.brand === 'Google Chrome')
    need(ua && ua.mobile === false && ua.platform === 'Windows',
      'A1a desktop shape: mobile=false, platform=Windows')
    need(brand && brand.version === '156',
      'A1b a desktop UA at the detected engine claims 156 everywhere')
    const v = await ua.getHighEntropyValues(['version', 'uaFullVersion', 'fullVersionList'])
    need(v.version === '156' && v.uaFullVersion === '156.0.8078.47',
      'A1c the full-version hint carries the true detected build')
    need(v.fullVersionList.some(b => b.brand === 'Google Chrome' && b.version === '156.0.8078.47'),
      'A1d fullVersionList agrees')
  }

  console.log('B — the stale string: the hints follow the STRING (the fingerprint dies)')

  { // B1: the frozen-preset desktop UA (124 string, 156 engine)
    const p = makePage(UA_DESKTOP_STALE)
    const ua = p.navigator.userAgentData
    const brand = ua.brands.find(b => b.brand === 'Google Chrome')
    need(brand && brand.version === '124',
      'B1a brands claim the STRING major (124), not the detected engine (156)')
    const v = await ua.getHighEntropyValues(['version', 'uaFullVersion'])
    need(v.version === '124' && v.uaFullVersion === '124.0.0.0',
      'B1b the version hints honestly claim what the string says (no 124-vs-156 mismatch anywhere)')
  }

  { // B2: the mobile control (the default UA path is untouched)
    const p = makePage(UA_MOBILE)
    const ua = p.navigator.userAgentData
    const v = await ua.getHighEntropyValues(['version', 'uaFullVersion'])
    need(ua.mobile === true && ua.platform === 'Android' && v.version === '156',
      'B2a the mobile default keeps its shape (Android mobile, engine major)')
    need(v.uaFullVersion === '156.0.8078.47',
      'B2b the mobile full-version hint keeps the true detected build')
  }

  console.log('C — the GREASE rotation follows the string major')

  { // C1: 124 and 156 pick different GREASE brands (real Chromium behavior)
    const g156 = makePage(UA_DESKTOP_LIVE).navigator.userAgentData.brands
    const g124 = makePage(UA_DESKTOP_STALE).navigator.userAgentData.brands
    const g156g = g156.find(b => b.brand.startsWith('Not')).brand
    const g124g = g124.find(b => b.brand.startsWith('Not')).brand
    need(g156g !== g124g,
      'C1a GREASE rotates with the claimed major (' + g124g + ' @124 vs ' + g156g + ' @156)')
  }
}

run().then(() => {
  console.log()
  if (fails) {
    console.log('v1.3.99 JS gate: ' + fails + ' FAILURE(S)')
    process.exit(1)
  }
  console.log('v1.3.99 JS gate: all green')
})
