#!/usr/bin/env python3
"""Refresh the RELEASE_MIRROR_TOKEN Actions secret with a known-good token.

Reads the classic token (full 'repo' scope — verified to write the public
releases repo), seals it with the repo's Actions public key, PUTs it as the
RELEASE_MIRROR_TOKEN secret. The secret's old value (set 2026-10-28) will die
when the user revokes the old tokens — this keeps the mirror workflow armed.
"""
import base64
import json
import urllib.request

def api(method, url, token, data=None):
    req = urllib.request.Request(url, method=method, data=(
        json.dumps(data).encode() if data else None))
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")

token = open("/home/z/my-project/.ghtoken_classic").read().strip()
api_root = "https://api.github.com/repos/arfan1210/bolt-light-browser"

# 1. repo Actions public key
pk = api("GET", f"{api_root}/actions/secrets/public-key", token)
print("public key id:", pk["key_id"])

# 2. seal the token value (libsodium sealed box)
from nacl.public import PublicKey, SealedBox
from nacl import encoding
sealed = SealedBox(PublicKey(pk["key"].encode(), encoding.Base64Encoder())).encrypt(
    token.encode())
enc = base64.b64encode(sealed).decode()

# 3. PUT the secret
api("PUT", f"{api_root}/actions/secrets/RELEASE_MIRROR_TOKEN", token,
    {"encrypted_value": enc, "key_id": pk["key_id"]})
print("RELEASE_MIRROR_TOKEN refreshed with the current classic token.")
