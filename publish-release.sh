#!/usr/bin/env bash

# One-click: build the signed release APK and publish it as a GitHub Release.
#
# Flow: verify clean tree -> read version -> clean build -> verify APK is
# signed with the persistent release keystore -> push master -> (re)create the
# version tag -> create the GitHub Release and upload the APK.
#
# Fail-fast by design. It never runs `git add -A`: publishing to a PUBLIC repo
# must not blindly stage whatever happens to be in the tree (secrets, scratch
# files). Commit your release deliberately first, then run this.

set -euo pipefail

cd "$(dirname "$0")"

APK_PATH="app/build/outputs/apk/release/app-release.apk"
BRANCH="master"

# ---- 1. Preconditions -----------------------------------------------------
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: working tree is dirty. Commit the release first, then re-run." >&2
    git status --short >&2
    exit 1
fi

VERSION_NAME=$(grep 'versionName =' app/build.gradle.kts | head -n1 | awk -F'"' '{print $2}')
VERSION_CODE=$(grep 'versionCode =' app/build.gradle.kts | head -n1 | grep -oE '[0-9]+')
[ -n "$VERSION_NAME" ] || { echo "Error: could not parse versionName." >&2; exit 1; }
TAG_NAME="v$VERSION_NAME"

if ! gh auth status >/dev/null 2>&1; then
    echo "Error: GitHub CLI not authenticated. Run 'gh auth login'." >&2
    exit 1
fi

echo "=========================================================="
echo " HomeAttach release publisher"
echo "   version : $VERSION_NAME (code $VERSION_CODE)"
echo "   tag     : $TAG_NAME"
echo "   commit  : $(git rev-parse --short HEAD)"
echo "=========================================================="

# ---- 2. Clean build -------------------------------------------------------
echo "Building signed release APK..."
./gradlew --console=plain clean :app:assembleRelease

[ -f "$APK_PATH" ] || { echo "Error: APK not produced at $APK_PATH" >&2; exit 1; }

# ---- 3. Verify the APK is actually signed (not a debug/unsigned build) -----
SDK_DIR=$(grep '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2- || true)
: "${SDK_DIR:=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
APKSIGNER=$(find "$SDK_DIR/build-tools" -maxdepth 2 -name apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
    if ! "$APKSIGNER" verify "$APK_PATH" >/dev/null 2>&1; then
        echo "Error: APK failed signature verification. Check release signing config." >&2
        exit 1
    fi
    echo "APK signature verified."
else
    echo "Warning: apksigner not found; skipping signature verification." >&2
fi
APK_SHA256=$(sha256sum "$APK_PATH" | awk '{print $1}')
echo "APK sha256: $APK_SHA256"

# ---- 4. Push branch and tag ----------------------------------------------
echo "Pushing $BRANCH..."
git push origin "$BRANCH"

if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
    echo "Tag $TAG_NAME exists locally; recreating on current HEAD."
    git tag -d "$TAG_NAME"
    git push --delete origin "$TAG_NAME" 2>/dev/null || true
fi
git tag "$TAG_NAME"
git push origin "$TAG_NAME"

# ---- 5. Release notes: commits since the previous tag ---------------------
PREV_TAG=$(git describe --tags --abbrev=0 "$TAG_NAME^" 2>/dev/null || true)
if [ -n "$PREV_TAG" ]; then
    CHANGES=$(git log --pretty='- %s' "$PREV_TAG..$TAG_NAME")
    RANGE="Changes since $PREV_TAG:"
else
    CHANGES=$(git log --pretty='- %s' "$TAG_NAME")
    RANGE="Changes:"
fi

NOTES=$(cat <<EOF
HomeAttach $TAG_NAME (versionCode $VERSION_CODE)

$RANGE
$CHANGES

APK sha256: \`$APK_SHA256\`

Install by sideloading \`app-release.apk\`. Upgrades keep encrypted app data only
when signed with the same release certificate.
EOF
)

# ---- 6. Create the GitHub Release -----------------------------------------
echo "Publishing GitHub Release $TAG_NAME..."
gh release delete "$TAG_NAME" --yes --cleanup-tag=false >/dev/null 2>&1 || true
gh release create "$TAG_NAME" "$APK_PATH#HomeAttach-$TAG_NAME.apk" \
    --title "$TAG_NAME" \
    --notes "$NOTES"

echo "=========================================================="
echo " Published: $(gh release view "$TAG_NAME" --json url -q .url)"
echo "=========================================================="
