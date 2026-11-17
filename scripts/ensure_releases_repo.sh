#!/bin/bash
# Ensure the PUBLIC releases repo (arfan1210/bolt-browser-releases) exists and
# has its README. Creates it if missing (needs a token that may create repos:
# a classic PAT with `repo` scope, or a fine-grained PAT with "All
# repositories" access + Contents:write). Idempotent — safe to run any time.
#
# Token source: $GHTOKEN or /home/z/my-project/.ghtoken
set -e
TOK="${GHTOKEN:-$(cat /home/z/my-project/.ghtoken 2>/dev/null || true)}"
if [ -z "$TOK" ]; then echo "ERROR: no GitHub token (GHTOKEN or /home/z/my-project/.ghtoken)"; exit 1; fi
AUTH="Authorization: Bearer $TOK"
ACC="Accept: application/vnd.github+json"
API="https://api.github.com/repos/arfan1210/bolt-browser-releases"

have_repo() { curl -s -o /dev/null -w "%{http_code}" -H "$AUTH" -H "$ACC" "$API"; }

if [ "$(have_repo)" = "200" ]; then
  echo "public repo exists: arfan1210/bolt-browser-releases"
else
  echo "creating public repo arfan1210/bolt-browser-releases..."
  CODE=$(curl -s -o /tmp/rel_repo.json -w "%{http_code}" -X POST -H "$AUTH" -H "$ACC" \
    "https://api.github.com/user/repos" -d '{
      "name":"bolt-browser-releases",
      "description":"Official releases of Bolt Light Browser — a tiny, fast, privacy-first Android browser. Download the APK here, no login required.",
      "private":false,"has_issues":true,"has_wiki":false,"has_pages":false}')
  if [ "$CODE" != "201" ]; then
    echo "----------------------------------------------------------------"
    echo "COULD NOT CREATE THE PUBLIC REPO (HTTP $CODE):"
    python3 -c "import json;d=json.load(open('/tmp/rel_repo.json'));print(d.get('message',''),d.get('documentation_url',''))" 2>/dev/null || cat /tmp/rel_repo.json
    echo
    echo "The current token is scoped to selected repositories only. To finish"
    echo "the public-releases setup, give the token access, then re-run this script:"
    echo "  EASY OPTION  — classic PAT with the 'repo' + 'workflow' scopes"
    echo "  FINE-GRAINED — Repository access: 'All repositories', permissions:"
    echo "                 Contents: Read and write, Workflows: Read and write"
    echo "Replace the token file contents with the new token and run:"
    echo "  bash scripts/ensure_releases_repo.sh && bash scripts/mirror_release.sh <ver>"
    echo "----------------------------------------------------------------"
    exit 2
  fi
  echo "created: $(python3 -c "import json;print(json.load(open('/tmp/rel_repo.json'))['html_url'])")"
fi

# README (only if absent — never clobber manual edits)
CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTH" -H "$ACC" "$API/contents/README.md")
if [ "$CODE" = "404" ]; then
  cat > /tmp/rel_readme.md << 'RMEOF'
# Bolt Light Browser — Releases

Tiny, fast, privacy-first Android browser with built-in adblock, userscripts,
and true background media playback. This repository hosts **official release
APKs** so anyone can download them — no GitHub account needed.

## Download

**Latest version:** https://github.com/arfan1210/bolt-browser-releases/releases/latest

Every release page carries the full changelog for that version.

## Install

1. Download the `.apk` onto your Android phone (Android 7.0+).
2. Tap it and allow "install unknown apps" for your browser/files app when prompted.
3. Upgrading works in place — every release is signed with the same key, your
   tabs, logins and settings are untouched.

## About Bolt

Bolt is a light browser inspired by Via Browser: one-process footprint,
essential features done well, no trackers, no analytics, no special
permissions beyond what a browser needs. It is not affiliated with Via.
RMEOF
  B64=$(base64 -w0 /tmp/rel_readme.md)
  curl -s -o /tmp/rel_rm.json -w "README put: %{http_code}\n" -X PUT -H "$AUTH" -H "$ACC" \
    "$API/contents/README.md" -d "{\"message\":\"README: releases home\",\"content\":\"$B64\"}" || true
fi
echo "public releases repo ready."

# ---- optional: install the Actions workflow into the private repo ----------
# .github/workflows/mirror-release.yml cannot be pushed with a token that
# lacks the workflow permission — this installs it via the contents API,
# which works once the token is (re)scoped. Best-effort, silent skip on 403.
WF_LOCAL="$(dirname "$0")/mirror-release.workflow.yml.txt"
WF_API="https://api.github.com/repos/arfan1210/bolt-light-browser/contents/.github/workflows/mirror-release.yml"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTH" -H "$ACC" "$WF_API")
if [ "$CODE" = "404" ] && [ -f "$WF_LOCAL" ]; then
  B64W=$(base64 -w0 "$WF_LOCAL")
  WCODE=$(curl -s -o /tmp/wf.json -w "%{http_code}" -X PUT -H "$AUTH" -H "$ACC" "$WF_API" \
    -d "{\"message\":\"ci: mirror release APK to public releases repo\",\"content\":\"$B64W\"}")
  if [ "$WCODE" = "201" ]; then
    echo "installed .github/workflows/mirror-release.yml (Actions mirror armed once RELEASE_MIRROR_TOKEN secret is added)"
  else
    echo "note: could not install the Actions workflow yet (HTTP $WCODE) — the"
    echo "scripts/mirror_release.sh path still mirrors every release meanwhile."
  fi
fi
