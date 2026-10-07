"""Reject an APK with missing or stale session helpers, including cached local bundles."""
from pathlib import Path
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
)


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
    return errors


if __name__ == '__main__':
    errors = check(sys.argv[1])
    if errors:
        sys.exit('\n'.join(errors))
    print('APK session helpers match source and the native payload is present')
