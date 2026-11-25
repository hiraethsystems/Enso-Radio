#!/bin/bash
# Makes Ensō Radio's release signing key, once, outside the repository:
#   ~/.config/ensoradio/release.jks          the key (PKCS12, RSA 4096, 100 years)
#   ~/.config/ensoradio/signing.properties   where it is and its password,
#                                           read by android/app/build.gradle.kts
# Both are readable only by you and are never committed. Back them up
# somewhere safe: without this key, an installed Ensō Radio can't be updated
# by a new release (Android would make users uninstall first).
set -euo pipefail

dir="$HOME/.config/ensoradio"
ks="$dir/release.jks"
props="$dir/signing.properties"

if [ -e "$ks" ] || [ -e "$props" ]; then
    echo "$ks or $props already exists; leaving them alone."
    exit 1
fi

umask 077
mkdir -p "$dir"
chmod 700 "$dir"

read -rsp "Choose a password for the release key (at least 8 characters): " pw
echo
read -rsp "Type it again: " pw2
echo
if [ "$pw" != "$pw2" ]; then
    echo "The two don't match; nothing made."
    exit 1
fi
if [ ${#pw} -lt 8 ]; then
    echo "Too short; nothing made."
    exit 1
fi

export ENSO_KEY_PW="$pw"
keytool -genkeypair -keystore "$ks" -storetype PKCS12 -alias ensoradio \
    -keyalg RSA -keysize 4096 -validity 36500 \
    -dname "CN=Aisling de Grás, O=Hiraeth Systems" \
    -storepass:env ENSO_KEY_PW -keypass:env ENSO_KEY_PW

# Java properties: backslashes must be doubled.
esc=${pw//\\/\\\\}
{
    echo "# Ensō Radio release signing. Never commit this file."
    echo "storeFile=$ks"
    echo "storePassword=$esc"
    echo "keyAlias=ensoradio"
    echo "keyPassword=$esc"
} > "$props"
unset ENSO_KEY_PW pw pw2 esc

echo
echo "Made $ks and $props."
echo "The certificate's SHA-256 fingerprint (it identifies your releases):"
keytool -list -keystore "$ks" -storetype PKCS12 -alias ensoradio -v \
    -storepass:file <(sed -n 's/^storePassword=//p' "$props") 2>/dev/null | grep -m1 "SHA256:" || true
echo
echo "Back up both files somewhere safe (not in the repository)."
