// Firefox defaults for the DroidDeck desktop, staged by the app into
// /usr/lib/firefox/defaults/pref/. A user's own prefs.js still wins.

// Firefox judges "online" by dumping the routing table over netlink, which Android denies to
// apps; seeing no default route it switches itself to Work Offline and every page fails while
// the network is fine. Let it just try.
pref("network.manage-offline-status", false);
pref("browser.offline", false);
// No user namespaces on Android kernels and a ptrace'd process tree: the process sandboxes
// have nothing to set up and only add failure modes.
pref("security.sandbox.content.level", 0);
pref("media.cubeb.sandbox", false);
pref("security.sandbox.socket.process.level", 0);
// Handheld-sized chrome, and nothing phoning home on first run.
pref("browser.shell.checkDefaultBrowser", false);
pref("datareporting.policy.dataSubmissionEnabled", false);
pref("browser.aboutwelcome.enabled", false);
pref("browser.startup.homepage", "about:blank");
pref("layout.css.devPixelsPerPx", "1.25");
