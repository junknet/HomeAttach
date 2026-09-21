#!/usr/bin/env bash

# One-click: build the signed release APK and publish it as a GitHub Release.
#
# Flow: verify clean tree -> read version -> clean build -> verify APK is
# signed with the persistent release keystore -> fold the build's hash into
# update.json and commit it -> push master -> (re)create the version tag ->
# create the GitHub Release and upload the APK.
#
# What you bump before running this: versionCode and versionName in
# app/build.gradle.kts, and `notes` in update.json. The manifest's version, URL,
# hash and size are written here - the APK is not reproducible, so a hash cannot
# be known before the build that ships.
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
APK_SIZE=$(stat -c%s "$APK_PATH")
echo "APK sha256: $APK_SHA256  size: $APK_SIZE"

# ---- 3b. Generate the static version manifest (update.json) ----------------
# The app reads this from the CDN direct-download URL, NOT the GitHub REST API.
# The asset name must be exactly "update.json"/"app-release.apk", so the temp
# file is named accordingly (gh derives the asset name from the basename).
#
# The manifest is the repository's own update.json with the computed fields
# replaced, rather than a fresh document. That makes the direction of each field
# explicit: `notes` is written by a human before the release and flows *in*,
# while the version, URL, hash and size are only knowable here and flow *out* -
# written back to the same file, which step 3c commits. Generating the whole
# document here instead is what used to ship "HomeAttach 1.5.1" as the release
# note the update dialog showed, and leave the committed hash naming an APK
# that was never published.
REPO_SLUG=$(gh repo view --json nameWithOwner -q .nameWithOwner)
APK_ASSET="app-release.apk"
APK_URL="https://github.com/$REPO_SLUG/releases/download/$TAG_NAME/$APK_ASSET"
MANIFEST_URL="https://github.com/$REPO_SLUG/releases/latest/download/update.json"
REPO_MANIFEST="update.json"
MANIFEST_DIR=$(mktemp -d)
trap 'rm -rf "$MANIFEST_DIR"' EXIT
MANIFEST="$MANIFEST_DIR/update.json"

[ -f "$REPO_MANIFEST" ] || { echo "Error: $REPO_MANIFEST is missing." >&2; exit 1; }
VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" APK_URL="$APK_URL" \
APK_SHA256="$APK_SHA256" APK_SIZE="$APK_SIZE" \
python3 - "$REPO_MANIFEST" "$MANIFEST" <<'PYEOF' || exit 1
import json, os, sys

source, destination = sys.argv[1], sys.argv[2]
with open(source, encoding="utf-8") as handle:
    manifest = json.load(handle)

notes = manifest.get("notes", "").strip()
if not notes:
    sys.exit(f"Error: {source} has no release notes to publish.")

manifest.update({
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "apkUrl": os.environ["APK_URL"],
    "sha256": os.environ["APK_SHA256"],
    "sizeBytes": int(os.environ["APK_SIZE"]),
    "notes": notes,
})
for path in (destination, source):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
PYEOF
echo "Manifest generated: $MANIFEST_URL -> versionCode $VERSION_CODE"

# ---- 3c. Commit the manifest before tagging --------------------------------
# The tag has to name a tree whose update.json is the one that ships. The APK is
# not reproducible - two clean builds of the same commit differ - so the hash
# cannot be written by hand in advance and be right; it is only knowable here,
# after the build that actually gets uploaded. Committing it before the tag is
# what keeps the published manifest and the tagged one the same document.
#
# Narrow on purpose: only update.json, never `git add -A`. Step 1 proved the
# tree was clean, so anything else being dirty now means something unexpected
# wrote to it, and that is a reason to stop rather than to sweep it in.
if [ -n "$(git status --porcelain)" ]; then
    if [ "$(git status --porcelain)" != " M update.json" ]; then
        echo "Error: the build left changes beyond update.json:" >&2
        git status --short >&2
        exit 1
    fi
    echo "Recording the published manifest..."
    git add update.json
    git commit -q -m "Record the published $VERSION_NAME manifest"
else
    echo "Manifest already matched the build; nothing to record."
fi

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

# ---- 6. Create the GitHub Release (APK + manifest, forced latest) ----------
# --latest is mandatory: the app's manifest URL follows the "Latest" pointer,
# and a release that is never marked latest (or is a prerelease) 404s the whole
# update channel. Never pass --prerelease.
echo "Publishing GitHub Release $TAG_NAME..."
gh release delete "$TAG_NAME" --yes --cleanup-tag=false >/dev/null 2>&1 || true
gh release create "$TAG_NAME" "$APK_PATH" "$MANIFEST" \
    --latest \
    --title "$TAG_NAME" \
    --notes "$NOTES"

# ---- 7. Post-publish self-check: the channel contract, verified anonymously -
# Fetch exactly what an app in the wild would fetch (no token), and assert the
# just-published versionCode is served and the APK is reachable. This is the
# durable guard against a silently-broken update channel.
echo "Verifying update channel (anonymous)..."
SERVED=""
for _ in 1 2 3 4 5; do
    SERVED=$(curl -fsSL "$MANIFEST_URL" 2>/dev/null) && break
    sleep 3
done
[ -n "$SERVED" ] || { echo "Self-check FAILED: manifest not served at $MANIFEST_URL" >&2; exit 1; }
echo "$SERVED" | grep -qE "\"versionCode\"[[:space:]]*:[[:space:]]*$VERSION_CODE([^0-9]|$)" || {
    echo "Self-check FAILED: served manifest versionCode != $VERSION_CODE" >&2
    echo "$SERVED" >&2
    exit 1
}
# Retry like the manifest check above: the release-asset redirect endpoint takes a few seconds
# to become signable after `gh release create`, so a single immediate probe can catch the CDN
# hop before it is live and report the bare 302 instead of the followed 200/206.
APK_CODE=000
for _ in 1 2 3 4 5; do
    APK_CODE=$(curl -fsSL -r 0-0 -o /dev/null -w '%{http_code}' "$APK_URL" 2>/dev/null || echo 000)
    case "$APK_CODE" in
        200|206) break ;;
    esac
    sleep 3
done
case "$APK_CODE" in
    200|206) : ;;
    *) echo "Self-check FAILED: apkUrl unreachable (HTTP $APK_CODE): $APK_URL" >&2; exit 1 ;;
esac

echo "=========================================================="
echo " Published: $(gh release view "$TAG_NAME" --json url -q .url)"
echo " Update channel verified: versionCode $VERSION_CODE, APK reachable."
echo "=========================================================="
