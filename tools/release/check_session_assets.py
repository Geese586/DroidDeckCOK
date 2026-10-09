"""Reject an APK with missing or stale session helpers, including cached local bundles."""
from pathlib import Path
import hashlib
import sys
from zipfile import ZipFile


OVERLAY = Path(__file__).resolve().parents[1] / 'linuxfs/overlay'

# What tools/build_local.sh produces and a plain checkout does not have: the cross-compiled glibc
# preloads, proot itself, the compositor, the desktop helpers, the pinned downloads and the
# droiddeck-esync index. Names only, no checksums - these are somebody else's binaries and their
# bytes are whatever the last fetch pinned.
#
# The list exists because its absence used to be silent. Without libproot.so
# LinuxRuntime.isInstalled() is false however complete the rootfs is, so an APK built from a plain
# checkout installs, shows its UI, and then sits on the session loading screen forever - while every
# other check, this one included, still passed.
NATIVE = (
    'lib/arm64-v8a/libproot.so',
    'lib/arm64-v8a/libproot-loader.so',
    'assets/linuxfs/libfakeinput.so',
    'assets/linuxfs/libblsession.so',
    'assets/linuxfs/libblfastpath.so',
    'assets/linuxfs/i386/libfakeinput.so',
    'assets/linuxfs/i386/libblsession.so',
    'assets/linuxfs/x86_64/libfakeinput.so',
    'assets/linuxfs/x86_64/libblsession.so',
    'assets/linuxfs/x86_64/libvulkan-thunk.so',
    'assets/linuxfs/usr/local/bin/gamescope',
    'assets/linuxfs/usr/local/bin/mangoapp',
    'assets/linuxfs/usr/local/bin/droiddeck-clipboard',
    'assets/linuxfs/usr/local/bin/droiddeck-desktop',
    'assets/linuxfs/usr/local/bin/droiddeck-gpu',
    'assets/linuxfs/usr/local/bin/droiddeck-desktop-gpu',
    'assets/linuxfs/usr/local/lib/droiddeck/uruntime',
    'assets/linuxfs/usr/local/lib/droiddeck-wlroots/libwlroots-0.20.so',
    'assets/linuxfs/usr/local/lib/mangoapp/mangoapp',
    'assets/linuxfs/etc/xdg/labwc/autostart',
    'assets/linuxfs/etc/xdg/labwc/rc.xml',
    'assets/linuxfs/etc/xdg/lxqt/panel.conf',
    'assets/linuxfs/usr/lib/firefox/defaults/pref/droiddeck.js',
    'assets/droiddeck-esync/index.json',
    'assets/droiddeck-esync/index.json.sig',
    # The Linux payload's own later additions, which a checkout that never re-ran the cross build
    # lacks: the SSBS preload a game's environment can ask for, and the msitools set the Windows
    # component installer drives.
    'assets/linuxfs/libssbs.so',
    'assets/linuxfs/usr/local/lib/droiddeck-msitools/msiinfo',
    'assets/linuxfs/usr/local/lib/droiddeck-msitools/cabextract',
    # The helper that owns Android's output stream when the client or a game is put on the
    # DirectAudio relay. Its absence is silent in the same way: the relay part is added to the
    # session and then has no program to run.
    'lib/arm64-v8a/libdirectaudiorelay.so',
)

# The daemon's sink modules ride inside the audio bundle, and CI compiles two of them in while it
# builds (build.yml, "Build the AAudio sink into the audio bundle") - they are not in the bundle
# the source tree carries. A build that skips that step, as a plain gradle one does, ships the
# older bundle whose only sink is module-aaudio-classic-sink.so; the daemon then fails
# "load-module module-aaudio-sink", no sink exists, and the client's Settings and every game come
# up with "no output device" - while every other check here passes. Pin the bundle CI produces,
# and unpack it to name what is missing when the pin does not match.
PULSE_BUNDLE = 'assets/pulseaudio.tzst'
PULSE_BUNDLE_SHA256 = 'ae8a394e381c8c4592f158e45ad4ec74a317bb93462e9ce3618d7dc897ea544a'
PULSE_MODULES = (
    'modules/arm64/module-aaudio-sink.so',
    'modules/arm64/module-directaudio-sink.so',
)


def audio_bundle_problem(content):
    """What the daemon will not find in this audio bundle, or None when it is usable."""
    if hashlib.sha256(content).hexdigest() == PULSE_BUNDLE_SHA256:
        return None
    try:
        import io
        import tarfile
        import zstandard
    except ImportError:
        return (PULSE_BUNDLE + ' is not the bundle this fork builds with (sha256 '
                + hashlib.sha256(content).hexdigest() + ', expected ' + PULSE_BUNDLE_SHA256
                + '), and the zstandard module is not here to say more. The sink module it has to '
                'carry is probably missing, and the session would come up with no output device.')
    present = set()
    reader = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(content))
    with tarfile.open(fileobj=reader, mode='r|') as tar:
        for member in tar:
            present.add(member.name[2:] if member.name.startswith('./') else member.name)
    missing = [name for name in PULSE_MODULES if name not in present]
    if not missing:
        return None
    return (PULSE_BUNDLE + ' carries no ' + ', '.join(missing) + ', so the daemon cannot load the '
            'sink the session asks for and the client reports no output device. Re-run '
            'tools/build_local.sh, or take the bundle out of the same version\'s release APK.')


def check(apk, overlay=OVERLAY):
    sources = list((overlay / 'usr/local/bin').glob('droiddeck-*'))
    sources += [path for path in [overlay / 'usr/local/bin/steam-compatibility'] if path.is_file()]
    sources += [path for path in (overlay / 'usr/bin').rglob('*') if path.is_file()]
    errors = []
    with ZipFile(apk) as package:
        present = {info.filename for info in package.infolist()}
        for source in sources:
            asset = 'assets/linuxfs/' + source.relative_to(overlay).as_posix()
            try:
                content = package.read(asset)
            except KeyError:
                errors.append('missing ' + asset)
                continue
            if content != source.read_bytes():
                errors.append('stale ' + asset)
        for name in NATIVE:
            if name not in present:
                errors.append('missing ' + name)
        try:
            bundle = package.read(PULSE_BUNDLE)
        except KeyError:
            errors.append('missing ' + PULSE_BUNDLE)
        else:
            problem = audio_bundle_problem(bundle)
            if problem:
                errors.append(problem)
    return errors


if __name__ == '__main__':
    errors = check(sys.argv[1])
    if errors:
        sys.exit('\n'.join(errors))
    print('APK session helpers match source and the native payload is present')
