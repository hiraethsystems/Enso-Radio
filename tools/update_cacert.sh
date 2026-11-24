#!/bin/sh
# Puts curl's Mozilla CA bundle (https://curl.se/docs/caextract.html) into
# the APK's assets, android/app/src/main/assets/cacert.pem. libcurl can't
# use Android's own certificate store, so the app ships this one.
# The bundle is in git; to update it, change the date and SHA-256 below
# (from https://curl.se/ca/cacert-<date>.pem.sha256), run this, and note
# it in NOTES.md. Licence: MPL 2.0 (the certificate data is Mozilla's).
set -eu
CA_DATE=2026-11-05
CA_SHA256=427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/android/app/src/main/assets/cacert.pem"
mkdir -p "$(dirname "$OUT")"
curl -fsSL --retry 3 -o "$OUT.part" "https://curl.se/ca/cacert-$CA_DATE.pem"
got=$(sha256sum "$OUT.part" | cut -d' ' -f1)
if [ "$got" != "$CA_SHA256" ]; then
    rm -f "$OUT.part"
    echo "SHA-256 mismatch for cacert-$CA_DATE.pem: got $got" >&2
    exit 1
fi
mv "$OUT.part" "$OUT"
echo "cacert-$CA_DATE.pem -> $OUT ($(grep -c 'BEGIN CERTIFICATE' "$OUT") certificates)"
