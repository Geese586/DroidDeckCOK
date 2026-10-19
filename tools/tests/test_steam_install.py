import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-install'
# A bare "bash" is not portable on a Windows development machine: the OS searches its own system
# directory before PATH, and System32/bash.exe there is the WSL launcher rather than Git's bash -
# which is a different shell from the one these tests were written against, and on a locked-down
# machine it is not allowed to start at all. Point TEST_BASH at an absolute path there. The guest
# and CI keep the default.
#
# Naming that path is necessary but not sufficient: Git's bash prepends its own /mingw64/bin -
# where the real curl, sha256sum and unzip live - ahead of whatever PATH it is handed, so the
# fakes below are shadowed and `curl` reaches Valve instead. That is a hang on the network rather
# than a failing assertion, and no PATH a caller can build changes it. Run this suite on Linux, as
# CI and the guest do.
#
# On a Windows development machine it can still be run, by shadowing curl with a shell function
# rather than a file - a function wins over any PATH lookup, including the one Git's bash forces -
# and pointing BASH_ENV at it:
#
#   BASH_ENV=fake-curl.sh TEST_BASH='C:\Program Files\...\usr\bin\bash.exe' python -m unittest ...
#
# Six of the tests then still fail, on that machine only: they need the *executable bit* on
# STEAM/steamrtarm64/steam, which the script sets with chmod on the guest and Linux keeps across
# the Python-written re-writes these tests do, while Windows has no such bit to keep. Those six
# pass on Linux and are not a signal about the script.
BASH = os.environ.get('TEST_BASH', 'bash')


def write_lf(path, text):
    """Writes [text] into [path] with Unix line endings, whatever the host would have chosen.

    The script under test reads these files the way the guest holds them - awk over the manifest's
    lines, `grep -cx start` over the client's own counter, `cut` over a checksum - and there a
    trailing carriage return is part of the field rather than whitespace. Python's text mode turns
    every \\n into \\r\\n on a Windows development machine, so a fixture written there is one long
    line with \\r inside it and every parse of it fails: the manifest comes back with a 65-character
    "sha256" and the run dies on "no properly formatted SHA256 checksum lines found". Linux CI and
    the guest never saw it, which is why the suite has only ever run there.
    """
    with open(path, 'w', encoding='utf-8', newline='\n') as file:
        file.write(text)


class SteamInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'home'
        self.home.mkdir()
        self.data = self.root / 'data'
        self.steam = self.data / 'Steam'
        self.cdn = self.root / 'cdn'
        self.cdn.mkdir()
        self.requests = self.root / 'requests'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        # Only the network is faked: exercise real manifest parsing, checksums and zip extraction.
        # A URL is served from $TEST_CDN/<host>/ first when that directory exists, so a test can
        # make one CDN edge fail and the other one serve - which is what the fallback is for.
        curl = self.bin / 'curl'
        write_lf(curl, '''#!/bin/bash
set -eu
url= dest=
while [ $# -gt 0 ]; do
  case $1 in
    -o) dest=${2:-}; shift; if [ $# -gt 0 ]; then shift; fi ;;
    -*) shift ;;
    *) url=$1; shift ;;
  esac
done
printf '%s\\n' "$url" >> "$TEST_REQUESTS"
# A test that taps another edge mid-file needs a file that is still arriving when it taps, and a
# copy is instantaneous: this holds the request open for as long as the test asks.
if [ -n "${TEST_CURL_SLEEP:-}" ]; then sleep "$TEST_CURL_SLEEP"; fi
host=${url#*//}
host=${host%%/*}
if [ -f "$TEST_CDN/$host/${url##*/}" ]; then
  cp "$TEST_CDN/$host/${url##*/}" "$dest"
else
  cp "$TEST_CDN/${url##*/}" "$dest"
fi
''')
        curl.chmod(0o755)
        # macOS has shasum; the guest and Linux CI have sha256sum.
        if shutil.which('sha256sum') is None:
            sha = self.bin / 'sha256sum'
            write_lf(sha, '#!/bin/bash\nexec shasum -a 256 "$@"\n')
            sha.chmod(0o755)
        if shutil.which('sha1sum') is None:
            sha1 = self.bin / 'sha1sum'
            write_lf(sha1, '#!/bin/bash\nexec shasum -a 1 "$@"\n')
            sha1.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), XDG_DATA_HOME=str(self.data),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TEST_CDN=str(self.cdn), TEST_REQUESTS=str(self.requests))
        for channel in ('steamdeck_publicbeta',):
            archive = self.cdn / (channel + '.zip')
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('steamrtarm64/steam', channel)
                package.writestr('linuxarm64/wrapper', 'wrapper')
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            # "size" is not decoration: the script reads the manifest's components with
            # `awk -F'"'` into "block file size sha", so a fixture without it shifts the digest into
            # the size column and leaves the checksum field empty - every install then dies on
            # "no properly formatted SHA256 checksum lines found". Valve's own manifest always
            # carries it, so a fixture that does not is not the thing the script is written against.
            write_lf(
                self.cdn / ('steam_client_' + channel + '_linuxarm64'),
                '"linuxarm64"\n{\n\t"version" "123456"\n'
                '\t"client_linuxarm64_linuxarm64"\n\t{\n'
                f'\t\t"file" "{archive.name}"\n\t\t"size" "{archive.stat().st_size}"\n'
                f'\t\t"sha2" "{digest}"\n'
                '\t}\n}\n')

    def install(self, channel=None, local=None, offline=False, cdn=None, order=None,
                launch=None, curl_sleep=None, sync=False):
        env = self.env.copy()
        if channel is not None:
            env['BL_STEAM_CHANNEL'] = channel
        if local is not None:
            env['BL_STEAM_LOCAL_DIR'] = str(local)
        if offline:
            env['BL_STEAM_LOCAL_ONLY'] = '1'
        if cdn is not None:
            env['BL_STEAM_CDN'] = cdn
        if order is not None:
            env['BL_STEAM_CDN_ORDER'] = order
        if launch is not None:
            env['BL_LAUNCH_DIR'] = str(launch)
        if curl_sleep is not None:
            env['TEST_CURL_SLEEP'] = str(curl_sleep)
        if sync:
            env['BL_STEAM_SYNC'] = '1'
        return subprocess.run([BASH, str(SCRIPT)], env=env, text=True, capture_output=True)

    def local_folder(self):
        """What a user who fetched the packages elsewhere would put on the device: the manifest
        and the components, under the names the manifest itself uses."""
        folder = self.root / 'local'
        folder.mkdir()
        for source in self.cdn.glob('steam_client_*'):
            (folder / source.name).write_bytes(source.read_bytes())
        (folder / 'steamdeck_publicbeta.zip').write_bytes((self.cdn / 'steamdeck_publicbeta.zip').read_bytes())
        return folder

    def component(self, folder, block, marker):
        """One component as Valve publishes it: a zip, named after its own sha1, holding the
        marker so which build got unpacked is visible in the result."""
        staged = folder / (block + '.zip')
        with zipfile.ZipFile(staged, 'w') as package:
            package.writestr('steamrtarm64/' + block, marker)
        final = folder / (block + '.zip.' + hashlib.sha1(staged.read_bytes()).hexdigest())
        staged.rename(final)
        return final

    def write_manifest(self, folder, version, entries):
        lines = ['"linuxarm64"', '{', '\t"version"\t\t"' + version + '"']
        for block, archive in entries:
            lines += [
                '\t"' + block + '"',
                '\t{',
                '\t\t"file"\t\t"' + archive.name + '"',
                '\t\t"size"\t\t"' + str(archive.stat().st_size) + '"',
                '\t\t"sha2"\t\t"' + hashlib.sha256(archive.read_bytes()).hexdigest() + '"',
                '\t}',
            ]
        lines.append('}')
        write_lf(folder / 'steam_client_steamdeck_publicbeta_linuxarm64', '\n'.join(lines) + '\n')

    def assert_installed(self, channel='steamdeck_publicbeta'):
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(channel + '\n', (self.steam / 'package/beta').read_text())
        self.assertEqual('123456\n', (self.steam / 'package/droiddeck-installed').read_text())
        # What the client's own bootstrapper reads before it decides anything. Without it every
        # start is a fresh install, over the network, of a client that is already unpacked here.
        self.assertTrue(
            (self.steam / ('package/steam_client_' + channel + '_linuxarm64.manifest')).is_file())
        client = self.steam / 'steamrtarm64/steam'
        self.assertTrue(os.access(client, os.X_OK))
        self.assertEqual(channel, client.read_text())
        self.assertEqual((self.steam / 'linuxarm64').resolve(), (self.home / '.steam/sdkarm64').resolve())
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_' + channel + '_linuxarm64',
            'https://client-update.fastly.steamstatic.com/' + channel + '.zip',
        ], self.requests.read_text().splitlines())

    def test_downloads_the_deck_beta_client(self):
        self.assert_installed()

    def test_an_install_from_another_branch_is_left_for_steam_to_move(self):
        # An older app could install publicbeta; droiddeck-session moves the client at its start.
        self.assert_installed()
        requests = self.requests.read_text()
        write_lf(self.steam / 'package/beta', 'publicbeta\n')
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(requests, self.requests.read_text())
        self.assertEqual('publicbeta\n', (self.steam / 'package/beta').read_text())

    def test_failed_download_has_no_ready_marker_and_can_retry(self):
        archive = self.cdn / 'steamdeck_publicbeta.zip'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupt download')
        self.assertNotEqual(0, self.install().returncode)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())
        archive.write_bytes(original)
        self.assertEqual(0, self.install().returncode)
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())

    def break_client(self, starts):
        self.assert_installed()
        self.requests.unlink()
        package = self.steam / 'package'
        (self.steam / 'steamrtarm64/steam').write_text('half updated')
        (package / 'steam_client_steamdeck_publicbeta_linuxarm64.manifest').write_text('manifest')
        (package / 'steam_client_steamdeck_publicbeta_linuxarm64.installed').write_text('installed')
        good = b'complete package'
        (package / ('bins_linuxarm64.zip.vz.' + hashlib.sha1(good).hexdigest() + '_16')).write_bytes(good)
        (package / ('bins_misc_linuxarm64.zip.vz.' + hashlib.sha1(b'whole').hexdigest() + '_5')).write_bytes(b'wh')
        (package / ('strings_all.zip.' + hashlib.sha1(b'strings').hexdigest())).write_bytes(b'strings')
        (self.steam / '.crash').write_text('')
        write_lf(package / 'droiddeck-unconfirmed', 'start\n' * starts)
        return package

    def test_two_starts_with_nothing_shown_fetch_the_client_again(self):
        package = self.break_client(2)
        self.assert_installed()
        self.assertEqual('repaired\n', (package / 'droiddeck-unconfirmed').read_text())
        self.assertFalse((self.steam / '.crash').exists())
        # The repair takes the client's own records with the stamp - they describe the build it is
        # throwing away - and the install that follows writes the manifest it used back.
        self.assertEqual(['steam_client_steamdeck_publicbeta_linuxarm64.manifest'],
                         sorted(p.name for p in package.glob('steam_client_*')))
        self.assertEqual(['bins_linuxarm64.zip.vz.', 'strings_all.zip.'],
                         sorted(p.name[:p.name.rindex('.') + 1] for p in package.glob('*.zip.*')))

    def test_one_start_with_nothing_shown_is_left_alone(self):
        package = self.break_client(1)
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())
        self.assertEqual('half updated', (self.steam / 'steamrtarm64/steam').read_text())
        self.assertTrue((package / 'steam_client_steamdeck_publicbeta_linuxarm64.installed').exists())

    def test_a_repair_is_not_repeated_until_the_client_has_been_seen(self):
        package = self.break_client(2)
        self.assertEqual(0, self.install().returncode)
        self.requests.unlink()
        # Appended rather than rewritten, and as Unix lines: the client's own counter is read with
        # `grep -cx start`, where a carriage return on the end makes every line "start\r".
        with open(package / 'droiddeck-unconfirmed', 'a', encoding='utf-8', newline='\n') as marker:
            marker.write('start\nstart\nstart\n')
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())

    def test_requested_repair_runs_once_and_is_retried_until_it_completes(self):
        package = self.break_client(0)
        (package / 'droiddeck-unconfirmed').unlink()
        request = self.home / '.bl-steam-repair'
        request.write_text('1\n')
        archive = self.cdn / 'steamdeck_publicbeta.zip'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupt download')
        self.assertNotEqual(0, self.install().returncode)
        self.assertTrue(request.exists())
        self.assertFalse((package / 'droiddeck-installed').exists())
        archive.write_bytes(original)
        self.requests.unlink()
        self.assert_installed()
        self.assertFalse(request.exists())
        self.requests.unlink()
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())

    def test_invalid_channel_fails_before_any_download(self):
        result = self.install('../unknown')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unsupported Steam channel', result.stderr)
        self.assertFalse(self.requests.exists())

    def test_a_local_folder_supplies_the_components_and_valve_still_names_them(self):
        result = self.install('steamdeck_publicbeta', local=self.local_folder())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())
        self.assertEqual('123456\n', (self.steam / 'package/droiddeck-installed').read_text())
        self.assertEqual('steamdeck_publicbeta', (self.steam / 'steamrtarm64/steam').read_text())
        # Valve's manifest is read - it is what decides which components the install wants, and a
        # folder filled a week ago names last week's - and nothing else is: every component it
        # names is already in the folder.
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64',
        ], self.requests.read_text().splitlines())

    def test_an_offline_local_folder_installs_without_the_network(self):
        """BL_STEAM_LOCAL_ONLY is the offline device's: the folder's own manifest is used and the
        network is not touched at all."""
        result = self.install('steamdeck_publicbeta', local=self.local_folder(), offline=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('steamdeck_publicbeta', (self.steam / 'steamrtarm64/steam').read_text())
        self.assertFalse(self.requests.exists(),
                         self.requests.read_text() if self.requests.exists() else '')

    def test_the_verified_packages_are_left_where_the_clients_own_updater_looks(self):
        """The bootstrapper checks package/<file> - the sha1 in the name is the file's own -
        before it downloads anything, so leaving them is what keeps a first start from fetching
        the same 344 MB over the link the folder exists to avoid."""
        self.assert_installed('steamdeck_publicbeta')
        self.assertTrue((self.steam / 'package/steamdeck_publicbeta.zip').is_file())

    def test_a_stale_folder_only_buys_the_components_valve_replaced(self):
        folder = self.root / 'stale'
        folder.mkdir()
        kept = self.component(folder, 'keep_linuxarm64_linuxarm64', 'kept from the folder')
        replaced = self.component(folder, 'gone_all', 'last week\'s build')
        self.write_manifest(folder, '123455', [
            ('keep_linuxarm64_linuxarm64', kept),
            ('gone_all', replaced),
        ])
        # Valve has replaced one of the two and left the other exactly as it was.
        cdn_kept = self.cdn / kept.name
        shutil.copy(kept, cdn_kept)
        cdn_new = self.component(self.cdn, 'gone_all', 'this week\'s build')
        self.write_manifest(self.cdn, '123456', [
            ('keep_linuxarm64_linuxarm64', cdn_kept),
            ('gone_all', cdn_new),
        ])
        result = self.install('steamdeck_publicbeta', local=folder)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64',
            'https://client-update.fastly.steamstatic.com/' + cdn_new.name,
        ], self.requests.read_text().splitlines())
        self.assertEqual('this week\'s build', (self.steam / 'steamrtarm64/gone_all').read_text())
        self.assertEqual('kept from the folder', (self.steam / 'steamrtarm64/keep_linuxarm64_linuxarm64').read_text())
        # Both are in package/ for the client, the one that came off the folder included.
        self.assertTrue((self.steam / 'package' / cdn_new.name).is_file())
        self.assertTrue((self.steam / 'package' / kept.name).is_file())

    def test_a_damaged_local_copy_is_downloaded_again_when_the_network_is_there(self):
        folder = self.local_folder()
        (folder / 'steamdeck_publicbeta.zip').write_bytes(b'damaged while copying it over')
        result = self.install('steamdeck_publicbeta', local=folder)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('steamdeck_publicbeta', (self.steam / 'steamrtarm64/steam').read_text())
        self.assertIn('/steamdeck_publicbeta.zip\n', self.requests.read_text())

    def test_a_component_the_first_cdn_host_will_not_serve_comes_from_the_other(self):
        """Fastly and Akamai are different edges on different networks: one that cannot serve a
        file is a retry, not the end of the install."""
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', alternate / 'steamdeck_publicbeta.zip')
        (self.cdn / 'steamdeck_publicbeta.zip').unlink()
        result = self.install('steamdeck_publicbeta')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64',
            'https://client-update.fastly.steamstatic.com/steamdeck_publicbeta.zip',
            'https://client-update.akamai.steamstatic.com/steamdeck_publicbeta.zip',
        ], self.requests.read_text().splitlines())
        self.assertEqual('steamdeck_publicbeta', (self.steam / 'steamrtarm64/steam').read_text())

    def test_a_pinned_cdn_host_is_the_only_one_used(self):
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', alternate / 'steamdeck_publicbeta.zip')
        result = self.install('steamdeck_publicbeta', cdn='akamai')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('fastly', self.requests.read_text())

    def test_a_measured_order_decides_which_edge_is_asked_first(self):
        """The line test's ranking, short of the 2x a pin needs: fastest first, the other kept as
        the fallback. This is the shape that had 675 KB/s running ahead of a measured 1.0 MB/s -
        the app knew the order and never told the script."""
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', alternate / 'steamdeck_publicbeta.zip')
        order = 'client-update.akamai.steamstatic.com client-update.fastly.steamstatic.com'
        result = self.install('steamdeck_publicbeta', order=order)
        self.assertEqual(0, result.returncode, result.stderr)
        asked = self.requests.read_text()
        self.assertNotIn('fastly', asked)
        self.assertIn('client-update.akamai.steamstatic.com', asked)

    def test_an_order_still_falls_back_to_the_other_edge(self):
        """An order is a preference, not a pin: the edge named second serves what the first cannot.
        That is why a 1.5x lead is safe to act on, where a pin would trade a slow install for a
        failed one."""
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        # The client's own package is left only on fastly, so akamai cannot finish on its own. The
        # flat copy has to go as well: the fake curl falls back to it when a host's own directory
        # has no such file, which would let akamai serve the package and hide the fallback.
        fastly = self.cdn / 'client-update.fastly.steamstatic.com'
        fastly.mkdir()
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', fastly / 'steamdeck_publicbeta.zip')
        (self.cdn / 'steamdeck_publicbeta.zip').unlink()
        order = 'client-update.akamai.steamstatic.com client-update.fastly.steamstatic.com'
        result = self.install('steamdeck_publicbeta', order=order)
        self.assertEqual(0, result.returncode, result.stderr)
        asked = self.requests.read_text().splitlines()
        self.assertTrue(asked[0].startswith('https://client-update.akamai.steamstatic.com/'))
        self.assertTrue(any('steamdeck_publicbeta.zip' in line and 'fastly' in line for line in asked))

    def test_a_tap_moves_the_file_in_flight_to_the_other_edge(self):
        """The list on the loading screen is not advice for the next run: a tap restarts the file
        that is coming down on the edge behind it. The app writes the edge's name into
        $BL_LAUNCH_DIR/steam-line - the same directory steam-stop and steam-game use - and this
        script polls it once a second while a fetch is running."""
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        # Both edges can serve the component; only the order says which one is asked first, so a
        # request to the second one can only come from the tap.
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', alternate / 'steamdeck_publicbeta.zip')
        launch = self.root / 'launch'
        launch.mkdir()

        def tap():
            for _ in range(200):
                if self.requests.exists() and self.requests.read_text().strip():
                    break
                time.sleep(0.05)
            (launch / 'steam-line').write_text('akamai\n')

        thread = threading.Thread(target=tap)
        thread.start()
        try:
            result = self.install(
                'steamdeck_publicbeta',
                order='client-update.fastly.steamstatic.com client-update.akamai.steamstatic.com',
                launch=launch,
                # Long enough that the tap lands while the first file is still in flight.
                curl_sleep=1.5,
            )
        finally:
            thread.join()
        self.assertEqual(0, result.returncode, result.stderr)
        asked = self.requests.read_text().splitlines()
        self.assertTrue(asked[0].startswith('https://client-update.fastly.steamstatic.com/'), asked)
        self.assertTrue(
            any(line.startswith('https://client-update.akamai.steamstatic.com/') for line in asked),
            asked,
        )
        self.assertIn('switching this download to client-update.akamai.steamstatic.com', result.stdout)

    def test_a_tap_made_before_the_first_fetch_decides_where_it_starts(self):
        """The list on the loading screen used to take taps only once the install step began, so on
        a first run it sat there visible and deaf while the runtime and the session came up. The
        file is read when the edge queue is built as well as during a fetch, so a choice made then
        is the edge the download *starts* on - nothing is killed and nothing restarts."""
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', alternate / 'steamdeck_publicbeta.zip')
        launch = self.root / 'launch'
        launch.mkdir()
        write_lf(launch / 'steam-line', 'akamai\n')
        result = self.install(
            'steamdeck_publicbeta',
            order='client-update.fastly.steamstatic.com client-update.akamai.steamstatic.com',
            launch=launch,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        asked = self.requests.read_text().splitlines()
        self.assertTrue(asked[0].startswith('https://client-update.akamai.steamstatic.com/'), asked)
        self.assertNotIn('switching this download', result.stdout)
        self.assertEqual('steamdeck_publicbeta', (self.steam / 'steamrtarm64/steam').read_text())

    def test_a_tap_made_before_the_first_fetch_is_still_a_preference(self):
        """A tap leads the queue but does not become a pin: if the edge it names cannot serve a
        file, the other one still does. A choice made on the loading screen must never be able to
        turn a slow install into a failed one."""
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        # The client's own package is left only on fastly, and not in the flat fallback either.
        fastly = self.cdn / 'client-update.fastly.steamstatic.com'
        fastly.mkdir()
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', fastly / 'steamdeck_publicbeta.zip')
        (self.cdn / 'steamdeck_publicbeta.zip').unlink()
        launch = self.root / 'launch'
        launch.mkdir()
        write_lf(launch / 'steam-line', 'akamai\n')
        result = self.install('steamdeck_publicbeta', launch=launch)
        self.assertEqual(0, result.returncode, result.stderr)
        asked = self.requests.read_text().splitlines()
        self.assertTrue(asked[0].startswith('https://client-update.akamai.steamstatic.com/'), asked)
        self.assertTrue(any('steamdeck_publicbeta.zip' in line and 'fastly' in line for line in asked), asked)
        self.assertEqual('steamdeck_publicbeta', (self.steam / 'steamrtarm64/steam').read_text())

    def test_a_tapped_edge_is_not_abandoned_for_being_slow(self):
        """A tap is a decision, not a hint.

        The automatic rule - a host that delivers less than BL_SLOW_BYTES in BL_SLOW_WINDOW is
        dropped for the other one - exists for the edge nobody chose. Applied to a tapped edge it
        read the choice back off the chip half a minute later and put the bytes on the other edge,
        which is exactly what "switching between them makes no difference" looks like from the
        outside (DroidDeckCOK). A tapped edge that stops answering outright still falls back: that
        is a failed fetch, not a slow one, and the test below it covers it.
        """
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        shutil.copy(self.cdn / 'steamdeck_publicbeta.zip', alternate / 'steamdeck_publicbeta.zip')
        launch = self.root / 'launch'
        launch.mkdir()
        write_lf(launch / 'steam-line', 'akamai\n')
        # A fetch that takes three seconds and delivers almost nothing for the first one: without
        # the exemption this is abandoned after a second and finished on fastly instead.
        self.env['BL_SLOW_WINDOW'] = '1'
        self.env['BL_SLOW_BYTES'] = '999999'
        result = self.install('steamdeck_publicbeta', launch=launch, curl_sleep=3)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('this CDN edge is slow', result.stdout)
        asked = self.requests.read_text().splitlines()
        self.assertFalse([line for line in asked if 'fastly' in line], asked)
        self.assertTrue(all('akamai' in line for line in asked), asked)

    def test_an_installed_client_is_updated_through_the_tapped_edge(self):
        """BL_STEAM_SYNC=1, the flag "advanced play" sets.

        An installed client is normally left to its own bootstrapper, which fetches Valve's update
        over an edge this app cannot steer - so the loading screen's chips steer nothing on the
        download the user is actually watching. With the flag the install reads Valve's manifest
        itself, fetches the components that changed through the same queue the chips move, and
        leaves the ones that did not change alone.
        """
        self.assert_installed()
        self.requests.unlink()
        # Valve publishes a new build: one component is new, the other one keeps its name. Only
        # blocks named *_all or *_linuxarm64_linuxarm64 are components of the arm64 client, so the
        # new one has to be named the second way to be seen at all.
        changed = self.component(self.cdn, 'steamrt_linuxarm64_linuxarm64', 'second build')
        self.write_manifest(self.cdn, '654321', [
            ('client_linuxarm64_linuxarm64', self.cdn / 'steamdeck_publicbeta.zip'),
            ('steamrt_linuxarm64_linuxarm64', changed),
        ])
        alternate = self.cdn / 'client-update.akamai.steamstatic.com'
        alternate.mkdir()
        for staged in self.cdn.glob('steam_client_*'):
            shutil.copy(staged, alternate / staged.name)
        for staged in self.cdn.iterdir():
            if staged.is_file():
                shutil.copy(staged, alternate / staged.name)
        launch = self.root / 'launch'
        launch.mkdir()
        write_lf(launch / 'steam-line', 'akamai\n')
        result = self.install('steamdeck_publicbeta', launch=launch, sync=True)
        self.assertEqual(0, result.returncode, result.stderr)
        asked = self.requests.read_text().splitlines()
        self.assertTrue(asked[0].startswith('https://client-update.akamai.steamstatic.com/'), asked)
        self.assertIn('is already on the device', result.stdout)
        self.assertFalse([line for line in asked if 'steamdeck_publicbeta.zip' in line], asked)
        self.assertTrue(any(changed.name in line for line in asked), asked)
        self.assertEqual('654321\n', (self.steam / 'package/droiddeck-installed').read_text())

    def test_a_synced_client_that_is_current_costs_the_manifest_and_nothing_else(self):
        """The flag asks a question every session, so the answer has to be cheap: one manifest,
        no unpacking, no components - and the version check is the client's own record, which is
        the one its bootstrapper reads."""
        self.assert_installed()
        self.requests.unlink()
        result = self.install('steamdeck_publicbeta', sync=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ['https://client-update.fastly.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64'],
            self.requests.read_text().splitlines())
        self.assertIn('up to date', result.stdout)

    def test_a_sync_that_cannot_reach_valve_starts_what_is_installed(self):
        """A blocked edge must not turn into a session that will not open: an installed client is
        the one the next launch would have started anyway."""
        self.assert_installed()
        self.requests.unlink()
        (self.cdn / 'steam_client_steamdeck_publicbeta_linuxarm64').unlink()
        result = self.install('steamdeck_publicbeta', sync=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('starting the client that is installed', result.stdout)

    def test_a_local_folder_is_only_read_flat(self):
        """A subfolder is invisible to the install, which is why steam-client-fetch.py fills one
        folder rather than a tree."""
        folder = self.local_folder()
        nested = folder / 'nested'
        nested.mkdir()
        (nested / 'steamdeck_publicbeta.zip').write_bytes(
            (folder / 'steamdeck_publicbeta.zip').read_bytes())
        (folder / 'steamdeck_publicbeta.zip').unlink()
        result = self.install('steamdeck_publicbeta', local=folder, offline=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('is not in', result.stderr)

    def test_a_local_folder_missing_a_component_falls_back_to_the_cdn(self):
        folder = self.local_folder()
        (folder / 'steamdeck_publicbeta.zip').unlink()
        result = self.install('steamdeck_publicbeta', local=folder)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64',
            'https://client-update.fastly.steamstatic.com/steamdeck_publicbeta.zip',
        ], self.requests.read_text().splitlines())

    def test_offline_stops_at_the_first_component_the_folder_lacks(self):
        folder = self.local_folder()
        (folder / 'steamdeck_publicbeta.zip').unlink()
        result = self.install('steamdeck_publicbeta', local=folder, offline=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('and the device is offline', result.stderr)
        self.assertFalse(self.requests.exists())
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())

    def test_a_damaged_local_copy_is_refused_like_a_bad_download(self):
        """The manifest is what vouches for a file, wherever the file came from."""
        folder = self.local_folder()
        (folder / 'steamdeck_publicbeta.zip').write_bytes(b'damaged while copying it over')
        result = self.install('steamdeck_publicbeta', local=folder, offline=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())
        self.assertFalse((self.steam / 'package/beta').exists())

    def test_a_folder_with_no_components_at_all_is_not_a_local_install(self):
        folder = self.local_folder()
        (folder / 'steamdeck_publicbeta.zip').unlink()
        (folder / 'steam_client_steamdeck_publicbeta_linuxarm64').unlink()
        result = self.install('steamdeck_publicbeta', local=folder, offline=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())

    def test_offline_refuses_a_folder_whose_manifest_is_missing(self):
        """Every component is there and the manifest is not, offline: the one thing that must not
        happen is a download. The app refuses this folder before it starts anything at all
        (SteamClient.installFromDir), because the alternative is the case below - and on the device
        this path exists for, the wait for it is the whole install."""
        folder = self.local_folder()
        (folder / 'steam_client_steamdeck_publicbeta_linuxarm64').unlink()
        result = self.install('steamdeck_publicbeta', local=folder, offline=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('and the device is offline', result.stderr)
        self.assertFalse(self.requests.exists(), 'an offline install asked the network for something')
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())

    def test_without_the_manifest_the_same_folder_reaches_valve(self):
        """What the app-side check exists to prevent, and what this path did until 2026-10-16: the
        same folder one flag away from the case above quietly fetches Valve's manifest - the network,
        on the install that exists for a device without one. Nothing on screen says so: the run looks
        exactly like a folder-only install."""
        folder = self.local_folder()
        (folder / 'steam_client_steamdeck_publicbeta_linuxarm64').unlink()
        result = self.install('steamdeck_publicbeta', local=folder)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64',
        ], self.requests.read_text().splitlines())

    def test_install_from_the_local_copy_leaves_the_record_it_used(self):
        """The record and the packages in package/ have to agree: it is the manifest the install
        followed that goes in, not whichever one happens to be lying around."""
        folder = self.local_folder()
        self.assertEqual(0, self.install('steamdeck_publicbeta', local=folder).returncode)
        record = self.steam / 'package/steam_client_steamdeck_publicbeta_linuxarm64.manifest'
        self.assertEqual((folder / 'steam_client_steamdeck_publicbeta_linuxarm64').read_text(),
                         record.read_text())

    def test_a_client_installed_before_the_record_existed_gets_one(self):
        """An install made before this script wrote the record - a device updating to this build.
        Nothing is installed (the client is already in), but the record is filled in from the
        folder, and no download is made to do it: without it every start fetches Valve's manifest
        and installs again, over the network, for a client that is already unpacked here."""
        self.assert_installed('steamdeck_publicbeta')
        folder = self.local_folder()
        record = self.steam / 'package/steam_client_steamdeck_publicbeta_linuxarm64.manifest'
        record.unlink()
        self.requests.unlink()
        result = self.install('steamdeck_publicbeta', local=folder)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("wrote the client's own record", result.stdout)
        self.assertEqual((folder / 'steam_client_steamdeck_publicbeta_linuxarm64').read_text(),
                         record.read_text())
        self.assertFalse(self.requests.exists())


if __name__ == '__main__':
    unittest.main()
