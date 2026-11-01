#!/bin/bash
# Použití: bash tools/release.sh popis.md [--dry-run [verze]]
#
# Vydá novou verzi Serpentiny na GitHub tak, aby odkaz (a QR kód)
#   https://github.com/zitluk/Serpentina-motonavigation/releases/latest/download/Serpentina-latest.apk
# vždy stahoval nejnovější verzi a nikdy se neměnil:
#   1. sestaví podepsané APK s verzí z app/build.gradle.kts (versionName = verze v O aplikaci),
#   2. vydá ho jako Serpentina-latest.apk a označí jako nejnovější,
#   3. teprve potom přejmenuje soubor u předchozího vydání na Serpentina-<jeho verze>.apk.
# Verzi (versionCode, versionName) je potřeba předem zvýšit a commitnout.
# --dry-run jen vypíše, co by se stalo, nic nesestaví ani nenahraje; volitelná verze
# (např. --dry-run 0.3.0) nahradí tu z build.gradle.kts, ať jde zkusit i přejmenování.
set -euo pipefail

REPO=zitluk/Serpentina-motonavigation
LATEST=Serpentina-latest.apk
NOTES=${1:?Použití: bash tools/release.sh popis.md [--dry-run [verze]]}
DRY=${2:-}
cd "$(dirname "$0")/.."

run() { if [ "$DRY" = "--dry-run" ]; then echo "  [na sucho] $*"; else "$@"; fi; }
fail() { if [ "$DRY" = "--dry-run" ]; then echo "  [na sucho by skončilo] $*"; else echo "$*"; exit 1; fi; }

VERSION=$(grep -m1 'versionName = "' app/build.gradle.kts | cut -d'"' -f2)
if [ "$DRY" = "--dry-run" ] && [ -n "${3:-}" ]; then VERSION=$3; fi
TAG="v$VERSION"
echo "Nová verze: $VERSION"

# Kontroly – nic nevydáme omylem
[ -f "$NOTES" ] || fail "Chybí popis vydání $NOTES"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then fail "Vydání $TAG už existuje – zvyš verzi"; fi
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "Necommitnuté změny – nejdřív commit"
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "main není shodný s GitHubem – nejdřív push/pull"

PREV_TAG=$(gh release view --repo "$REPO" --json tagName --jq .tagName 2>/dev/null || true)
echo "Předchozí nejnovější vydání: ${PREV_TAG:-žádné}"

# 1) Sestavení a kontrola, že APK má opravdu novou verzi
export JAVA_HOME=${JAVA_HOME:-$LOCALAPPDATA/Programs/Java/jdk-17.0.20.1+1}
APK="$HOME/Library/Caches/Motonavigace/build/app/outputs/apk/release/app-release.apk"
run bash ./gradlew --project-cache-dir "$HOME/Library/Caches/Motonavigace/gradle" testDebugUnitTest assembleRelease --console=plain -q
if [ "$DRY" != "--dry-run" ]; then
    AAPT=$(ls -d "${ANDROID_HOME:-/c/Users/Lucasz/Android/Sdk}"/build-tools/*/ | tail -1)aapt2
    BUILT=$("$AAPT" dump badging "$APK" | grep -o "versionName='[^']*'" | cut -d"'" -f2)
    [ "$BUILT" = "$VERSION" ] || fail "APK má verzi $BUILT, čekala se $VERSION"
fi

# 2) Nové vydání jako Serpentina-latest.apk
WORK=$(mktemp -d)
if [ "$DRY" != "--dry-run" ]; then cp "$APK" "$WORK/$LATEST"; fi
run gh release create "$TAG" "$WORK/$LATEST#Serpentina $VERSION (APK)" --repo "$REPO" --target main \
    --title "Serpentina $VERSION (latest)" --notes-file "$NOTES" --latest

# 3) Až po vydání nové verze: předchozí soubor dostane název podle své verze
if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$TAG" ]; then
    PREV=${PREV_TAG#v}
    ASSET_ID=$(gh api "repos/$REPO/releases/tags/$PREV_TAG" --jq ".assets[] | select(.name==\"$LATEST\") | .id")
    if [ -n "$ASSET_ID" ]; then
        run gh api -X PATCH "repos/$REPO/releases/assets/$ASSET_ID" -f name="Serpentina-$PREV.apk" -f label="Serpentina $PREV (APK)" --silent
    fi
    gh release view "$PREV_TAG" --repo "$REPO" --json body --jq .body | sed "s/$LATEST/Serpentina-$PREV.apk/g" > "$WORK/prev.md"
    run gh release edit "$PREV_TAG" --repo "$REPO" --title "Serpentina $PREV" --notes-file "$WORK/prev.md"
    echo "Předchozí vydání: soubor Serpentina-$PREV.apk, název „Serpentina $PREV“"
fi
rm -rf "$WORK"

# Kontrola stálého odkazu
if [ "$DRY" != "--dry-run" ]; then
    URL="https://github.com/$REPO/releases/latest/download/$LATEST"
    curl -sfL -o /dev/null "$URL" && echo "Stálý odkaz funguje: $URL"
fi
