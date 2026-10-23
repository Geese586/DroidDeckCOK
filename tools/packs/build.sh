#!/usr/bin/env bash
# Builds the add-on packs the app downloads on first use instead of shipping in the APK:
#   proot.tar.zst     the patched proot and its loader (built by tools/proot)
#   directaudio.tar.zst  the Wine DirectAudio drivers (fetched by tools/directaudio/fetch.sh)
#   tracking.tar.zst  hand-tracking models and the OpenCV/MediaPipe native libraries
#   xbox.tar.zst      the Xbox backend (built by tools/xbox) for /usr/local/lib/droiddeck-xbox
#   comet.tar.zst     the GOG Galaxy companion (built by tools/comet) for /usr/local/lib/droiddeck-comet
# Only CI publishes these (.github/workflows/publish-packs.yml); the APK built from the same
# directory pins each pack's SHA-256.
# Usage: tools/packs/build.sh <out-dir>
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:?usage: build.sh <out-dir>}
mkdir -p "$out"
out=$(cd "$out" && pwd)
# DroidDeckCOK: build only the packs named after <out-dir>, so a machine without the Xbox cross
# toolchain can still produce the other four. No names = build all five, exactly as upstream does.
# Without this, `pack xbox` exits under `set -e` and comet (which follows it) never runs.
shift || true
only=" $* "
want() { [ "$only" = "  " ] || [[ "$only" == *" $1 "* ]]; }
linuxfs="$repo/app/src/main/assets/linuxfs/usr/local/lib"
# shellcheck source=tracking/release.env
source "$here/tracking/release.env"

# DroidDeckCOK: on Windows $TMP is a "C:\..." path, so mktemp hands back a mixed spelling that the
# cleanup trap cannot delete here (the sandbox rejects an embedded drive prefix). Stage inside the
# repo instead, where the MSYS and Windows sides agree on what the path means. CI keeps mktemp.
if [ -n "${OS:-}" ]; then
    work="$repo/build-logs/pack-work-$$"
    mkdir -p "$work"
else
    work=$(mktemp -d)
fi
trap 'rm -rf "$work"' EXIT

# DroidDeckCOK: sha256sum prefixes the whole line with a backslash (and escapes the file name) when
# the path contains a backslash - which every Windows path does. Left in, the digest starts with "\"
# and never matches the pin. Trimming it is safe: a digest is hex.
sha256() {
    local line
    if command -v sha256sum >/dev/null; then line=$(sha256sum "$1"); else line=$(shasum -a 256 "$1"); fi
    printf '%s' "${line%% *}" | tr -d '\\'
}

fetch() {
    local url=$1 expected=$2 dest=$3
    curl -fsSL --retry 4 -o "$dest" "$url"
    local actual
    actual=$(sha256 "$dest")
    if [ "$actual" != "$expected" ]; then
        echo "checksum mismatch for $url: $actual" >&2
        exit 1
    fi
}

pack() {
    local name=$1 src=$2
    shift 2
    for required in "$@"; do
        [ -f "$src/$required" ] || { echo "$name pack: $src/$required is missing" >&2; exit 1; }
    done
    python3 -I - "$src" "$work/$name.tar" <<'EOF'
import os, sys, tarfile
src, dest = sys.argv[1], sys.argv[2]
files = []
for base, dirs, names in os.walk(src):
    dirs.sort()
    for name in names:
        path = os.path.join(base, name)
        if os.path.islink(path) or not os.path.isfile(path):
            sys.exit(f"{path}: only regular files can be packed")
        files.append(os.path.relpath(path, src))
with tarfile.open(dest, "w", format=tarfile.USTAR_FORMAT) as tar:
    for rel in sorted(files):
        path = os.path.join(src, rel)
        info = tar.gettarinfo(path, arcname=rel)
        info.mtime, info.uid, info.gid, info.uname, info.gname = 0, 0, 0, "", ""
        info.mode = 0o755 if os.stat(path).st_mode & 0o111 else 0o644
        with open(path, "rb") as f:
            tar.addfile(info, f)
EOF
    zstd -q -19 -f "$work/$name.tar" -o "$out/$name.tar.zst"
    echo "$name $(sha256 "$out/$name.tar.zst") $(wc -c < "$out/$name.tar.zst" | tr -d ' ')"
}

if want tracking; then
mkdir "$work/tracking"
fetch "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" \
    fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1 "$work/tracking/hand_landmarker.task"
fetch "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" \
    59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a "$work/tracking/pose_landmarker_lite.task"
cp "$here/tracking/hand_landmarker_lite.task" "$work/tracking/"
[ "$(sha256 "$work/tracking/hand_landmarker_lite.task")" = 173c0f47ecee7e935bd4a1e93aa580756b4180c1307c07d709313e82c28672c8 ] || {
    echo "checksum mismatch for hand_landmarker_lite.task" >&2; exit 1; }
fetch "https://repo1.maven.org/maven2/org/opencv/opencv/${OPENCV_VERSION}/opencv-${OPENCV_VERSION}.aar" \
    "$OPENCV_AAR_SHA256" "$work/opencv.aar"
fetch "https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/${MEDIAPIPE_VERSION}/tasks-core-${MEDIAPIPE_VERSION}.aar" \
    "$MEDIAPIPE_AAR_SHA256" "$work/tasks-core.aar"
unzip -q -j "$work/opencv.aar" jni/arm64-v8a/libopencv_java4.so -d "$work/tracking"
unzip -q -j "$work/tasks-core.aar" jni/arm64-v8a/libmediapipe_tasks_jni.so -d "$work/tracking"
chmod 0644 "$work/tracking"/*
fi

if want proot; then
    mkdir "$work/proot"
    install -m755 "$repo/app/src/main/jniLibs/arm64-v8a/libproot.so" \
        "$repo/app/src/main/jniLibs/arm64-v8a/libproot-loader.so" "$work/proot/"
    pack proot "$work/proot" libproot.so libproot-loader.so
fi
if want directaudio; then
    pack directaudio "$repo/app/src/main/assets/directaudio" \
        linux-wine11/version.txt linux-wine11-systhread/version.txt
fi
if want tracking; then
    pack tracking "$work/tracking" hand_landmarker.task hand_landmarker_lite.task pose_landmarker_lite.task \
        libopencv_java4.so libmediapipe_tasks_jni.so
fi
if want xbox; then
    pack xbox "$linuxfs/droiddeck-xbox" version xodus-cli xodus-service
fi
if want comet; then
    pack comet "$linuxfs/droiddeck-comet" version comet
fi
