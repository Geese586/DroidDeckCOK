#!/usr/bin/env python3
"""Sign a built apk with this checkout's own key, handing over from the AOSP testkey.

Windows counterpart of tools/release/sign-apk.sh, for a machine that holds the key itself rather
than reading it from a CI secret. build-apk.bat runs it after the payload check; it works by hand
too:

    python tools/release/sign-apk-local.py app/build/outputs/apk/release/app-release.apk out.apk

Why a hand-over rather than a plain re-sign:

  - v1 and v2 are signed by the AOSP testkey and v3 by the local key, with a signed lineage
    testkey -> local key. An install made from an earlier build of this checkout - all of which
    are testkey-signed - updates in place and keeps its data (rootfs, Proton, Steam client)
    instead of being refused as a different signer.
  - the testkey loses its rollback right, so an apk signed with the testkey alone - a key
    everybody has, so anyone can make one - is refused as an update to this one. That is the
    whole point of signing with a private key.
  - --rotation-min-sdk-version 28: the lineage applies from Android 9, which is every device this
    app runs on (minSdk 26, and 26/27 fall back to the testkey signer).

Settings come from GoogleKeystore/signing.env, beside the key itself and inside a folder .gitignore
excludes (untracked - it holds a password): RELEASE_KEYSTORE, RELEASE_STORE_PASSWORD,
RELEASE_KEY_ALIAS, and optionally RELEASE_SIGNER_SHA256, the certificate digest the result must
carry so a wrong or swapped key is caught rather than shipped. A checkout without that folder has
no key to sign with, which is what sends the build back to the AOSP testkey.

default_signing_env() looks in $DROIDDECK_SIGNING_ENV, then GoogleKeystore/signing.env, then
.signing.env at the project root - the older location, still read so a checkout set up before the
move keeps working.

--optional makes a missing key a fall-back rather than a failure: exit code 3, nothing written, and
the caller keeps the testkey-signed apk it passed in. build-apk.bat passes it when the settings
were found by itself, so deleting GoogleKeystore takes the build back to the AOSP testkey instead
of stopping it; an explicit --own-key stays a failure, because then the key was asked for.

Passwords reach apksigner through the environment (--ks-pass env:VAR), so characters a command
line would mangle (&, *, =, quotes) need no escaping.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TESTKEY = REPO / "keystore" / "testkey.p12"
TESTKEY_ALIAS = "testkey"
TESTKEY_PASSWORD = "android"
# The public AOSP testkey's certificate, as sign-apk.sh also hardcodes it.
TESTKEY_SHA256 = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"

TS_PASS_VAR = "DROIDDECK_TESTKEY_PASS"
KEY_PASS_VAR = "DROIDDECK_STORE_PASS"

# Exit code for "there is no key here to sign with", which --optional turns from a failure into a
# fall-back to the testkey. 1 stays the code for "something is wrong, do not guess".
SKIP_EXIT = 3

# Where the settings that name the key live, best first: beside the key, in the one folder that
# never reaches git, then the project root, where they lived before the move.
SIGNING_ENV_LOCATIONS = (REPO / "GoogleKeystore" / "signing.env", REPO / ".signing.env")


def die(message: str) -> None:
    print("[FAIL] " + message, file=sys.stderr)
    raise SystemExit(1)


def run(command, **kwargs):
    """Echo and run a command, failing loudly rather than leaving a half-made apk behind."""
    printable = " ".join('"%s"' % part if " " in str(part) else str(part) for part in command)
    print("  $ " + printable)
    result = subprocess.run([str(part) for part in command], text=True, **kwargs)
    if result.returncode != 0:
        die("command failed (%d): %s" % (result.returncode, printable))
    return result


def capture(command):
    result = subprocess.run([str(part) for part in command], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        print(result.stdout)
        die("command failed (%d)" % result.returncode)
    return result.stdout


def read_signing_env(path: Path) -> dict:
    """Parse a KEY=value file. Values may be quoted - the shell sources this file too, and a
    password holding & or * has to be quoted there or it is read as shell syntax."""
    if not path.is_file():
        die("no signing settings at %s (set DROIDDECK_SIGNING_ENV to point elsewhere)" % path)
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        key, sep, value = line.partition("=")
        if not sep:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def default_signing_env() -> Path:
    """The settings file to read when the caller names none.

    $DROIDDECK_SIGNING_ENV wins outright. Otherwise the first location that exists is used, and
    failing that the preferred one, so the error names where the file belongs.
    """
    explicit = os.environ.get("DROIDDECK_SIGNING_ENV")
    if explicit:
        return Path(explicit)
    for candidate in SIGNING_ENV_LOCATIONS:
        if candidate.is_file():
            return candidate
    return SIGNING_ENV_LOCATIONS[0]


def find_build_tools(sdk: Path | None) -> Path:
    """Highest build-tools version holding both zipalign and apksigner.jar."""
    roots = []
    if sdk:
        roots.append(sdk / "build-tools")
    for name in ("DROIDDECK_ANDROID_SDK", "ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            roots.append(Path(value) / "build-tools")
    roots.append(Path("D:/Android/SDK/build-tools"))
    for root in roots:
        if not root.is_dir():
            continue
        def order(entry: Path):
            return [int(n) for n in re.findall(r"\d+", entry.name)] or [0]
        for entry in sorted((d for d in root.iterdir() if d.is_dir()), key=order, reverse=True):
            if (entry / "lib" / "apksigner.jar").is_file() and (
                    (entry / "zipalign.exe").is_file() or (entry / "zipalign").is_file()):
                return entry
    die("no build-tools with zipalign and apksigner.jar; pass --build-tools or --sdk")


def find_java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    found = shutil_which("java")
    if found:
        return found
    die("no java; set JAVA_HOME (build-apk.bat does)")


def shutil_which(name: str):
    import shutil
    return shutil.which(name)


def main() -> int:
    parser = argparse.ArgumentParser(description="Sign an apk with the local key over the testkey")
    parser.add_argument("apk", type=Path, help="the apk gradle produced (testkey-signed)")
    parser.add_argument("out", type=Path, help="where to write the signed apk")
    parser.add_argument("--env", type=Path, default=default_signing_env(),
                        help="settings file (default: GoogleKeystore/signing.env)")
    parser.add_argument("--keystore", help="override RELEASE_KEYSTORE")
    parser.add_argument("--alias", help="override RELEASE_KEY_ALIAS")
    parser.add_argument("--password", help="override RELEASE_STORE_PASSWORD")
    parser.add_argument("--expect", help="override RELEASE_SIGNER_SHA256 (the required v3 signer)")
    parser.add_argument("--optional", action="store_true",
                        help="no key to sign with is exit %d, not a failure: nothing is written "
                             "and the caller keeps the testkey-signed apk" % SKIP_EXIT)
    parser.add_argument("--build-tools", type=Path, help="build-tools dir holding apksigner.jar")
    parser.add_argument("--sdk", type=Path, help="Android SDK root, to find build-tools under")
    parser.add_argument("--work", type=Path, default=REPO / "build-logs" / "sign",
                        help="scratch directory (default: build-logs/sign)")
    args = parser.parse_args()

    def no_key(message: str) -> None:
        """There is no key here to sign with.

        With --optional that is a fall-back, not a failure: the build keeps the testkey-signed apk
        it passed in, and a checkout without the key folder still produces a package. Everything
        else below still dies, because a key that is present but unusable must not be papered over
        with a testkey signature the caller believes is something else.
        """
        if args.optional:
            print("[SKIP] " + message)
            raise SystemExit(SKIP_EXIT)
        die(message)

    if not args.apk.is_file():
        die("no apk at %s" % args.apk)
    if not TESTKEY.is_file():
        die("the AOSP testkey is missing at %s - it is what the hand-over starts from" % TESTKEY)

    if not args.env.is_file():
        no_key("no signing settings at %s (set DROIDDECK_SIGNING_ENV to point elsewhere)"
               % args.env)
    settings = read_signing_env(args.env)
    keystore = Path(args.keystore or settings.get("RELEASE_KEYSTORE") or "")
    if not str(keystore):
        die("%s does not set RELEASE_KEYSTORE" % args.env)
    if not keystore.is_absolute():
        keystore = REPO / keystore
        # The documented form is relative to the project root; reading the settings from a file
        # somewhere else (DROIDDECK_SIGNING_ENV) should not force their path to be rewritten.
        named = settings.get("RELEASE_KEYSTORE")
        if not keystore.is_file() and named and not args.keystore:
            beside = args.env.parent / named
            if beside.is_file():
                keystore = beside
    alias = args.alias or settings.get("RELEASE_KEY_ALIAS")
    password = args.password or settings.get("RELEASE_STORE_PASSWORD")
    expect = (args.expect or settings.get("RELEASE_SIGNER_SHA256") or "").strip().lower()
    if not keystore.is_file():
        no_key("no keystore at %s - the settings name it, so the key folder is incomplete"
               % keystore)
    if not alias:
        die("%s does not set RELEASE_KEY_ALIAS" % args.env)
    if password is None:
        die("%s does not set RELEASE_STORE_PASSWORD" % args.env)
    if expect and not re.fullmatch(r"[0-9a-f]{64}", expect):
        die("RELEASE_SIGNER_SHA256 is not a SHA-256 digest: %r" % expect)

    build_tools = args.build_tools or find_build_tools(args.sdk)
    apksigner_jar = build_tools / "lib" / "apksigner.jar"
    if not apksigner_jar.is_file():
        die("no apksigner.jar under %s" % build_tools)
    zipalign = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    if not zipalign.is_file():
        die("no zipalign under %s" % build_tools)
    java = find_java()

    # apksigner takes passwords as env:VAR, which keeps &, *, = and quotes out of the command line
    # entirely - and out of the process list.
    environment = dict(os.environ)
    environment[TS_PASS_VAR] = TESTKEY_PASSWORD
    environment[KEY_PASS_VAR] = password

    def signer(argv):
        return [java, "-jar", apksigner_jar] + argv

    # When the settings do not name the certificate, read it off the keystore: checking that the
    # right key signed the result has to happen either way.
    if not expect:
        keytool = Path(java).parent / ("keytool.exe" if os.name == "nt" else "keytool")
        if not keytool.is_file():
            die("no keytool beside java (%s); set RELEASE_SIGNER_SHA256" % java)
        listing = subprocess.run(
            [str(keytool), "-list", "-v", "-keystore", str(keystore),
             "-storepass:env", KEY_PASS_VAR, "-alias", alias],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environment)
        if listing.returncode != 0:
            print(listing.stdout)
            die("cannot read %s with that password (is the alias %r right?)" % (keystore, alias))
        found = re.search(r"SHA256:\s*((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})", listing.stdout)
        if not found:
            die("cannot tell which certificate %s holds; set RELEASE_SIGNER_SHA256" % keystore)
        expect = found.group(1).replace(":", "").lower()
        print("(RELEASE_SIGNER_SHA256 is not set; %s holds %s)" % (keystore.name, expect))

    args.work.mkdir(parents=True, exist_ok=True)
    aligned = args.work / (args.apk.stem + "-aligned.apk")
    lineage = args.work / "lineage"
    for stale in (aligned, lineage, Path(str(args.out) + ".idsig")):
        if stale.exists():
            stale.unlink()
    if args.out.exists():
        args.out.unlink()

    print("signing: %s" % args.apk.name)
    print("  key  : %s (%s)" % (keystore, alias))
    print("  from : the AOSP testkey, handed over by lineage")
    print("  expect v3 signer: %s" % expect)

    run([zipalign, "-p", "-f", "4", args.apk, aligned], stdout=subprocess.DEVNULL)
    run(signer(["rotate", "--out", lineage,
                "--old-signer", "--ks", TESTKEY, "--ks-type", "PKCS12",
                "--ks-pass", "env:" + TS_PASS_VAR, "--ks-key-alias", TESTKEY_ALIAS,
                "--key-pass", "env:" + TS_PASS_VAR, "--set-rollback", "false",
                "--new-signer", "--ks", keystore, "--ks-pass", "env:" + KEY_PASS_VAR,
                "--ks-key-alias", alias, "--key-pass", "env:" + KEY_PASS_VAR]),
        stdout=subprocess.DEVNULL, env=environment)
    run(signer(["sign",
                "--ks", TESTKEY, "--ks-type", "PKCS12", "--ks-pass", "env:" + TS_PASS_VAR,
                "--ks-key-alias", TESTKEY_ALIAS, "--key-pass", "env:" + TS_PASS_VAR,
                "--next-signer", "--ks", keystore, "--ks-pass", "env:" + KEY_PASS_VAR,
                "--ks-key-alias", alias, "--key-pass", "env:" + KEY_PASS_VAR,
                "--lineage", lineage, "--rotation-min-sdk-version", "28",
                "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                "--out", args.out, aligned]),
        stdout=subprocess.DEVNULL, env=environment)
    if Path(str(args.out) + ".idsig").exists():
        Path(str(args.out) + ".idsig").unlink()

    # What the apk must carry, checked rather than assumed. A rejection leaves nothing behind: the
    # caller keeps the gradle apk it passed in, and a half-acceptable one must not be picked up by
    # whatever comes next.
    def reject(message):
        if args.out.exists():
            args.out.unlink()
        die(message)

    if not args.out.is_file():
        die("apksigner reported success but wrote no %s" % args.out)

    certs = capture(signer(["verify", "--min-sdk-version", "28", "--print-certs", args.out]))
    seen = sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs)))
    if seen != [expect]:
        print(certs, file=sys.stderr)
        reject("Android 9+ sees signer(s) %s, not only the local key %s" % (seen, expect))

    # v1, so installers on old Android take it at all (they read no v2 or v3).
    with zipfile.ZipFile(args.out) as archive:
        names = archive.namelist()
    if not any(re.fullmatch(r"META-INF/.*\.(SF|RSA|DSA|EC)", name) for name in names):
        reject("no JAR signature under META-INF - an installer on old Android would refuse it")

    # A gate, not a source of text: apksigner verify says nothing at all when it succeeds.
    capture([java, "-jar", apksigner_jar, "verify", "--min-sdk-version", "26", args.out])
    lineage_text = capture(signer(["lineage", "--in", args.out, "--print-certs"]))
    handover = re.search(r"Signer #1 in lineage certificate SHA-256 digest: ([0-9a-f]{64})",
                         lineage_text)
    receiver = re.search(r"Signer #2 in lineage certificate SHA-256 digest: ([0-9a-f]{64})",
                         lineage_text)
    if not handover or handover.group(1) != TESTKEY_SHA256:
        print(lineage_text, file=sys.stderr)
        reject("the lineage does not start at the testkey - older installs could not update")
    if not receiver or receiver.group(1) != expect:
        print(lineage_text, file=sys.stderr)
        reject("the lineage does not hand over to the local key")
    # How many spaces come before the colon varies by apksigner version - both
    # "rollback capability: false" and "Has rollback capability      : false" are current output.
    before_handover = lineage_text.split("Signer #2 in lineage")[0]
    if not re.search(r"rollback capability\s*:\s*false", before_handover):
        print(lineage_text, file=sys.stderr)
        reject("the testkey kept its rollback right - a testkey-signed apk could replace this one")

    print("signed: %s (%.1f MiB)" % (args.out, args.out.stat().st_size / 1048576))
    print("  v1+v2 by the testkey, v3 by the local key %s" % expect)
    print("  installs over this checkout's earlier builds, and over nothing signed with the")
    print("  testkey alone - a key everyone has, so anyone can sign with it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
