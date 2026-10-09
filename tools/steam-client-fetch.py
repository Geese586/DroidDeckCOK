#!/usr/bin/env python3
"""Fetch the native arm64 Steam client's packages for DroidDeck's local install.

Valve's CDN is slow or unreachable from some networks, and the client is a 360 MB set of 17 zip
files listed in a small text manifest. Run this where the connection is better, copy the folder
it fills to the device, then use "Install from folder" in DroidDeck's Steam client settings: the
app hands that folder to the guest's droiddeck-steam-install, which checks every file against the
sha256 the manifest carries and downloads only the pieces the folder is missing.

    python steam-client-fetch.py                 # into ./steam-client, from whichever edge is quicker
    python steam-client-fetch.py --out D:\\steam  # somewhere else
    python steam-client-fetch.py --list          # just print the URLs, download nothing
    python steam-client-fetch.py --host HOST     # one host or base URL, no edge picking at all

Neither of Valve's client-update edges has a point of presence in mainland China, so which one is
quicker is a property of the line, not of the file. With no --host the tool measures both - one
small ranged read each, of the largest component - and takes the faster, falling back to the other
if a component still fails. DROIDDECK_STEAM_HOST does the same thing as --host.

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

OFFICIAL_HOST = "client-update.fastly.steamstatic.com"
# Valve serves identical bytes from both of its client-update edges. Neither is a Chinese mirror -
# Valve's China CDN hosts only carry game content - so the only lever is which edge the line
# reaches faster, and that differs per network: measured here, Akamai ran 4.6x the Fastly edge on a
# small component and 1.1x on a large one. Both are tried, whichever answers fastest first; the
# Fastly edge is the one the device's own install script uses.
CDN_EDGES = (
    "client-update.akamai.steamstatic.com",
    OFFICIAL_HOST,
)
CHANNELS = ("publicbeta", "steamdeck_publicbeta")
CHUNK = 1 << 20
PROBE_BYTES = 1 << 20


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


def probe(base, path, curl, sample=PROBE_BYTES):
    """Bytes per second reading [sample] bytes of [path] from [base], or None if it did not answer.

    A ranged read of a fixed size, so a slow edge cannot be mistaken for a fast one that happened
    to answer a short request first.
    """
    url = "%s/%s" % (base, path)
    started = time.time()
    if curl:
        command = [curl, "-f", "-L", "--ssl-no-revoke", "--max-time", "20",
                   "-r", "0-%d" % (sample - 1), "-o", os.devnull, url]
        try:
            completed = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            return None
        if completed.returncode != 0:
            return None
    else:
        request = urllib.request.Request(url, headers={
            "User-Agent": "DroidDeck", "Range": "bytes=0-%d" % (sample - 1)})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                read = 0
                while read < sample:
                    block = response.read(min(CHUNK, sample - read))
                    if not block:
                        break
                    read += len(block)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
            return None
    return sample / max(time.time() - started, 0.001)


def order_by_speed(bases, path, curl, sample=PROBE_BYTES):
    """Put the fastest edge first. Whatever does not answer keeps a place at the end anyway."""
    measured = []
    for base in bases:
        rate = probe(base, path, curl, sample)
        if rate is None:
            print("  %-44s no answer, tried last" % base.split("//")[-1])
            continue
        print("  %-44s %s/s" % (base.split("//")[-1], human(rate)))
        measured.append((rate, base))
    measured.sort(key=lambda item: -item[0])
    ordered = [base for _, base in measured]
    return ordered + [base for base in bases if base not in ordered]


def download(urls, dest, expected, label, curl=None, attempts=6):
    """Fetch [dest] from the first of [urls] that delivers [expected]; idempotent when done.

    A component that cannot be had from one edge is still worth asking another for, and since
    every edge serves the same bytes the partial file resumes straight across.
    """
    if os.path.isfile(dest) and sha256(dest) == expected:
        print("  %-44s already here and verified" % label[:44])
        return True
    part = dest + ".part"
    per_edge = max(1, attempts // max(len(urls), 1))
    for index, url in enumerate(urls):
        if index:
            print("  %-44s falling back to %s" % (label[:44], url.split("//")[-1].split("/")[0]))
        if fetch(url, part, dest, expected, label, curl, per_edge):
            return True
    return False


def fetch(url, part, dest, expected, label, curl, attempts):
    """One edge's worth of trying: [attempts] transfers into [part], renamed on a checksum match."""
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
    parser.add_argument("--host", default=os.environ.get("DROIDDECK_STEAM_HOST") or None,
                        help="one CDN host or whole base URL, used as-is with no edge picking; "
                             "default is to measure %s and take the faster" % " and ".join(CDN_EDGES))
    args = parser.parse_args()

    manifest = manifest_name(args.channel)
    bases = [base_url(args.host)] if args.host else [base_url(edge) for edge in CDN_EDGES]
    curl = find_curl()

    text = None
    for base in bases:
        try:
            with urllib.request.urlopen(base + "/" + manifest, timeout=60) as response:
                text = response.read().decode("utf-8", "replace")
            print("Manifest: %s/%s" % (base, manifest))
            break
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as error:
            print("  %-44s %s" % (base.split("//")[-1], error))
    if text is None:
        print("Could not read the manifest from any host.", file=sys.stderr)
        return 1

    parts = parse_manifest(text)
    if not parts:
        print("The manifest lists no components; Valve may have changed its format.", file=sys.stderr)
        return 1
    total_bytes = sum(int(entry.get("size", 0)) for entry in parts.values())
    print("%d components, %s" % (len(parts), human(total_bytes)))
    # The largest component is also the one whose speed says the most about the line.
    biggest = max(parts.values(), key=lambda entry: int(entry.get("size", 0)))["file"]

    if args.list:
        print()
        print("%s/%s" % (bases[0], manifest))
        for name in sorted(parts):
            print("%s/%s" % (bases[0], parts[name]["file"]))
        if len(bases) > 1:
            print()
            print("Measured before downloading, faster first: %s" % ", ".join(bases))
        return 0

    try:
        os.makedirs(args.out, exist_ok=True)
    except OSError as error:
        print("Could not create %s: %s" % (args.out, error), file=sys.stderr)
        return 1
    # The install script reads this when the device has no way to reach Valve, so ship it too.
    with open(os.path.join(args.out, manifest), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

    print("Downloader: %s" % (curl if curl else "python urllib - curl is not on PATH, expect this to be slow"))
    if len(bases) > 1:
        print("Picking the quicker CDN edge (%s of %s):" % (human(PROBE_BYTES), biggest))
        bases = order_by_speed(bases, biggest, curl)
        print("Using %s" % bases[0])

    ordered = sorted(parts.items(), key=lambda item: -int(item[1].get("size", 0)))
    failed = []
    for index, (name, entry) in enumerate(ordered, 1):
        label = "%s (%d/%d)" % (name, index, len(ordered))
        if not download(["%s/%s" % (base, entry["file"]) for base in bases],
                        os.path.join(args.out, entry["file"]), entry["sha2"], label, curl):
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
