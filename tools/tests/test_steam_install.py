import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
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
BASH = os.environ.get('TEST_BASH', 'bash')


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
        curl.write_text('''#!/bin/bash
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
            sha.write_text('#!/bin/bash\nexec shasum -a 256 "$@"\n')
            sha.chmod(0o755)
        if shutil.which('sha1sum') is None:
            sha1 = self.bin / 'sha1sum'
            sha1.write_text('#!/bin/bash\nexec shasum -a 1 "$@"\n')
            sha1.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), XDG_DATA_HOME=str(self.data),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TEST_CDN=str(self.cdn), TEST_REQUESTS=str(self.requests))
        self.env.pop('BL_STEAM_CHANNEL', None)
        for channel in ('publicbeta', 'steamdeck_publicbeta'):
            archive = self.cdn / (channel + '.zip')
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('steamrtarm64/steam', channel)
                package.writestr('linuxarm64/wrapper', 'wrapper')
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            (self.cdn / ('steam_client_' + channel + '_linuxarm64')).write_text(
                '"linuxarm64"\n{\n\t"version" "123456"\n'
                '\t"client_linuxarm64_linuxarm64"\n\t{\n'
                f'\t\t"file" "{archive.name}"\n\t\t"sha2" "{digest}"\n'
                '\t}\n}\n')

    def install(self, channel=None, local=None, offline=False, cdn=None):
        env = self.env.copy()
        if channel is not None:
            env['BL_STEAM_CHANNEL'] = channel
        if local is not None:
            env['BL_STEAM_LOCAL_DIR'] = str(local)
        if offline:
            env['BL_STEAM_LOCAL_ONLY'] = '1'
        if cdn is not None:
            env['BL_STEAM_CDN'] = cdn
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
        (folder / 'steam_client_steamdeck_publicbeta_linuxarm64').write_text('\n'.join(lines) + '\n')

    def assert_installed(self, channel):
        result = self.install(channel)
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

    def test_unset_channel_downloads_deck_beta(self):
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())
        self.assertIn('/steam_client_steamdeck_publicbeta_linuxarm64\n', self.requests.read_text())

    def test_explicit_deck_beta_downloads_matching_client(self):
        self.assert_installed('steamdeck_publicbeta')

    def test_explicit_public_beta_downloads_matching_client(self):
        self.assert_installed('publicbeta')

    def test_existing_install_is_left_for_steam_to_update(self):
        self.assert_installed('steamdeck_publicbeta')
        requests = self.requests.read_text()
        result = self.install('publicbeta')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(requests, self.requests.read_text())
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())

    def test_failed_download_has_no_ready_marker_and_can_retry(self):
        archive = self.cdn / 'publicbeta.zip'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupt download')
        self.assertNotEqual(0, self.install('publicbeta').returncode)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())
        archive.write_bytes(original)
        self.assertEqual(0, self.install('publicbeta').returncode)
        self.assertEqual('publicbeta\n', (self.steam / 'package/beta').read_text())

    def break_client(self, starts):
        self.assert_installed('steamdeck_publicbeta')
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
        (package / 'droiddeck-unconfirmed').write_text('start\n' * starts)
        return package

    def test_two_starts_with_nothing_shown_fetch_the_client_again(self):
        package = self.break_client(2)
        self.assert_installed('steamdeck_publicbeta')
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
        with (package / 'droiddeck-unconfirmed').open('a') as marker:
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
        self.assert_installed('steamdeck_publicbeta')
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
