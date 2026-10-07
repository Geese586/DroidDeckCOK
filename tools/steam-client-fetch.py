#!/usr/bin/env python3
"""Fetch the native arm64 Steam client's packages for DroidDeck's local install.

Valve's CDN is slow or unreachable from some networks, and the client is a 360 MB set of 17 zip
files listed in a small text manifest. Run this where the connection is better, copy the folder
it fills to the device, then use "Install from folder" in DroidDeck's Steam client settings: the
app hands that folder to the guest's droiddeck-steam-install, which checks every file against the
sha256 the manifest carries and downloads only the pieces the folder is missing.

    python steam-client-fetch.py                 # into ./steam-client
    python steam-client-fetch.py --out D:\\steam  # somewhere else
    python steam-client-fetch.py --list          # just print the URLs, download nothing
    python steam-client-fetch.py --host HOST     # fetch from another copy of Valve's CDN

The transfers go through curl when it is on PATH, which is what the .bat wrapper relies on:
Python's own HTTP stack measured about two hundred times slower than curl on one Windows machine
here (0.05 MB/s against 9.4 MB/s from the same host), for reasons that had nothing to do with the
network. With no curl it falls back to urllib, which is correct but may crawl.

Re-running resumes: a file that is already complete is verified and skipped, and a partial one
keeps its bytes.
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_HOST = "client-update.fastly.steamstatic.com"
CHANNELS = ("publicbeta", "steamdeck_publicbeta")
CHUNK = 1 << 20


def manifest_name(channel):
    return "steam_client_%s_linuxarm64" % channel


def parse_manifest(text):
    """The component blocks the install script would take, mirroring its awk pass exactly.

    Every block is a bare tab-indented "name" line followed by a brace of key/value pairs; the
    two interesting keys are `file` (the plain zip) and `sha2` (its checksum).
    """
    current = None
    blocks = {}
    for line in text.splitlines():
        quoted = re.findall(r'"([^"]*)"', line)
        if len(quoted) == 1 and line.startswith("\t"):
            current = quoted[0]
        elif len(quoted) >= 2 and current:
            blocks.setdefault(current, {})[quoted[0]] = quoted[1]
    return {
        name: entry
        for name, entry in blocks.items()
        if (name.endswith("_all") or name.endswith("_linuxarm64_linuxarm64")) and "file" in entry
    }


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def human(count):
    for unit in ("B", "KB", "MB", "GB"):
        if count < 1024 or unit == "GB":
            return "%.1f %s" % (count, unit)
        count /= 1024.0


def show(label, done, total):
    if total > 0:
        sys.stdout.write("\r  %-44s %5.1f%%  %s / %s   " % (
            label[:44], 100.0 * done / total, human(done), human(total)))
    else:
        sys.stdout.write("\r  %-44s %s   " % (label[:44], human(done)))
    sys.stdout.flush()


def base_url(host):
    """Accept a bare host or a whole base URL, so a mirror with a path prefix works too."""
    host = host.rstrip("/")
    return host if "://" in host else "https://" + host


def find_curl():
    """curl ships with Windows 10 1803 and later, and with every Unix worth the name."""
    return shutil.which("curl") or shutil.which("curl.exe")


def curl_to(curl, url, part, label, meter):
    """Fetch [url] into [part], continuing whatever is already there. True on a clean exit."""
    command = [curl, "-f", "-L", "--ssl-no-revoke",
               "--retry", "3", "--retry-delay", "3", "--connect-timeout", "20"]
    if os.path.isfile(part) and os.path.getsize(part):
        command += ["-C", "-"]
    command += ["-o", part, "--progress-bar" if meter else "--no-progress-meter", url]
    try:
        completed = subprocess.run(command)
    except OSError as error:
        print("  %-44s curl would not run: %s" % (label[:44], error))
        return False
    if completed.returncode != 0:
        print("  %-44s curl gave up (exit %d)" % (label[:44], completed.returncode))
        return False
    return True


def urllib_to(url, part, label):
    """curl_to without curl: the same job, no resume guarantee, and possibly far slower."""
    start = os.path.getsize(part) if os.path.isfile(part) else 0
    request = urllib.request.Request(url, headers={"User-Agent": "DroidDeck"})
    if start:
        request.add_header("Range", "bytes=%d-" % start)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            if start and response.status != 206:
                start = 0  # the server ignored the range; whatever is there is unusable
            total = int(response.headers.get("Content-Length") or 0) + start
            done = start
            with open(part, "ab" if start else "wb") as handle:
                for block in iter(lambda: response.read(CHUNK), b""):
                    handle.write(block)
                    done += len(block)
                    show(label, done, total)
        return True
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as error:
        print("  %-44s %s" % (label[:44], error))
        return False


def download(url, dest, expected, label, curl=None, attempts=6):
    """Fetch [url] to [dest] and verify it is [expected]; resumable, and idempotent when done."""
    if os.path.isfile(dest) and sha256(dest) == expected:
        print("  %-44s already here and verified" % label[:44])
        return True
    part = dest + ".part"
    meter = sys.stderr.isatty()
    for attempt in range(1, attempts + 1):
        if attempt > 1:
            print("  %-44s attempt %d/%d" % (label[:44], attempt, attempts))
        started = time.time()
        arrived = curl_to(curl, url, part, label, meter) if curl else urllib_to(url, part, label)
        sys.stdout.write("\r" + " " * 100 + "\r")
        if arrived and sha256(part) == expected:
            os.replace(part, dest)
            size = os.path.getsize(dest)
            rate = human(size / max(time.time() - started, 0.001))
            print("  %-44s ok (%s at %s/s)" % (label[:44], human(size), rate))
            return True
        if arrived:
            # Everything arrived, but not the bytes the manifest promised: there is nothing
            # here worth resuming from.
            print("  %-44s checksum mismatch; downloading again" % label[:44])
            try:
                os.remove(part)
            except OSError:
                pass
        # A transfer that broke part way keeps its bytes, and the next attempt continues it.
    return False


def main():
    parser = argparse.ArgumentParser(description="Download the arm64 Steam client for a local install.")
    parser.add_argument("--out", default="steam-client", help="where to put the packages (default: ./steam-client)")
    parser.add_argument("--channel", default="steamdeck_publicbeta", choices=CHANNELS,
                        help="Steam client branch (default: steamdeck_publicbeta, what the app uses)")
    parser.add_argument("--list", action="store_true", help="print the URLs and exit without downloading")
    parser.add_argument("--host", default=DEFAULT_HOST,
                        help="a CDN host or whole base URL (default: %s); Valve serves the same "
                             "files from client-update.steamstatic.com too" % DEFAULT_HOST)
    args = parser.parse_args()

    base = base_url(args.host)
    manifest = manifest_name(args.channel)
    print("Steam client manifest: %s/%s" % (base, manifest))
    try:
        with urllib.request.urlopen(base + "/" + manifest, timeout=60) as response:
            text = response.read().decode("utf-8", "replace")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as error:
        print("Could not read the manifest: %s" % error, file=sys.stderr)
        return 1

    parts = parse_manifest(text)
    if not parts:
        print("The manifest lists no components; Valve may have changed its format.", file=sys.stderr)
        return 1
    total_bytes = sum(int(entry.get("size", 0)) for entry in parts.values())
    print("%d components, %s" % (len(parts), human(total_bytes)))

    if args.list:
        print()
        print("%s/%s" % (base, manifest))
        for name in sorted(parts):
            entry = parts[name]
            print("%s/%s" % (base, entry["file"]))
        return 0

    try:
        os.makedirs(args.out, exist_ok=True)
    except OSError as error:
        print("Could not create %s: %s" % (args.out, error), file=sys.stderr)
        return 1
    # The install script reads this when the device has no way to reach Valve, so ship it too.
    with open(os.path.join(args.out, manifest), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

    curl = find_curl()
    print("Downloader: %s" % (curl if curl else "python urllib - curl is not on PATH, expect this to be slow"))

    ordered = sorted(parts.items(), key=lambda item: -int(item[1].get("size", 0)))
    failed = []
    for index, (name, entry) in enumerate(ordered, 1):
        label = "%s (%d/%d)" % (name, index, len(ordered))
        if not download("%s/%s" % (base, entry["file"]), os.path.join(args.out, entry["file"]),
                        entry["sha2"], label, curl):
            failed.append(name)

    print()
    if failed:
        print("%d of %d components did not arrive; run this again to retry:" % (len(failed), len(ordered)))
        for name in failed:
            print("  %s" % name)
        return 1
    print("All %d components are in %s (%s)." % (len(ordered), os.path.abspath(args.out), human(total_bytes)))
    print("Copy that folder to the device and use \"Install from folder\" in the Steam client settings.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
