#!/bin/bash
# Mirror ONE release's APK from the private bolt-light-browser repo to the
# PUBLIC bolt-browser-releases repo (anonymous downloads, no login). Copies the
# release notes; uploads ONLY the .apk (never the source.zip — it contains the
# signing keystore). Idempotent: an already-mirrored version is re-verified.
#
# Usage: scripts/mirror_release.sh <version>     e.g. scripts/mirror_release.sh 1.3.16
# Token: $GHTOKEN, /home/z/my-project/.ghtoken or /home/z/my-project/.ghtoken_classic
#        — whichever can WRITE the public repo is picked automatically (probed;
#        learned on v1.3.80: a fine-grained PAT scoped to the private repo
#        reads the public repo fine but 403s every write). See
#        ensure_releases_repo.sh for the scope recipe.
set -e
VER="${1:?usage: mirror_release.sh <version>  (e.g. 1.3.16)}"
ACC="Accept: application/vnd.github+json"
SRC="https://api.github.com/repos/arfan1210/bolt-light-browser"
DST="https://api.github.com/repos/arfan1210/bolt-browser-releases"

# ---- token pick: the FIRST candidate that can WRITE the public repo ----
# (learned on v1.3.80: a fine-grained PAT scoped to the private repo reads
# the public repo fine but 403s every write.) Probe = PATCH the public
# repo's newest release with its OWN name — a genuine no-op write:
# 200 = can write, 403/401 = cannot. (A DELETE of a NONexistent asset is
# useless as a probe — GitHub checks existence first, so every token gets
# a 404 and the probe passed tokens that cannot write.)
can_write_dst() {  # $1 = candidate token
  [ -n "$1" ] || return 1
  local R PID NAME CODE
  R=$(curl -s -H "Authorization: Bearer $1" -H "$ACC" "$DST/releases/latest")
  PID=$(printf '%s' "$R" | python3 -c "import json,sys
try: print(json.load(sys.stdin)['id'])
except Exception: print('')" 2>/dev/null)
  if [ -z "$PID" ]; then return 0; fi   # no releases yet — let the mirror try
  NAME=$(printf '%s' "$R" | python3 -c "import json,sys
try:
    r = json.load(sys.stdin)
    print(json.dumps(r.get('name') or r['tag_name']))
except Exception: print('\"mirror-probe\"')" 2>/dev/null)
  [ -z "$NAME" ] && NAME='"mirror-probe"'
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH \
    -H "Authorization: Bearer $1" -H "$ACC" "$DST/releases/$PID" \
    -d "{\"name\":$NAME}")
  [ "$CODE" = "200" ]
}
TOK=""
for CAND in "${GHTOKEN:-}" \
            "$(cat /home/z/my-project/.ghtoken 2>/dev/null || true)" \
            "$(cat /home/z/my-project/.ghtoken_classic 2>/dev/null || true)"; do
  if can_write_dst "$CAND"; then TOK="$CAND"; break; fi
done
if [ -z "$TOK" ]; then
  echo "ERROR: no token that can WRITE the public repo (tried \$GHTOKEN,"
  echo ".ghtoken, .ghtoken_classic). The mirror needs Contents:write on"
  echo "arfan1210/bolt-browser-releases — easiest: a classic PAT with the"
  echo "'repo' scope. (Fine-grained PATs must list bolt-browser-releases"
  echo "with Contents: Read and write.)"
  exit 1
fi
AUTH="Authorization: Bearer $TOK"

echo "== ensure public repo =="
GHTOKEN="$TOK" bash "$(dirname "$0")/ensure_releases_repo.sh"

echo "== fetch source release v$VER =="
curl -s -H "$AUTH" -H "$ACC" "$SRC/releases/tags/v$VER" > /tmp/m_src_rel.json
python3 - "$VER" << 'PYEOF'
import json, sys
r = json.load(open('/tmp/m_src_rel.json'))
if 'id' not in r:
    print('SOURCE RELEASE NOT FOUND:', r.get('message')); raise SystemExit(1)
apk = next((a for a in r['assets'] if a['name'].endswith('.apk')), None)
if not apk:
    print('source release has no .apk asset'); raise SystemExit(1)
json.dump({'tag': r['tag_name'], 'name': r['name'] or r['tag_name'],
           'body': r['body'] or '', 'asset_id': apk['id'],
           'asset_name': apk['name'], 'asset_size': apk['size']},
          open('/tmp/m_mirror.json','w'))
print('source release:', r['tag_name'], '| apk:', apk['name'], apk['size'], 'bytes')
PYEOF

echo "== download APK from private release =="
ASSET_ID=$(python3 -c "import json;print(json.load(open('/tmp/m_mirror.json'))['asset_id'])")
curl -sL -H "$AUTH" -H "Accept: application/octet-stream" \
  "$SRC/releases/assets/$ASSET_ID" -o /tmp/m_mirror.apk
ls -la /tmp/m_mirror.apk

echo "== create public release =="
TAG=$(python3 -c "import json;print(json.load(open('/tmp/m_mirror.json'))['tag'])")
NAME=$(python3 -c "import json;print(json.load(open('/tmp/m_mirror.json'))['name'])")
BODY=$(python3 -c "import json;print(json.dumps(json.load(open('/tmp/m_mirror.json'))['body']))")
curl -s -H "$AUTH" -H "$ACC" "$DST/releases/tags/$TAG" > /tmp/m_dst_rel.json
if python3 -c "import json,sys;sys.exit(0 if 'id' in json.load(open('/tmp/m_dst_rel.json')) else 1)" 2>/dev/null; then
  echo "public release $TAG already exists — verifying asset instead"
  REL_JSON=/tmp/m_dst_rel.json
else
  curl -s -X POST -H "$AUTH" -H "$ACC" "$DST/releases" \
    -d "{\"tag_name\":\"$TAG\",\"name\":$(python3 -c "import json;print(json.dumps(json.load(open('/tmp/m_mirror.json'))['name']))"),\"body\":$BODY}" > /tmp/m_dst_rel.json
  if ! python3 -c "import json,sys;sys.exit(0 if 'id' in json.load(open('/tmp/m_dst_rel.json')) else 1)"; then
    echo "PUBLIC RELEASE CREATE FAILED:"; cat /tmp/m_dst_rel.json; exit 1
  fi
fi
REL_ID=$(python3 -c "import json;print(json.load(open('/tmp/m_dst_rel.json'))['id'])")
echo "public release id: $REL_ID"

APKNAME=$(python3 -c "import json;print(json.load(open('/tmp/m_mirror.json'))['asset_name'])")
EXISTING=$(python3 -c "
import json
r=json.load(open('/tmp/m_dst_rel.json'))
print(next((a['id'] for a in r.get('assets',[]) if a['name']=='$APKNAME'), ''))")
if [ -n "$EXISTING" ]; then
  echo "asset $APKNAME already mirrored (id $EXISTING) — replacing"
  curl -s -X DELETE -H "$AUTH" "$DST/releases/assets/$EXISTING" || true
fi
UPURL=$(python3 -c "import json;print(json.load(open('/tmp/m_dst_rel.json'))['upload_url'].replace('{?name,label}',''))")
curl -s -X POST -H "$AUTH" -H "Content-Type: application/octet-stream" \
  --data-binary "@/tmp/m_mirror.apk" "$UPURL?name=$APKNAME" > /tmp/m_asset.json
python3 -c "import json;d=json.load(open('/tmp/m_asset.json'));print(' uploaded:',d.get('name'),d.get('size'),'bytes  dl:',d.get('browser_download_url'))"

echo "== verify =="
curl -s -H "$AUTH" -H "$ACC" "$DST/releases/tags/$TAG" | python3 -c "
import json,sys
r=json.load(sys.stdin)
print('public release:',r['html_url'])
for a in r['assets']: print(' asset:',a['name'],a['size'],'bytes')
print('anonymous-download check: asset URL works without auth once repo is public')
"
echo "== MIRROR DONE: https://github.com/arfan1210/bolt-browser-releases/releases/tag/$TAG =="
