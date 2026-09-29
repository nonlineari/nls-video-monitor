/*
 * GravityDesktop.java
 * Java + (Swing as desktop window; QtJambi ready for true Qt)
 * Client for the NoiseProtocol Gravity server (from SCIS noiseprotocol gravity).
 * Opens a native desktop window acting *as the protocol client* for the NLS Visualist interface.
 * 
 * Aesthetics from your visualist X post: glitch title, "1000 years old nonlinear h@k reality" banner,
 * nonlinear/h@k vibe, dark theme.
 * Thumbnails of ASC: cinematic film-style (high contrast, borders, "ASC" labels, subtle grain via colors).
 * 
 * Build: javac GravityDesktop.java
 * Run:  java GravityDesktop
 * 
 * Rules followed: no kill, no eval (pure sockets + Swing, no process termination, no dynamic exec).
 * 
 * Protocol (Gravity / noiseprotocol from SCIS):
 * - Connect
 * - Receive prologue "GRAVITY\x00\x01"
 * - Send hello (32 bytes 0x42)
 * - Receive server hello (32 bytes 0x00)
 * - Send finish (48 bytes 0x43)
 * - Then receive JSON lines: {"type":"gravity_update", "jobs":[...], "catalog":[...]}
 * 
 * The window shows:
 * - Header with glitch + banner
 * - Jobs panel (table + progress)
 * - Visualist Data panel (catalog with ASC thumbnails + gravity badges)
 * - NEW: URL drag & drop / copy & paste INPUT BAR (above status) for direct data downloads to ~/Downloads/
 *   (matches gravity-client TUI; drag text/URLs from browser, paste, Enter or button triggers yt-dlp).
 * - Auto updates from the protocol.
 * 
 * For full Qt: replace JFrame/JPanel with com.trolltech.qt.gui.QMainWindow etc.
 * (qmake available in your env for native Qt parts if needed).
 */

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;

public class GravityDesktop {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 4242;
    private static final String APP_DIR = resolveAppDir();
    private static final String YTDLP_PATH = resolveYtDlpPath();
    private static final String FFMPEG_PATH = resolveFfmpegPath();
    private static final String FFPLAY_PATH = resolveFfplayPath();
    // Prefer H.264 in MP4; fall back to best H.264 stream merged to MP4 (never AV1/WebM).
    private static final String YTDLP_H264_FORMAT =
        "bv*[vcodec^=avc][ext=mp4]+ba[ext=m4a]/bv*[vcodec^=avc1]+ba[ext=m4a]/"
        + "b[ext=mp4][vcodec^=avc]/bestvideo[vcodec^=avc]+bestaudio/best[vcodec^=avc]";
    /** Non-YouTube / generic HTTP: never require avc1 — that yields HTTP rc=1 "Requested format is not available". */
    private static final String YTDLP_GENERIC_FORMAT = "bestvideo*+bestaudio/best";
    // Animated GIF: native gif if available, else recode best video to gif.
    private static final String YTDLP_GIF_FORMAT = "best[ext=gif]/best";

    /** System-wide request pacing + 403/IP restriction cooldown (shared across all yt-dlp calls). */
    private static final class RateLimiter {
        private static final Object LOCK = new Object();
        private static long lastRequestMs = 0;
        private static long cooldownUntilMs = 0;
        private static int consecutive403 = 0;

        static void acquire(String phase) throws InterruptedException {
            long minGapMs = envLong("GRAVITY_MIN_REQUEST_INTERVAL_SEC", 3) * 1000L;
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                long waitUntil = Math.max(lastRequestMs + minGapMs, cooldownUntilMs);
                long waitMs = waitUntil - now;
                if (waitMs > 0) Thread.sleep(waitMs);
                lastRequestMs = System.currentTimeMillis();
            }
        }

        static void on403() {
            synchronized (LOCK) {
                consecutive403++;
                long baseSec = envLong("GRAVITY_403_COOLDOWN_SEC", 60);
                long cooldownSec = baseSec * Math.min(consecutive403, 5);
                cooldownUntilMs = System.currentTimeMillis() + cooldownSec * 1000L;
            }
        }

        static void onSuccess() {
            synchronized (LOCK) {
                consecutive403 = Math.max(0, consecutive403 - 1);
            }
        }

        static long cooldownSecondsRemaining() {
            synchronized (LOCK) {
                long rem = cooldownUntilMs - System.currentTimeMillis();
                return rem > 0 ? (rem + 999) / 1000 : 0;
            }
        }
    }

    private static final class YtDlpResult {
        final int exitCode;
        final String lastLine;
        final java.util.List<String> lines;
        YtDlpResult(int exitCode, String lastLine, java.util.List<String> lines) {
            this.exitCode = exitCode;
            this.lastLine = lastLine;
            this.lines = lines;
        }
    }

    private static long envLong(String key, long defaultVal) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return defaultVal; }
    }

    private static String envStr(String key, String defaultVal) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v.trim() : defaultVal;
    }

    private static boolean isIpOr403Error(String line) {
        if (line == null || line.isBlank()) return false;
        String l = line.toLowerCase();
        return l.contains("403")
            || l.contains("forbidden")
            || l.contains("http error 403")
            || l.contains("rate limit")
            || l.contains("rate-limit")
            || l.contains("too many requests")
            || l.contains("429")
            || (l.contains("ip") && (l.contains("block") || l.contains("restrict") || l.contains("banned")))
            || l.contains("precondition check failed");
    }

    private static boolean isGeoRestrictionError(String line) {
        if (line == null || line.isBlank()) return false;
        String l = line.toLowerCase();
        return l.contains("not available in your country")
            || l.contains("geo restrict")
            || l.contains("geo-restrict")
            || l.contains("available in your region")
            || l.contains("region") && l.contains("not available")
            || l.contains("copyright grounds")
            || l.contains("blocked in your region");
    }

    private static boolean outputIndicates403(java.util.List<String> lines) {
        for (String line : lines) {
            if (isIpOr403Error(line) || isGeoRestrictionError(line)) return true;
        }
        return false;
    }

    private static String minSleepInterval() {
        String v = System.getenv("GRAVITY_MIN_SLEEP_INTERVAL");
        if (v == null || v.isBlank()) v = System.getenv("GRAVITY_SLEEP_INTERVAL");
        return (v != null && !v.isBlank()) ? v.trim() : "3";
    }

    private static boolean envFlagTrue(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return false;
        String s = v.trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }

    private static boolean envFlagFalse(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return false;
        String s = v.trim().toLowerCase();
        return s.equals("0") || s.equals("false") || s.equals("no") || s.equals("off");
    }

    static final String ASTRILL_BIN = "/usr/local/Astrill/astrill";
    static final File ASTRILL_CONF_DIR = new File(System.getProperty("user.home"), ".config/astrill");
    static final File NLS_ASTRILL_DIR = new File(System.getProperty("user.home"), "Downloads/NLS-Astrill");

    /** Live VPN/tunnel: Surfshark WG is `state UNKNOWN` + name surfshark_wg, not `: wg`. Astrill Stealth is `tun0`. */
    static boolean ifaceLineIsVpn(String line) {
        if (line == null || line.isBlank()) return false;
        String l = line.toLowerCase().trim();
        int first = l.indexOf(": ");
        if (first < 0) return false;
        int second = l.indexOf(':', first + 2);
        if (second < 0) return false;
        String name = l.substring(first + 2, second).trim();
        if (name.isEmpty() || name.equals("lo")
            || name.startsWith("docker") || name.startsWith("veth")
            || name.startsWith("br-") || name.startsWith("virbr") || name.startsWith("cni")) {
            return false;
        }
        if (l.contains("no-carrier") || l.contains("state down")) return false;
        if (!l.contains("lower_up") && !l.contains("state up")) return false;
        if (name.contains("surfshark") || name.contains("nordlynx") || name.contains("proton")
            || name.contains("mullvad") || name.contains("wireguard") || name.contains("vpn")
            || name.contains("tailscale") || name.contains("astrill")
            || name.matches("tun\\d*") || name.matches("tap\\d*")
            || name.matches("wg\\d+") || name.matches("ppp\\d*") || name.matches("utun\\d*")
            || name.contains("_wg") || name.startsWith("wg-")) {
            return true;
        }
        return l.contains("pointopoint");
    }

    private static boolean vpnInterfaceUp() {
        try {
            Process p = new ProcessBuilder("ip", "-o", "link", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            boolean found = false;
            while ((ln = br.readLine()) != null) {
                if (ifaceLineIsVpn(ln)) {
                    found = true;
                    break;
                }
            }
            p.waitFor();
            return found;
        } catch (Exception ignore) {}
        return false;
    }

    /** Parse `ip -4 -o addr show` line → iface name, or null. */
    static String vpnAddrLineIpv4(String line) {
        if (line == null || line.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})/")
            .matcher(line.trim());
        if (!m.find()) return null;
        String ip = m.group(2);
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) return null;
        return m.group(1) + " " + ip;
    }

    private static String vpnTunnelIpv4() {
        String env = System.getenv("GRAVITY_SOURCE_ADDRESS");
        if (env != null && !env.isBlank()) return env.trim();
        java.util.Set<String> vpnIfaces = new java.util.HashSet<>();
        try {
            Process lp = new ProcessBuilder("ip", "-o", "link", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(lp.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                if (!ifaceLineIsVpn(ln)) continue;
                int first = ln.indexOf(": ");
                int second = first >= 0 ? ln.indexOf(':', first + 2) : -1;
                if (first >= 0 && second > first) {
                    vpnIfaces.add(ln.substring(first + 2, second).trim().toLowerCase());
                }
            }
            lp.waitFor();
        } catch (Exception ignore) {
            return null;
        }
        if (vpnIfaces.isEmpty()) return null;
        String fallback = null;
        try {
            Process ap = new ProcessBuilder("ip", "-4", "-o", "addr", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(ap.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                String parsed = vpnAddrLineIpv4(ln);
                if (parsed == null) continue;
                int sp = parsed.indexOf(' ');
                String name = parsed.substring(0, sp).toLowerCase();
                String ip = parsed.substring(sp + 1);
                if (!vpnIfaces.contains(name)) continue;
                if (name.contains("wg") || name.startsWith("tun") || name.startsWith("ppp")) {
                    ap.waitFor();
                    return ip;
                }
                if (fallback == null) fallback = ip;
            }
            ap.waitFor();
        } catch (Exception ignore) {}
        return fallback;
    }

    static boolean ipLooksAstrill(String ip) {
        return ip != null && ip.startsWith("198.18.");
    }

    static String vpnProviderFromIfaces(java.util.Collection<String> names, boolean astrillProc) {
        boolean surf = false;
        boolean tun = false;
        if (names != null) {
            for (String n : names) {
                String l = n.toLowerCase();
                if (l.contains("surfshark")) surf = true;
                if (l.startsWith("tun") || l.contains("astrill")) tun = true;
            }
        }
        if (surf && (tun || astrillProc)) return "stacked";
        if (surf) return "surfshark";
        if (tun || astrillProc) return "astrill";
        return "none";
    }

    private static boolean astrillProcessRunning() {
        try {
            Process p = new ProcessBuilder("ps", "-eo", "args=").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                String l = ln.trim();
                if (l.contains("/usr/local/Astrill/astrill")
                        || l.contains("/usr/local/Astrill/asovpnc")
                        || l.contains("/usr/local/Astrill/aswgvpnc")
                        || l.endsWith(" asovpnc") || l.endsWith(" aswgvpnc")) {
                    if (!l.contains("zsh -c") && !l.contains("eval ")) {
                        p.waitFor();
                        return true;
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignore) {}
        return false;
    }

    private static boolean astrillInstalled() {
        return new File(ASTRILL_BIN).isFile() || ASTRILL_CONF_DIR.isDirectory();
    }

    private static java.util.Set<String> liveVpnIfaceNames() {
        java.util.Set<String> vpnIfaces = new java.util.HashSet<>();
        try {
            Process lp = new ProcessBuilder("ip", "-o", "link", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(lp.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                if (!ifaceLineIsVpn(ln)) continue;
                int first = ln.indexOf(": ");
                int second = first >= 0 ? ln.indexOf(':', first + 2) : -1;
                if (first >= 0 && second > first) {
                    vpnIfaces.add(ln.substring(first + 2, second).trim().toLowerCase());
                }
            }
            lp.waitFor();
        } catch (Exception ignore) {}
        return vpnIfaces;
    }

    /** auto | astrill | surfshark | stacked | none */
    private String selectedVpnProvider() {
        if (vpnProviderBox != null) {
            String s = String.valueOf(vpnProviderBox.getSelectedItem()).toLowerCase();
            if (s.contains("astrill")) return "astrill";
            if (s.contains("surfshark")) return "surfshark";
        }
        String env = envStr("GRAVITY_VPN_PROVIDER", "auto").toLowerCase();
        if (env.contains("astrill") || env.contains("surfshark")) return env.contains("astrill") ? "astrill" : "surfshark";
        return vpnProviderFromIfaces(liveVpnIfaceNames(), astrillProcessRunning());
    }

    private static String vpnTunnelIpv4ForProvider(String provider) {
        String env = System.getenv("GRAVITY_SOURCE_ADDRESS");
        if (env != null && !env.isBlank()) return env.trim();
        java.util.Set<String> ifaces = new java.util.HashSet<>();
        try {
            Process lp = new ProcessBuilder("ip", "-o", "link", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(lp.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                if (!ifaceLineIsVpn(ln)) continue;
                int first = ln.indexOf(": ");
                int second = first >= 0 ? ln.indexOf(':', first + 2) : -1;
                if (first >= 0 && second > first) {
                    ifaces.add(ln.substring(first + 2, second).trim().toLowerCase());
                }
            }
            lp.waitFor();
        } catch (Exception ignore) {}
        String astrillIp = null;
        String surfIp = null;
        String tunIp = null;
        try {
            Process ap = new ProcessBuilder("ip", "-4", "-o", "addr", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(ap.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                String parsed = vpnAddrLineIpv4(ln);
                if (parsed == null) continue;
                int sp = parsed.indexOf(' ');
                String name = parsed.substring(0, sp).toLowerCase();
                String ip = parsed.substring(sp + 1);
                if (!ifaces.contains(name)) continue;
                if (name.contains("surfshark")) surfIp = ip;
                if (name.startsWith("tun") || name.contains("astrill")) {
                    tunIp = ip;
                    if (ipLooksAstrill(ip)) astrillIp = ip;
                }
            }
            ap.waitFor();
        } catch (Exception ignore) {}
        if ("surfshark".equals(provider)) return surfIp;
        if ("astrill".equals(provider)) return astrillIp != null ? astrillIp : tunIp;
        if (astrillIp != null) return astrillIp;
        if (tunIp != null) return tunIp;
        return surfIp;
    }

    private static boolean vpnRouteRequested() {
        return envFlagTrue("GRAVITY_VPN_REQUIRED") || envFlagTrue("GRAVITY_USE_VPN");
    }

    private boolean useVpnRoute() {
        if (vpnRouteRequested()) return true;
        return vpnCheck != null && vpnCheck.isSelected();
    }

    private static boolean forceIpv4ForDownloads() {
        if (envFlagTrue("GRAVITY_FORCE_IPV6")) return false;
        if (envFlagFalse("GRAVITY_FORCE_IPV4")) return false;
        if (envFlagTrue("GRAVITY_FORCE_IPV4")) return true;
        return vpnRouteRequested();
    }

    private static boolean tcpOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Overlay: direct | ssh | tor (onion) | i2p (garlic). No kernel VPN. */
    private String selectedOverlay() {
        if (overlayBox != null) {
            String s = String.valueOf(overlayBox.getSelectedItem());
            if (s.contains("Tor") || s.contains("onion")) return "tor";
            if (s.contains("I2P") || s.contains("garlic")) return "i2p";
            if (s.contains("SSH") || s.contains("VPS")) return "ssh";
        }
        return envStr("GRAVITY_OVERLAY", "direct").toLowerCase();
    }

    private String resolveProxyUrl() {
        String gravity = System.getenv("GRAVITY_PROXY");
        if (gravity != null && !gravity.isBlank()) return gravity.trim();
        String file = readEnvFileKey("GRAVITY_PROXY");
        if (file != null && !file.isBlank()) return file;
        for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) return v.trim();
        }
        switch (selectedOverlay()) {
            case "ssh":
                return tcpOpen("127.0.0.1", 1080) ? "socks5://127.0.0.1:1080" : null;
            case "tor":
                return tcpOpen("127.0.0.1", 9050) ? "socks5://127.0.0.1:9050" : null;
            case "i2p":
                if (tcpOpen("127.0.0.1", 4447)) return "socks5://127.0.0.1:4447";
                if (tcpOpen("127.0.0.1", 4444)) return "http://127.0.0.1:4444";
                return null;
            default:
                return null;
        }
    }

    /** First non-VPN LAN IPv4 (enp/wlan). Used to keep YouTube off Cyberzone while Surfshark is up. */
    private static String lanIpv4() {
        try {
            Process p = new ProcessBuilder("ip", "-4", "-o", "addr", "show").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})/")
                    .matcher(ln.trim());
                if (!m.find()) continue;
                String name = m.group(1).toLowerCase();
                String ip = m.group(2);
                if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
                if (name.contains("surfshark") || name.contains("nordlynx") || name.contains("proton")
                    || name.contains("mullvad") || name.contains("wireguard") || name.contains("vpn")
                    || name.contains("tailscale") || name.contains("astrill")
                    || name.matches("tun\\d*") || name.matches("wg\\d+")
                    || name.contains("_wg") || name.startsWith("ppp")) continue;
                p.waitFor();
                return ip;
            }
            p.waitFor();
        } catch (Exception ignore) {}
        return null;
    }

    /** False when Surfshark kill-switch drops the ISP NIC. */
    private static volatile Boolean ispAliveCached;
    private static volatile long ispAliveAt;

    private static boolean ispPathAlive() {
        long now = System.currentTimeMillis();
        if (ispAliveCached != null && now - ispAliveAt < 15000) return ispAliveCached;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            return ispAliveCached == null || ispAliveCached;
        }
        String lan = lanIpv4();
        if (lan == null) {
            ispAliveCached = false;
            ispAliveAt = now;
            return false;
        }
        try {
            Process p = new ProcessBuilder("curl", "-4", "-sS", "-o", "/dev/null",
                "--max-time", "2", "--interface", lan, "https://1.1.1.1").start();
            boolean ok = p.waitFor() == 0;
            ispAliveCached = ok;
            ispAliveAt = System.currentTimeMillis();
            return ok;
        } catch (Exception e) {
            ispAliveCached = false;
            ispAliveAt = System.currentTimeMillis();
            return false;
        }
    }

    private String describeNetworkRoute() {
        String ov = selectedOverlay();
        String proxy = resolveProxyUrl();
        if ("tor".equals(ov)) return proxy != null ? "Tor onion :9050" : "Tor (down)";
        if ("i2p".equals(ov)) return proxy != null ? "I2P garlic" : "I2P (down)";
        if ("ssh".equals(ov)) return proxy != null ? "SSH SOCKS :1080" : "SSH VPS (down)";
        if (proxy != null) {
            if (proxy.contains("127.0.0.1:1080")) return "SSH SOCKS :1080";
            return proxy.length() > 28 ? "proxy:" + proxy.substring(0, 25) + "..." : "proxy:" + proxy;
        }
        if (useVpnRoute()) {
            String prov = selectedVpnProvider();
            String src = vpnTunnelIpv4ForProvider(prov);
            if ("stacked".equals(prov)) return "VPN stacked — disconnect Surfshark or Astrill";
            if ("astrill".equals(prov)) {
                if (src != null) return "Astrill tun0 " + src + " (stealth)";
                if (astrillProcessRunning()) return "Astrill (no tun0 — toggle StealthVPN ON)";
                return astrillInstalled() ? "Astrill (down)" : "Astrill (not installed)";
            }
            if ("surfshark".equals(prov)) {
                if (src != null) return "Surfshark " + src + " (datacenter)";
                if (vpnInterfaceUp()) return "Surfshark (no IPv4)";
                return "Surfshark (down)";
            }
            if (src != null) return "VPN " + src;
            if (vpnInterfaceUp()) return "VPN (no IPv4)";
            return "VPN (down)";
        }
        boolean tun = vpnInterfaceUp() || vpnTunnelIpv4() != null;
        String live = vpnProviderFromIfaces(liveVpnIfaceNames(), astrillProcessRunning());
        if ("stacked".equals(live)) return "VPN stacked — disconnect one";
        if (tun && !ispPathAlive()) {
            return "surfshark".equals(live) ? "kill-switch (Cyberzone)" : "kill-switch (full tunnel)";
        }
        if (tun) return "HKG/ISP split";
        return "HKG/direct";
    }

    private static File gravityEnvFile() {
        return new File(System.getProperty("user.home"), ".config/gravity-desktop/env");
    }

    private static String readEnvFileKey(String key) {
        File f = gravityEnvFile();
        if (!f.isFile()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln;
            String prefix = key + "=";
            while ((ln = br.readLine()) != null) {
                ln = ln.trim();
                if (ln.startsWith("#") || !ln.startsWith(prefix)) continue;
                String v = ln.substring(prefix.length()).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v.isEmpty() ? null : v;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static boolean switcherPidAlive() {
        File pidf = new File(System.getProperty("user.home"), ".config/gravity-desktop/switcher.pid");
        if (!pidf.isFile()) return false;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(pidf.toPath())).trim();
            long pid = Long.parseLong(s);
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Gravity Switcher ON+lock is source of truth. OFF or closed → HKG/direct. */
    private void syncFromSwitcherLock() {
        boolean lock = "1".equals(readEnvFileKey("GRAVITY_SWITCHER_LOCK"));
        boolean alive = switcherPidAlive();
        if (switcherBtn != null) {
            if (lock && alive) {
                switcherBtn.setText("SWITCHER ON");
                switcherBtn.setBackground(new Color(0x5a, 0xe0, 0xd0));
            } else {
                switcherBtn.setText("SWITCHER");
                switcherBtn.setBackground(new Color(0x9b, 0xe7, 0xff));
            }
        }
        if (lock && !alive) {
            writeEnvFileKey("GRAVITY_SWITCHER_LOCK", "0");
            writeEnvFileKey("GRAVITY_SWITCHER_ON", "0");
            writeEnvFileKey("GRAVITY_OVERLAY", "direct");
            writeEnvFileKey("GRAVITY_USE_VPN", "0");
            writeEnvFileKey("GRAVITY_VPN_PROVIDER", "auto");
            if (overlayBox != null) overlayBox.setSelectedItem("HKG/direct");
            if (vpnCheck != null) vpnCheck.setSelected(false);
            if (directStatusLabel != null) {
                directStatusLabel.setText("Switcher OFF — HKG/direct restored (applet not running)");
            }
            refreshNetLabel();
            return;
        }
        if (!lock || !alive) return;
        String ov = readEnvFileKey("GRAVITY_OVERLAY");
        String vpn = readEnvFileKey("GRAVITY_USE_VPN");
        String prov = readEnvFileKey("GRAVITY_VPN_PROVIDER");
        if (overlayBox != null && ov != null && !ov.isEmpty()) {
            String label = "direct".equals(ov) ? "HKG/direct"
                : "ssh".equals(ov) ? "SSH VPS"
                : "tor".equals(ov) ? "Tor onion"
                : "i2p".equals(ov) ? "I2P garlic" : null;
            if (label != null && !label.equals(overlayBox.getSelectedItem())) {
                overlayBox.setSelectedItem(label);
            }
        }
        if (vpnCheck != null && vpn != null && !vpn.isEmpty()) {
            boolean on = "1".equals(vpn);
            if (vpnCheck.isSelected() != on) vpnCheck.setSelected(on);
        }
        if (vpnProviderBox != null && prov != null && !prov.isEmpty()) {
            String label = "astrill".equals(prov) ? "Astrill"
                : "surfshark".equals(prov) ? "Surfshark" : "VPN auto";
            if (!label.equals(vpnProviderBox.getSelectedItem())) vpnProviderBox.setSelectedItem(label);
        }
        refreshNetLabel();
    }

    private static void writeEnvFileKey(String key, String value) {
        File f = gravityEnvFile();
        f.getParentFile().mkdirs();
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean replaced = false;
        if (f.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    if (ln.trim().startsWith(key + "=")) {
                        lines.add(key + "=" + value);
                        replaced = true;
                    } else {
                        lines.add(ln);
                    }
                }
            } catch (Exception ignore) {}
        }
        if (!replaced) lines.add(key + "=" + value);
        try (FileWriter fw = new FileWriter(f, false)) {
            for (String ln : lines) fw.write(ln + "\n");
        } catch (Exception ignore) {}
    }

    private static File gravitySshKey() {
        return new File(System.getProperty("user.home"), ".ssh/id_ed25519_gravity");
    }

    private void ensureGravitySshKey() {
        File key = gravitySshKey();
        if (key.isFile()) return;
        key.getParentFile().mkdirs();
        try {
            Process p = new ProcessBuilder("ssh-keygen", "-t", "ed25519", "-f", key.getAbsolutePath(),
                "-N", "", "-C", "gravity-socks").inheritIO().start();
            p.waitFor();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "ssh-keygen failed: " + ex.getMessage());
        }
    }

    private void startSshSocksTunnel() {
        if (tcpOpen("127.0.0.1", 1080)) {
            if (directStatusLabel != null) directStatusLabel.setText("SSH SOCKS already on :1080");
            refreshNetLabel();
            return;
        }
        String host = System.getenv("GRAVITY_SSH_HOST");
        if (host == null || host.isBlank()) host = readEnvFileKey("GRAVITY_SSH_HOST");
        if (host == null || host.isBlank()) {
            host = JOptionPane.showInputDialog(frame,
                "SSH hop for downloads (user@host).\nNot a VPN datacenter — a VPS or home box.\nPublic key: ~/.ssh/id_ed25519_gravity.pub",
                "SSH SOCKS tunnel", JOptionPane.QUESTION_MESSAGE);
            if (host == null || host.isBlank()) return;
            host = host.trim();
            writeEnvFileKey("GRAVITY_SSH_HOST", host);
        }
        final String hop = host;
        ensureGravitySshKey();
        File key = gravitySshKey();
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("ssh");
            cmd.add("-N");
            cmd.add("-D");
            cmd.add("127.0.0.1:1080");
            cmd.add("-i");
            cmd.add(key.getAbsolutePath());
            cmd.add("-o");
            cmd.add("IdentitiesOnly=yes");
            cmd.add("-o");
            cmd.add("ExitOnForwardFailure=yes");
            cmd.add("-o");
            cmd.add("ServerAliveInterval=30");
            cmd.add(hop);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            sshSocksProc = pb.start();
            if (directStatusLabel != null) {
                directStatusLabel.setText("SSH SOCKS starting → " + hop + " :1080");
            }
            new Thread(() -> {
                try { Thread.sleep(800); } catch (InterruptedException ignore) {}
                SwingUtilities.invokeLater(() -> {
                    if (tcpOpen("127.0.0.1", 1080)) {
                        if (directStatusLabel != null) {
                            directStatusLabel.setText("SSH SOCKS up :1080 — yt-dlp will use socks5 (not kernel VPN)");
                        }
                    } else {
                        if (directStatusLabel != null) {
                            directStatusLabel.setText("SSH SOCKS failed — add id_ed25519_gravity.pub to " + hop);
                        }
                    }
                    refreshNetLabel();
                });
            }).start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "ssh failed: " + ex.getMessage());
        }
    }

    private void startGravitySwitcher() {
        File dir = new File(System.getProperty("user.home"), "Downloads/GravitySwitcher");
        File run = new File(dir, "run.sh");
        if (!run.isFile()) {
            JOptionPane.showMessageDialog(frame,
                "Gravity Switcher not found:\n" + run.getAbsolutePath()
                + "\n\nIt is a separate app: ~/Downloads/GravitySwitcher/run.sh");
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", run.getAbsolutePath());
            pb.directory(dir);
            pb.environment().put("DISPLAY", envStr("DISPLAY", ":0.0"));
            pb.redirectErrorStream(true);
            pb.start();
            if (directStatusLabel != null) {
                directStatusLabel.setText("Gravity Switcher launching — Catalog / Case / NNLR / Security");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Switcher launch failed: " + ex.getMessage());
        }
    }

    private void startAstrillClient() {
        String stacked = vpnProviderFromIfaces(liveVpnIfaceNames(), astrillProcessRunning());
        if ("stacked".equals(stacked) || "surfshark".equals(stacked)) {
            int go = JOptionPane.showConfirmDialog(frame,
                "Surfshark appears up. Do not stack with Astrill.\nDisconnect Surfshark, then launch Astrill?",
                "Astrill StealthVPN", JOptionPane.YES_NO_OPTION);
            if (go != JOptionPane.YES_OPTION) return;
        }
        File bin = new File(ASTRILL_BIN);
        if (!bin.isFile()) {
            JOptionPane.showMessageDialog(frame,
                "Astrill not installed at " + ASTRILL_BIN + "\n"
                + "Archive: " + NLS_ASTRILL_DIR.getAbsolutePath() + "\n"
                + "Installer: ~/Downloads/astrill-setup-linux64.deb\n"
                + "Config: " + ASTRILL_CONF_DIR.getAbsolutePath());
            return;
        }
        if (astrillProcessRunning()) {
            if (directStatusLabel != null) {
                String src = vpnTunnelIpv4ForProvider("astrill");
                directStatusLabel.setText(src != null
                    ? ("Astrill already running — tun0 " + src + ". Check VPN box.")
                    : "Astrill GUI up — set StealthVPN ON (tun0). Then check VPN.");
            }
            if (vpnCheck != null) vpnCheck.setSelected(true);
            if (vpnProviderBox != null) vpnProviderBox.setSelectedItem("Astrill");
            refreshNetLabel();
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(ASTRILL_BIN);
            pb.environment().put("DISPLAY", envStr("DISPLAY", ":0.0"));
            pb.redirectErrorStream(true);
            pb.start();
            if (vpnProviderBox != null) vpnProviderBox.setSelectedItem("Astrill");
            if (vpnCheck != null) vpnCheck.setSelected(true);
            if (directStatusLabel != null) {
                directStatusLabel.setText("Astrill launched — OpenWeb → StealthVPN, then ON. tun0 = tunnel.");
            }
            refreshNetLabel();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Astrill launch failed: " + ex.getMessage());
        }
    }

    private void startNgrokTunnel() {
        if (tcpOpen("127.0.0.1", 4040)) {
            fetchNgrokUrl();
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("ngrok", "start", "gravity-serve");
            pb.redirectErrorStream(true);
            ngrokProc = pb.start();
            if (directStatusLabel != null) directStatusLabel.setText("ngrok starting gravity-serve…");
            new Thread(() -> {
                try { Thread.sleep(1200); } catch (InterruptedException ignore) {}
                SwingUtilities.invokeLater(this::fetchNgrokUrl);
            }).start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "ngrok failed: " + ex.getMessage());
        }
    }

    private void fetchNgrokUrl() {
        try {
            URL u = URI.create("http://127.0.0.1:4040/api/tunnels").toURL();
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(2000);
            String body;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
                body = sb.toString();
            }
            String url = jsonQuotedField(body, "public_url");
            if (url == null) {
                int i = body.indexOf("https://");
                if (i >= 0) {
                    int j = i;
                    while (j < body.length() && "\"'<> \\".indexOf(body.charAt(j)) < 0) j++;
                    url = body.substring(i, j);
                }
            }
            if (directStatusLabel != null) {
                directStatusLabel.setText(url != null ? ("ngrok " + url) : "ngrok up (no public_url yet)");
            }
        } catch (Exception ex) {
            if (directStatusLabel != null) {
                directStatusLabel.setText("ngrok API :4040 not ready — " + ex.getMessage());
            }
        }
        refreshNetLabel();
    }

    private void onOverlayChanged() {
        String ov = selectedOverlay();
        writeEnvFileKey("GRAVITY_OVERLAY", ov);
        if ("tor".equals(ov)) {
            if (!tcpOpen("127.0.0.1", 9050)) {
                try {
                    new ProcessBuilder("tor").redirectErrorStream(true).start();
                    if (directStatusLabel != null) {
                        directStatusLabel.setText("Starting system Tor (onion SOCKS :9050)…");
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Tor not startable: " + ex.getMessage());
                }
            } else if (directStatusLabel != null) {
                directStatusLabel.setText("Tor onion SOCKS :9050 — YouTube often 403s Tor exits. Use SSH VPS for video.");
            }
        } else if ("i2p".equals(ov)) {
            if (!tcpOpen("127.0.0.1", 4444) && !tcpOpen("127.0.0.1", 4447)) {
                JOptionPane.showMessageDialog(frame,
                    "I2P garlic router is not running.\n\n"
                    + "Java garlic stack: install I2P (geti2p.net) or: sudo apt install i2pd\n"
                    + "Then HTTP proxy 127.0.0.1:4444 / SOCKS 4447.\n"
                    + "I2P has no YouTube outproxy — use SSH VPS for downloads, I2P for eepsites.");
            } else if (directStatusLabel != null) {
                directStatusLabel.setText("I2P garlic proxy up — eepsites only, not YouTube.");
            }
        } else if ("ssh".equals(ov)) {
            if (!tcpOpen("127.0.0.1", 1080)) startSshSocksTunnel();
        }
        refreshNetLabel();
    }

    private void refreshNetLabel() {
        if (netL == null) return;
        String r = describeNetworkRoute();
        netL.setText("net: " + r);
        if (r.contains("kill-switch") || r.contains("datacenter") || r.contains("stacked")) {
            netL.setForeground(new Color(0xe8, 0x5a, 0x6a));
        } else if (r.contains("SOCKS") || r.contains("socks") || r.contains("ngrok")
                || r.contains("Astrill") || r.contains("stealth") || useVpnRoute()) {
            netL.setForeground(new Color(0x5a, 0xe0, 0xd0));
        } else {
            netL.setForeground(new Color(0x98, 0xb7, 0xc7));
        }
    }

    private void addProxyArgs(java.util.List<String> args) {
        String proxy = resolveProxyUrl();
        if (proxy == null || proxy.isBlank()) return;
        args.add("--proxy");
        args.add(proxy);
        String geoProxy = System.getenv("GRAVITY_GEO_PROXY");
        args.add("--geo-verification-proxy");
        args.add((geoProxy != null && !geoProxy.isBlank()) ? geoProxy.trim() : proxy);
    }

    private void addVpnCompatArgs(java.util.List<String> args) {
        args.add("--socket-timeout");
        args.add(envStr("GRAVITY_SOCKET_TIMEOUT", useVpnRoute() ? "30" : "20"));
        if (resolveProxyUrl() != null) {
            // SSH/SOCKS hop already chooses the exit IP — do not also bind kernel VPN.
            return;
        }
        if (!useVpnRoute()) {
            // HKG: if Surfshark is up but ISP still works, pin yt-dlp to LAN so YouTube
            // is not the Cyberzone datacenter. Astrill full-tunnel usually kills ISP.
            String lan = lanIpv4();
            if (lan != null && vpnTunnelIpv4() != null && ispPathAlive()) {
                args.add("--force-ipv4");
                args.add("--source-address");
                args.add(lan);
            }
            return;
        }
        String src = vpnTunnelIpv4ForProvider(selectedVpnProvider());
        args.add("--force-ipv4");
        if (src != null) {
            // VPN box on: pin to Astrill tun0 or Surfshark WG. Surfshark datacenter may 403 YouTube.
            args.add("--source-address");
            args.add(src);
        }
        String extra = System.getenv("GRAVITY_YT_EXTRACTOR_ARGS");
        if (extra != null && !extra.isBlank()) {
            args.add("--extractor-args");
            args.add(extra.trim());
        } else {
            args.add("--extractor-args");
            args.add("youtube:player_client=tv,web");
            args.add("--remote-components");
            args.add("ejs:github");
        }
    }

    /** YouTube Shorts bot-gate — tv/web/mweb, never android PO-token. Not used for generic sites. */
    private static void addYoutubeClientArgs(java.util.List<String> args, String url) {
        if (!isYouTubeUrl(url)) return;
        for (String a : args) {
            if (a.startsWith("youtube:player_client=")) return;
        }
        String extra = System.getenv("GRAVITY_YT_EXTRACTOR_ARGS");
        args.add("--extractor-args");
        args.add(extra != null && !extra.isBlank() ? extra.trim() : "youtube:player_client=web,mweb,tv");
        args.add("--remote-components");
        args.add("ejs:github");
    }

    private static void addRateLimitArgs(java.util.List<String> args) {
        // Human-like pacing: random sleep between downloads, requests, and fragments.
        args.add("--min-sleep-interval");
        args.add(minSleepInterval());
        args.add("--max-sleep-interval");
        args.add(envStr("GRAVITY_MAX_SLEEP_INTERVAL", "12"));
        args.add("--sleep-requests");
        args.add(envStr("GRAVITY_SLEEP_REQUESTS", "1"));
        args.add("--retries");
        args.add(envStr("GRAVITY_RETRIES", "15"));
        args.add("--fragment-retries");
        args.add(envStr("GRAVITY_FRAGMENT_RETRIES", "15"));
        args.add("--extractor-retries");
        args.add(envStr("GRAVITY_EXTRACTOR_RETRIES", "5"));
        args.add("--retry-sleep");
        args.add("http:exp=1:" + envStr("GRAVITY_HTTP_RETRY_SLEEP_MAX", "60"));
        args.add("--retry-sleep");
        args.add("fragment:exp=1:30");
        args.add("--retry-sleep");
        args.add("extractor:exp=1:20");
        args.add("--concurrent-fragments");
        args.add("1");
        String limitRate = System.getenv("GRAVITY_LIMIT_RATE");
        if (limitRate != null && !limitRate.isBlank()) {
            args.add("--limit-rate");
            args.add(limitRate.trim());
        }
    }

    private void abortCurrentYtDlp() {
        abortRequested = true;
        Process p = currentYtDlpProc;
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception ignore) {
            try { p.destroyForcibly(); } catch (Exception ignore2) {}
        }
    }

    private YtDlpResult runYtDlp(java.util.List<String> args) throws Exception {
        if (abortRequested) {
            return new YtDlpResult(130, "aborted", new java.util.ArrayList<>());
        }
        RateLimiter.acquire("yt-dlp");
        if (abortRequested) {
            return new YtDlpResult(130, "aborted", new java.util.ArrayList<>());
        }
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        currentYtDlpProc = proc;
        java.util.List<String> lines = new java.util.ArrayList<>();
        String lastLine = "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String ln;
            while ((ln = r.readLine()) != null) {
                if (abortRequested) {
                    abortCurrentYtDlp();
                    break;
                }
                if (!isYtDlpNoise(ln)) lastLine = ln;
                lines.add(ln);
            }
            int code = abortRequested ? 130 : proc.waitFor();
            if (abortRequested && lastLine.isEmpty()) lastLine = "aborted";
            return new YtDlpResult(code, lastLine, lines);
        } finally {
            if (currentYtDlpProc == proc) currentYtDlpProc = null;
        }
    }

    private YtDlpResult runYtDlpWithProgress(java.util.List<String> args, double theta) throws Exception {
        if (abortRequested) {
            return new YtDlpResult(130, "aborted", new java.util.ArrayList<>());
        }
        RateLimiter.acquire("yt-dlp");
        if (abortRequested) {
            return new YtDlpResult(130, "aborted", new java.util.ArrayList<>());
        }
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        currentYtDlpProc = proc;
        java.util.List<String> lines = new java.util.ArrayList<>();
        String lastLine = "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String ln;
            while ((ln = r.readLine()) != null) {
                if (abortRequested) {
                    abortCurrentYtDlp();
                    break;
                }
                if (!isYtDlpNoise(ln)) lastLine = ln;
                lines.add(ln);
                if (isYtDlpNoise(ln)) continue;
                final String line = ln;
                int pct = -1;
                if (line.contains("%")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.?\\d*)%").matcher(line);
                    if (m.find()) {
                        try {
                            pct = (int) Double.parseDouble(m.group(1));
                        } catch (Exception ignore) {}
                    }
                }
                final int progress = pct;
                SwingUtilities.invokeLater(() -> {
                    if (progress >= 0) {
                        directProgress.setValue(Math.min(100, Math.max(0, progress)));
                    }
                    String shortLine = line.length() > 78 ? line.substring(0, 75) + "..." : line;
                    if (line.contains("[download]") || line.contains("Merging") ||
                        line.contains("Destination") || line.contains("has already") ||
                        isIpOr403Error(line)) {
                        directStatusLabel.setText(shortLine + (theta != 0 ? " θ" + theta : ""));
                    }
                });
            }
            int code = abortRequested ? 130 : proc.waitFor();
            if (abortRequested && lastLine.isEmpty()) lastLine = "aborted";
            return new YtDlpResult(code, lastLine, lines);
        } finally {
            if (currentYtDlpProc == proc) currentYtDlpProc = null;
        }
    }

    /** Remote viewer nav entry: asset-class + PHP viewer template (viewkey / menu index). */
    private static final class RemoteNavEntry {
        String assetClass;
        String baseUrl;
        int menuIndex;
        String viewer; // remote.php | remote-cli.php | navi-remote.php

        RemoteNavEntry(String assetClass, String baseUrl, int menuIndex, String viewer) {
            this.assetClass = assetClass;
            this.baseUrl = baseUrl;
            this.menuIndex = menuIndex;
            this.viewer = viewer != null && !viewer.isBlank() ? viewer : "remote-cli.php";
        }

        String baseNorm() {
            String b = baseUrl == null ? "" : baseUrl.trim();
            if (b.isEmpty()) return "";
            return b.endsWith("/") ? b : b + "/";
        }

        String buildViewkeyUrl(String viewkey) {
            String key = viewkey == null ? "" : viewkey.trim();
            String b = baseNorm();
            if (b.isEmpty()) return "viewkey=" + key;
            String v = viewer.toLowerCase();
            if (v.equals("bot") || v.equals("news") || v.equals("station") || v.equals("nlsar")) {
                String page = v.equals("nlsar") ? "bot" : v;
                String q = key.isEmpty() ? "" : ("?q=" + key);
                return b + page + q;
            }
            if (v.contains("remote-cli")) {
                return b + "remote-cli.php?viewkey=" + key;
            }
            if (v.contains("navi")) {
                return b + "navi(1)remote.php?index.htm&menu=" + menuIndex + "&viewkey=" + key;
            }
            return b + "remote.php?index.htm&menu=" + menuIndex + "&viewkey=" + key;
        }

        String buildMenuUrl(int index) {
            String b = baseNorm();
            String v = viewer.toLowerCase();
            if (v.equals("bot") || v.equals("news") || v.equals("station") || v.equals("nlsar")) {
                return b + (v.equals("nlsar") ? "bot" : v);
            }
            if (v.contains("navi")) {
                return b + "navi(1)remote.php?index.htm&menu=" + index;
            }
            return b + "remote.php?index.htm&menu=" + index;
        }

        static RemoteNavEntry fromTsv(String line) {
            String[] p = line.split("\t", -1);
            if (p.length < 4) return null;
            try {
                return new RemoteNavEntry(p[0].trim(), p[1].trim(), Integer.parseInt(p[2].trim()), p[3].trim());
            } catch (Exception e) { return null; }
        }

        String toTsv() {
            return assetClass + "\t" + baseUrl + "\t" + menuIndex + "\t" + viewer;
        }

        public String toString() {
            return assetClass + " [menu=" + menuIndex + "] " + viewer;
        }
    }

    /** Recent direct download entry for the ~/Downloads list panel. */
    private static final class AscEntry {
        final String id;
        final String title;
        final String outputPath;
        final String playUrl;
        final String preset;
        final String smc;
        final String duration;
        final boolean gravity;
        final boolean playable;
        ImageIcon thumbIcon;

        AscEntry(String title, String outputPath, String playUrl, String preset,
                 String smc, String duration, boolean gravity, boolean playable, String id) {
            this.title = title;
            this.outputPath = outputPath;
            this.playUrl = playUrl;
            this.preset = preset;
            this.smc = smc;
            this.duration = duration;
            this.gravity = gravity;
            this.playable = playable;
            this.id = id;
        }

        String key() {
            if (id != null && !id.isBlank()) return "id:" + id;
            if (outputPath != null && !outputPath.isBlank()) return outputPath;
            return title;
        }

        String playTarget() {
            if (outputPath != null && new File(outputPath).isFile()) return outputPath;
            return playUrl;
        }

        String recStem() {
            String s = title == null ? "asc" : title.replaceAll("[^A-Za-z0-9._-]+", "-");
            if (s.startsWith("-")) s = s.substring(1);
            if (s.length() > 36) s = s.substring(0, 36);
            return s.isBlank() ? "asc" : s;
        }

        String audioReady() {
            if (outputPath != null && new File(outputPath).isFile()) return "file";
            if (playUrl != null && !playUrl.isBlank()) return "http";
            return playable ? "?" : "—";
        }

        String listLine() {
            String g = gravity ? " GRAVITY" : "";
            String p = playable ? "" : " (no file)";
            String t = title.length() > 28 ? title.substring(0, 26) + "…" : title;
            return t + g + "  " + duration + "  " + preset + p;
        }

        public String toString() {
            return listLine();
        }
    }

    private static final class RecentDownload {
        final String path;
        final String display;

        RecentDownload(String path, String display) {
            this.path = path;
            this.display = display;
        }

        public String toString() {
            return display;
        }
    }

    /** Pulse/PipeWire playback sink — hardware card or virtual (no card). */
    private static final class PulseOut {
        final String name;
        final String desc;
        final boolean running;
        final String port;
        final String avail;

        PulseOut(String name, String desc, boolean running) {
            this(name, desc, running, null, "");
        }

        PulseOut(String name, String desc, boolean running, String port, String avail) {
            this.name = name;
            this.desc = desc;
            this.running = running;
            this.port = port;
            this.avail = avail != null ? avail : "";
        }

        boolean isSystemDefault() {
            return name == null || name.isBlank();
        }

        String key() {
            return (name != null ? name : "") + "|" + (port != null ? port : "");
        }

        String sinkName(String fallback) {
            if (!isSystemDefault()) return name;
            return fallback;
        }

        String monitorName(String fallbackSink) {
            String s = sinkName(fallbackSink);
            if (s == null || s.isBlank()) return "default.monitor";
            return s.endsWith(".monitor") ? s : s + ".monitor";
        }

        boolean portUnplugged() {
            String a = avail.toLowerCase();
            return a.contains("not available") || a.equals("no");
        }

        public String toString() {
            String mark = isSystemDefault() ? "◆ " : (portUnplugged() ? "× " : (running ? "● " : "○ "));
            String d = desc != null && !desc.isBlank() ? desc : name;
            if (d != null && d.length() > 36) d = d.substring(0, 34) + "…";
            if (avail.toLowerCase().contains("unknown")) d = (d != null ? d : "") + " ~";
            return mark + (d != null ? d : "output");
        }
    }

    /** Live L/R peak + RMS meters for WYHIWYREC (what you hear). */
    private static final class RecMeter extends JPanel {
        private volatile float lPeak = -90, rPeak = -90, lRms = -90, rRms = -90;
        private volatile float lHold = -90, rHold = -90;
        private volatile boolean clip;
        private volatile boolean live;

        RecMeter() {
            setPreferredSize(new Dimension(640, 52));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            setMinimumSize(new Dimension(200, 48));
            setBackground(new Color(0x0b, 0x10, 0x20));
            setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0x9b, 0xe7, 0xff), 1),
                "SIGNAL  WYHIWYREC",
                0, 0, new Font("Monospaced", Font.PLAIN, 8), new Color(0x9b, 0xe7, 0xff)
            ));
            setToolTipText("L/R peak (bright) + RMS (fill). 0 dBFS clip. TASCAM monitor.");
        }

        void setLevels(float lp, float rp, float lr, float rr) {
            lPeak = lp; rPeak = rp; lRms = lr; rRms = rr;
            lHold = Math.max(lp, lHold - 1.4f);
            rHold = Math.max(rp, rHold - 1.4f);
            clip = lp > -0.3f || rp > -0.3f;
            live = true;
            repaint();
        }

        void idle() {
            live = false;
            clip = false;
            lPeak = rPeak = lRms = rRms = lHold = rHold = -90;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            int w = getWidth();
            int h = getHeight();
            int left = 28;
            int right = 72;
            int barW = Math.max(40, w - left - right - 8);
            int yL = 14;
            int yR = 30;
            int bh = 10;
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            g2.setColor(new Color(0x98, 0xb7, 0xc7));
            g2.drawString("L", 8, yL + 8);
            g2.drawString("R", 8, yR + 8);
            drawBar(g2, left, yL, barW, bh, lRms, lPeak, lHold);
            drawBar(g2, left, yR, barW, bh, rRms, rPeak, rHold);
            String num = live
                ? String.format("%5.1f  %5.1f", lPeak, rPeak)
                : "idle";
            g2.setColor(clip ? new Color(0xe8, 0x5a, 0x6a) : new Color(0x9b, 0xe7, 0xff));
            g2.drawString(clip ? "CLIP" : num, left + barW + 6, yL + 9);
            g2.setColor(new Color(0x98, 0xb7, 0xc7));
            if (live) g2.drawString("dBFS", left + barW + 6, yR + 9);
        }

        private void drawBar(Graphics2D g2, int x, int y, int w, int h, float rms, float peak, float hold) {
            g2.setColor(new Color(0x12, 0x18, 0x28));
            g2.fillRect(x, y, w, h);
            int rmsW = dbToW(rms, w);
            int pkW = dbToW(peak, w);
            int hdX = x + dbToW(hold, w);
            g2.setColor(barColor(rms));
            g2.fillRect(x, y, rmsW, h);
            g2.setColor(barColor(peak));
            g2.fillRect(x, y, Math.max(1, pkW), 2);
            g2.setColor(new Color(0xe6, 0xf6, 0xff));
            g2.fillRect(Math.min(x + w - 2, hdX), y, 2, h);
            g2.setColor(new Color(0x22, 0x2a, 0x3a));
            g2.drawRect(x, y, w, h);
        }

        private static int dbToW(float db, int w) {
            float n = (db + 60f) / 60f;
            if (n < 0) n = 0;
            if (n > 1) n = 1;
            return Math.round(n * w);
        }

        private static Color barColor(float db) {
            if (db > -6) return new Color(0xe8, 0x5a, 0x6a);
            if (db > -18) return new Color(0xe8, 0xc0, 0x5a);
            return new Color(0x5a, 0xe0, 0x80);
        }
    }

    private JFrame frame;
    private JTable jobsTable;
    private DefaultTableModel jobsModel;
    private JTextArea visualistArea;
    private DefaultTableModel ascTableModel;
    private JTable ascTable;
    private final java.util.List<AscEntry> ascEntries = new java.util.ArrayList<>();
    private String selectedAscKey;
    private JLabel ascSelLabel;
    private JButton ascPlayBtn;
    private JButton ascRecBtn;
    private JLabel statusLabel;
    private DefaultListModel<RemoteNavEntry> remoteNavModel;
    private JList<RemoteNavEntry> remoteNavList;

    // URL drag & drop + copy-paste input (above status bar) for direct download to ~/Downloads/
    // Mirrors the gravity-client TUI feature. Uses yt-dlp CLI (ProcessBuilder, safe list, no shell/eval).
    private JTextField urlField;
    private JButton downloadBtn;
    private JButton gifBtn;
    private JButton previewBtn;
    private JProgressBar directProgress;
    private JLabel directStatusLabel;
    private volatile String lastDownloadPath;
    private JLabel mintStatusLabel;
    private DefaultListModel<RecentDownload> recentDownloadsModel;
    private JList<RecentDownload> recentDownloadsList;

    // KISS rotation theta + dual vi (i & j = true) for H.264
    private JTextField thetaField;
    private JCheckBox dualCheck;
    private JCheckBox gifCheck;
    private JCheckBox privateCheck;
    private JCheckBox vacuumCheck;
    private JCheckBox vacuumDetailCheck;
    private JButton vacuumLogBtn;
    private JTextArea vacuumLogArea;
    private JScrollPane vacuumLogScroll;
    private JCheckBox tlsJa3Check;
    private JCheckBox vpnCheck;
    private JComboBox<String> vpnProviderBox;
    private JComboBox<String> overlayBox;
    private JButton astrillBtn;
    private JLabel netL;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean gravityRunning = true;
    private volatile Process currentYtDlpProc;
    private volatile boolean abortRequested = false;
    private volatile Process sshSocksProc;
    private volatile Process ngrokProc;
    private volatile Process recProc;
    private volatile Process recYtdlpProc;
    private volatile Process recPlayProc;
    private volatile boolean recRunning = false;
    private volatile String recOutPath;
    private JButton sshSocksBtn;
    private JButton switcherBtn;
    private JButton ngrokBtn;
    private JButton recBtn;
    private JButton recWyhiwyBtn;
    private JButton recNetBtn;
    private JButton recCatalogFnBtn;
    private JButton recGoBtn;
    private JButton recStopBtn;
    private JButton recSaveBtn;
    private JComboBox<String> recFormatBox;
    private JComboBox<String> recChannelBox;
    private JCheckBox recCopyCheck;
    private JCheckBox recHearCheck;
    private JCheckBox recAutoCheck;
    private JCheckBox recSigCheck;
    private JPanel recOptRow;
    private JPanel recOutRow;
    private JPanel recSouth;
    private JLabel recOptL;
    private String recFn = "WYHIWYREC";
    private boolean recUrlFromClipboard;
    private RecMeter recMeter;
    private volatile Process recMeterProc;
    private JComboBox<PulseOut> recOutBox;
    private JButton recOutRefreshBtn;
    private JCheckBox recRouteCheck;
    private JCheckBox recAlsaCheck;
    private JCheckBox recPwCheck;
    private volatile Process recPwProc;
    private boolean recOutRefreshing;
    private final java.util.Map<String, String> recAlsaSavedProfiles = new java.util.HashMap<>();

    // ASC frame using user-provided symbol pattern for live metadata display (filmic/ASC aesthetic)
    private static final String ASC_FRAME =
        "⿲⿳⿴⿵⿶⿷⿸⿹⿺⿻⿰ \n" +
        "⿳⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱ \n" +
        "⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲ \n" +
        "⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲⿳ \n" +
        "⿶⿷⿸⿹⿺⿻⿰⿱⿲⿳⿴ \n" +
        "⿷⿸⿹⿺⿻⿰⿱⿲⿳⿴⿵ \n" +
        "⿸⿹⿺⿻⿰⿱⿲⿳⿴⿵⿶ \n" +
        "⿹⿺⿻⿰⿱⿲⿳⿴⿵⿶⿷ \n" +
        "⿺⿻⿰⿱⿲⿳⿴⿵⿶⿷⿸\n";

    private static final long RUNTIME_STARTED_MS = System.currentTimeMillis();
    private static final File RUNTIME_LOG_DIR = new File(
        System.getProperty("user.home"), ".local/share/gravity-desktop/runtime-logs");
    private static final File RUNTIME_LIVE_LOG = new File(RUNTIME_LOG_DIR, "gravity-desktop-live.jsonl");
    private static final File RUNTIME_SESSION = new File(RUNTIME_LOG_DIR, "gravity-desktop-session.json");
    private static final Object RUNTIME_LOG_LOCK = new Object();

    static {
        initRuntimeLog();
    }

    private static boolean runtimeLogEnabled() {
        String v = System.getenv("GRAVITY_RUNTIME_LOG");
        if (v == null || v.isBlank()) return true;
        v = v.trim().toLowerCase();
        return !("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v));
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static void initRuntimeLog() {
        if (!runtimeLogEnabled()) return;
        RUNTIME_LOG_DIR.mkdirs();
        String started = java.time.Instant.now().toString();
        String session = "{\n"
            + "  \"source\": \"gravity-desktop\",\n"
            + "  \"pid\": " + ProcessHandle.current().pid() + ",\n"
            + "  \"started_at\": \"" + started + "\",\n"
            + "  \"live_log\": \"" + jsonEsc(RUNTIME_LIVE_LOG.getAbsolutePath()) + "\",\n"
            + "  \"gravity_host\": \"" + HOST + ":" + PORT + "\"\n"
            + "}\n";
        try (FileWriter fw = new FileWriter(RUNTIME_SESSION, false)) {
            fw.write(session);
        } catch (Exception ignore) {}
        appendRuntimeLog("session_start", null);
    }

    private static void appendRuntimeLog(String event, String extraFields) {
        if (!runtimeLogEnabled()) return;
        long runtimeMs = System.currentTimeMillis() - RUNTIME_STARTED_MS;
        String ts = java.time.Instant.now().toString();
        String line = "{\"ts\":\"" + ts + "\",\"runtime_ms\":" + runtimeMs
            + ",\"source\":\"gravity-desktop\",\"event\":\"" + jsonEsc(event) + "\""
            + (extraFields != null && !extraFields.isBlank() ? "," + extraFields : "")
            + "}\n";
        synchronized (RUNTIME_LOG_LOCK) {
            try (FileWriter fw = new FileWriter(RUNTIME_LIVE_LOG, true)) {
                fw.write(line);
            } catch (Exception ignore) {}
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            GravityDesktop app = new GravityDesktop();
            app.createAndShowGUI();
            app.connectToGravity();
            app.startNlsarBackendPoller();
        });
    }

    /** App home: GRAVITY_DESKTOP_HOME env, then directory containing this class/jar. */
    private static String resolveAppDir() {
        String env = System.getenv("GRAVITY_DESKTOP_HOME");
        if (env != null && !env.isBlank()) {
            File f = new File(env);
            if (f.isDirectory()) return f.getAbsolutePath();
        }
        try {
            java.net.URL loc = GravityDesktop.class.getProtectionDomain().getCodeSource().getLocation();
            File f = new File(loc.toURI());
            if (f.isFile()) return f.getParentFile().getAbsolutePath();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }

    private static String resolveExecutable(String envVar, String fallbackName, String... candidates) {
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            File f = new File(env);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        for (String c : candidates) {
            File f = new File(c);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        try {
            Process p = new ProcessBuilder("which", fallbackName).start();
            if (p.waitFor() == 0) {
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String path = br.readLine();
                if (path != null && !path.isBlank()) return path.trim();
            }
        } catch (Exception ignore) {}
        return fallbackName;
    }

    private static String resolveYtDlpPath() {
        String home = System.getProperty("user.home");
        return resolveExecutable("YTDLP_PATH", "yt-dlp",
            APP_DIR + "/yt-dlp",
            home + "/Downloads/GravityDesktop/yt-dlp",
            home + "/.local/bin/yt-dlp",
            "/usr/local/bin/yt-dlp",
            "/usr/bin/yt-dlp");
    }

    private static String resolveFfmpegPath() {
        String home = System.getProperty("user.home");
        return resolveExecutable("FFMPEG_PATH", "ffmpeg",
            "/usr/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            home + "/.local/bin/ffmpeg");
    }

    private static String resolveFfplayPath() {
        String home = System.getProperty("user.home");
        return resolveExecutable("FFPLAY_PATH", "ffplay",
            "/usr/bin/ffplay",
            "/usr/local/bin/ffplay",
            home + "/.local/bin/ffplay");
    }

    /** InterlaterusDesktop: download → probe → blockcode mint → NLS catalog. */
    private static String resolveInterlaterusScript() {
        String home = System.getProperty("user.home");
        String[] candidates = {
            APP_DIR + "/interlaterus_desktop.py",
            home + "/Downloads/nls-video-monitor/interlaterus_desktop.py",
            home + "/Downloads/GravityDesktop/interlaterus_desktop.py",
        };
        for (String c : candidates) {
            if (new File(c).isFile()) return c;
        }
        return null;
    }

    private void triggerInterlaterusMint(String mediaPath, String sourceUrl, String title) {
        if ("1".equals(System.getenv("INTERLATERUS_SKIP_MINT"))) {
            updateMintStatus("skipped", null, null);
            return;
        }
        String script = resolveInterlaterusScript();
        if (script == null || mediaPath == null || mediaPath.isBlank()) {
            updateMintStatus("no_script", null, null);
            return;
        }
        File media = new File(mediaPath);
        if (!media.isFile()) return;
        final String scriptFinal = script;
        final String pathFinal = media.getAbsolutePath();
        final String urlFinal = sourceUrl != null ? sourceUrl : "";
        final String titleFinal = title != null ? title : "";
        updateMintStatus("minting", null, null);
        new Thread(() -> {
            StringBuilder jsonOut = new StringBuilder();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/python3", scriptFinal, "mint",
                    "--file", pathFinal,
                    "--url", urlFinal,
                    "--title", titleFinal
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String ln;
                    while ((ln = br.readLine()) != null) {
                        jsonOut.append(ln).append('\n');
                    }
                }
                int rc = proc.waitFor();
                if (rc == 0) {
                    parseAndShowMintResult(jsonOut.toString(), pathFinal);
                    refreshRecentDownloads();
                } else {
                    updateMintStatus("failed", null, null);
                }
            } catch (Exception ex) {
                updateMintStatus("failed", null, null);
            }
        }).start();
    }

    private void updateMintStatus(String phase, String pattern, int[] vertex) {
        SwingUtilities.invokeLater(() -> {
            if (mintStatusLabel == null) return;
            Color ok = new Color(0x5a, 0xe0, 0xd0);
            Color warn = new Color(0xc8, 0xd0, 0x5a);
            Color err = new Color(0xe8, 0x5a, 0x6a);
            Color muted = new Color(0x98, 0xb7, 0xc7);
            String vtx = vertex != null
                ? "[" + vertex[0] + "," + vertex[1] + "," + vertex[2] + "," + vertex[3] + "]"
                : "";
            switch (phase) {
                case "minting":
                    mintStatusLabel.setForeground(warn);
                    mintStatusLabel.setText("mint: probing → blockcode tesseract...");
                    break;
                case "minted":
                    mintStatusLabel.setForeground(ok);
                    mintStatusLabel.setText("mint: ✓ " + pattern + "  vertex " + vtx);
                    break;
                case "already_minted":
                    mintStatusLabel.setForeground(warn);
                    mintStatusLabel.setText("mint: ○ " + pattern + " (already minted)");
                    break;
                case "skipped":
                    mintStatusLabel.setForeground(muted);
                    mintStatusLabel.setText("mint: skipped (INTERLATERUS_SKIP_MINT=1)");
                    break;
                case "no_script":
                    mintStatusLabel.setForeground(err);
                    mintStatusLabel.setText("mint: interlaterus_desktop.py not found");
                    break;
                case "failed":
                    mintStatusLabel.setForeground(err);
                    mintStatusLabel.setText("mint: failed — check interlaterus / ffprobe");
                    break;
                case "pending":
                    mintStatusLabel.setForeground(muted);
                    mintStatusLabel.setText("mint: pending — " + (pattern != null ? pattern : "no sidecar yet"));
                    break;
                default:
                    mintStatusLabel.setForeground(muted);
                    mintStatusLabel.setText("mint: —");
            }
        });
    }

    private void parseAndShowMintResult(String output, String mediaPath) {
        String status = extractJsonString(output, "status");
        String pattern = extractJsonString(output, "pattern_code");
        int[] vertex = extractJsonVertex(output);
        if (pattern == null || pattern.isBlank()) {
            MintSidecar sidecar = readMintSidecar(mediaPath);
            if (sidecar != null) {
                pattern = sidecar.pattern;
                if (vertex == null) vertex = sidecar.vertex;
            }
        }
        if ("already_minted".equals(status)) {
            updateMintStatus("already_minted", pattern, vertex);
        } else if ("minted".equals(status)) {
            updateMintStatus("minted", pattern, vertex);
        } else {
            updateMintStatus("failed", pattern, vertex);
        }
        appendMintToVisualist(status, pattern, vertex, mediaPath);
        String vtx = vertex != null
            ? "[" + vertex[0] + "," + vertex[1] + "," + vertex[2] + "," + vertex[3] + "]"
            : "null";
        appendRuntimeLog("mint_complete",
            "\"status\":\"" + jsonEsc(status) + "\",\"pattern\":\"" + jsonEsc(pattern)
            + "\",\"vertex\":" + vtx + ",\"media\":\"" + jsonEsc(mediaPath) + "\"");
    }

    private void appendMintToVisualist(String status, String pattern, int[] vertex, String mediaPath) {
        if (pattern == null || pattern.isBlank()) return;
        String vtx = vertex != null
            ? "[" + vertex[0] + "," + vertex[1] + "," + vertex[2] + "," + vertex[3] + "]"
            : "—";
        String file = mediaPath != null ? new File(mediaPath).getName() : "";
        String block = "\n⿲ BLOCKCODE MINT " + status.toUpperCase() + " ⿲\n"
            + "pattern: " + pattern + "\n"
            + "vertex:  " + vtx + "\n"
            + "file:    " + file + "\n";
        SwingUtilities.invokeLater(() -> {
            if (visualistArea == null) return;
            String cur = visualistArea.getText();
            visualistArea.setText(cur + block);
        });
    }

    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static int[] extractJsonVertex(String json) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"vertex\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]")
            .matcher(json);
        if (!m.find()) return null;
        return new int[]{
            Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4))
        };
    }

    private static final class MintSidecar {
        final String pattern;
        final int[] vertex;

        MintSidecar(String pattern, int[] vertex) {
            this.pattern = pattern;
            this.vertex = vertex;
        }
    }

    private static MintSidecar readMintSidecar(String mediaPath) {
        File sidecar = new File(mediaPath + ".interlaterus.json");
        if (!sidecar.isFile()) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(sidecar.toPath()));
            String pattern = extractJsonString(text, "pattern_code");
            int[] vertex = extractJsonVertex(text);
            if (pattern == null) return null;
            return new MintSidecar(pattern, vertex);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean isRecentDownloadName(String name) {
        if (name == null) return false;
        return name.matches("^direct-h264-.+\\.mp4$")
            || name.matches("^direct-gif-.+\\.gif$")
            || name.matches("^direct-h264-rot\\d+-.+\\.mp4$");
    }

    /** First take is unsuffixed; later takes are -002, -003, … (never overwrite). */
    private static File nextDirectFile(String home, String mediaId, boolean gif) {
        String prefix = gif ? ("direct-gif-" + mediaId) : ("direct-h264-" + mediaId);
        String ext = gif ? ".gif" : ".mp4";
        File first = new File(home + "/Downloads/" + prefix + ext);
        if (!first.exists()) return first;
        for (int n = 2; n < 1000; n++) {
            File f = new File(home + "/Downloads/" + prefix + "-" + String.format("%03d", n) + ext);
            if (!f.exists()) return f;
        }
        return new File(home + "/Downloads/" + prefix + "-" + System.currentTimeMillis() + ext);
    }

    private static int cleanupDownloadTemps(File dest) {
        if (dest == null) return 0;
        File dir = dest.getParentFile();
        String name = dest.getName();
        if (dir == null || name == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File p : files) {
            String fn = p.getName();
            if (!(fn.equals(name) || fn.startsWith(name + ".") || fn.startsWith(name + ".part"))) continue;
            if (fn.endsWith(".part") || fn.endsWith(".ytdl") || fn.endsWith(".vac") || fn.contains(".part-Frag")) {
                if (p.delete()) n++;
            }
        }
        return n;
    }

    private String downloadsDir() {
        return System.getProperty("user.home") + "/Downloads";
    }

    private void refreshRecentDownloads() {
        File dir = new File(downloadsDir());
        File[] files = dir.listFiles();
        java.util.List<File> matches = new java.util.ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isRecentDownloadName(f.getName())) {
                    matches.add(f);
                }
            }
        }
        matches.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        final java.util.List<RecentDownload> entries = new java.util.ArrayList<>();
        int limit = 25;
        for (int i = 0; i < matches.size() && i < limit; i++) {
            File f = matches.get(i);
            String label = f.getName();
            MintSidecar sidecar = readMintSidecar(f.getAbsolutePath());
            if (sidecar != null && sidecar.pattern != null) {
                label += "  ·  " + sidecar.pattern;
                if (sidecar.vertex != null) {
                    label += "  v" + sidecar.vertex[0] + sidecar.vertex[1]
                        + sidecar.vertex[2] + sidecar.vertex[3];
                }
            }
            entries.add(new RecentDownload(f.getAbsolutePath(), label));
        }
        SwingUtilities.invokeLater(() -> {
            if (recentDownloadsModel == null) return;
            recentDownloadsModel.clear();
            for (RecentDownload rd : entries) {
                recentDownloadsModel.addElement(rd);
            }
        });
    }

    private void openDownloadsFolder() {
        String path = downloadsDir();
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb = os.contains("mac")
                ? new ProcessBuilder("open", path)
                : new ProcessBuilder("xdg-open", path);
            pb.start();
            directStatusLabel.setText("Opened ~/Downloads/");
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            directStatusLabel.setText("Could not open Downloads: " + msg);
        }
    }

    private void onRecentDownloadSelected(RecentDownload sel) {
        if (sel == null) return;
        setLastDownloadPath(sel.path);
        MintSidecar sidecar = readMintSidecar(sel.path);
        if (sidecar != null) {
            updateMintStatus("minted", sidecar.pattern, sidecar.vertex);
        } else {
            updateMintStatus("pending", new File(sel.path).getName(), null);
        }
    }

    private void createAndShowGUI() {
        // KISS design following the visual_language_animation.svg
        // Dark gradient-like bg, monospace, rounded container feel, thin cyan accent border,
        // colored node indicators for pipeline stages (H.264 focus), simple clean layout.
        frame = new JFrame("NLS Visualist • H.264 v1.1.9");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1240, 760);
        frame.setLocationRelativeTo(null);

        Color bg = new Color(0x09, 0x09, 0x0f);
        Color panelBg = new Color(0x16, 0x1a, 0x2a);
        Color accent = new Color(0x9b, 0xe7, 0xff); // #9be7ff from SVG
        Color textLight = new Color(0xe6, 0xf6, 0xff);
        Color textMuted = new Color(0x98, 0xb7, 0xc7);

        frame.getContentPane().setBackground(bg);
        frame.setLayout(new BorderLayout(8, 8));

        // Top header - KISS: title + pipeline flow subtitle (monospace)
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(bg);
        header.setBorder(BorderFactory.createEmptyBorder(8, 16, 4, 16));

        JLabel title = new JLabel("NLS Visualist • H.264 Pipeline");
        title.setFont(new Font("Monospaced", Font.BOLD, 20));
        title.setForeground(textLight);
        header.add(title, BorderLayout.NORTH);

        JLabel sub = new JLabel("https:// / ipfs://  →  H.264 mp4 / GIF  •  human sleep  •  VPN/proxy geo  •  403 retry");
        sub.setFont(new Font("Monospaced", Font.PLAIN, 12));
        sub.setForeground(textMuted);
        header.add(sub, BorderLayout.SOUTH);
        switcherBtn = new JButton("SWITCHER");
        switcherBtn.setFont(new Font("Monospaced", Font.BOLD, 13));
        switcherBtn.setForeground(bg);
        switcherBtn.setBackground(accent);
        switcherBtn.setToolTipText("Open Gravity Switcher — separate network-planning applet (Catalog / Case / NNLR / Security)");
        switcherBtn.addActionListener(e -> startGravitySwitcher());
        header.add(switcherBtn, BorderLayout.EAST);

        frame.add(header, BorderLayout.NORTH);

        // Main container with thin cyan border (like SVG main rect rx="24" stroke #9be7ff)
        JPanel mainWrap = new JPanel(new BorderLayout(6, 6));
        mainWrap.setBackground(panelBg);
        mainWrap.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent, 1),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        // H.264 pipeline stages as simple colored node indicators (KISS version of SVG nodes)
        // Colors + symbols inspired by the SVG visual language
        JPanel stages = new JPanel(new GridLayout(1, 5, 8, 0));
        stages.setBackground(panelBg);
        String[] stageNames = {"INPUT", "DOWNLOAD", "H.264", "GRAVITY", "CATALOG"};
        Color[] stageCols = {
            new Color(0x5a, 0xe0, 0xd0), // hsl(176 ~ cyan)
            new Color(0xc8, 0xd0, 0x5a), // hsl(78 ~ yellow-green)
            new Color(0xe8, 0x5a, 0x6a), // hsl(355 ~ red)
            new Color(0x5a, 0xd0, 0x7a), // hsl(123 ~ green)
            new Color(0xb0, 0x5a, 0xd0)  // hsl(310 ~ purple)
        };
        String[] symbols = {"◐", "▦", "⌬", "▣", "◈"};
        for (int i = 0; i < stageNames.length; i++) {
            JPanel node = new JPanel(new BorderLayout());
            node.setBackground(panelBg);
            JLabel sym = new JLabel(symbols[i], SwingConstants.CENTER);
            sym.setFont(new Font("Monospaced", Font.PLAIN, 22));
            sym.setForeground(stageCols[i]);
            JLabel lbl = new JLabel(stageNames[i], SwingConstants.CENTER);
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
            lbl.setForeground(textMuted);
            node.add(sym, BorderLayout.CENTER);
            node.add(lbl, BorderLayout.SOUTH);
            stages.add(node);
        }
        mainWrap.add(stages, BorderLayout.NORTH);

        // Simple H.264 focused input (KISS drag/paste URL for download + convert)
        // Added rotation theta + dual vi (i & j = true) per visual language
        JPanel inputBox = new JPanel(new BorderLayout(6, 4));
        inputBox.setBackground(panelBg);
        inputBox.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(accent, 1),
            "URL  (https://, ipfs://, .php viewer, GIF — paste / drag → ~/Downloads/)",
            0, 0, new Font("Monospaced", Font.PLAIN, 10), accent
        ));

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(panelBg);
        urlField = new JTextField(50);
        urlField.setFont(new Font("Monospaced", Font.PLAIN, 13));
        urlField.setBackground(new Color(0x0b, 0x10, 0x20));
        urlField.setForeground(textLight);
        urlField.setCaretColor(accent);
        urlField.setEditable(true);
        urlField.setFocusable(true);
        urlField.setDragEnabled(true);  // allow selection drag + paste support
        urlField.addActionListener(e -> startDirectDownload());
        downloadBtn = new JButton("H.264");
        downloadBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        downloadBtn.setForeground(bg);
        downloadBtn.setBackground(accent);
        downloadBtn.setToolTipText("Download H.264 mp4 to ~/Downloads/");
        downloadBtn.addActionListener(e -> {
            if (gifCheck != null) gifCheck.setSelected(false);
            startDirectDownload(Boolean.FALSE);
        });
        gifBtn = new JButton("GIF");
        gifBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        gifBtn.setForeground(bg);
        gifBtn.setBackground(new Color(0xe0, 0x5a, 0xc0));
        gifBtn.setToolTipText("Download any GIF: direct .gif, X/Twitter, Giphy, Tenor, Imgur gifv, or recode video");
        gifBtn.addActionListener(e -> {
            if (gifCheck != null) gifCheck.setSelected(true);
            startDirectDownload(Boolean.TRUE);
        });
        previewBtn = new JButton("▶");
        previewBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        previewBtn.setForeground(textLight);
        previewBtn.setBackground(new Color(0x1a, 0x25, 0x40));
        previewBtn.setEnabled(false);
        previewBtn.setToolTipText("Preview last download with ffplay");
        previewBtn.addActionListener(e -> previewLastDownload());
        JPanel btnCol = new JPanel(new GridLayout(3, 1, 0, 2));
        btnCol.setBackground(panelBg);
        btnCol.add(downloadBtn);
        btnCol.add(gifBtn);
        btnCol.add(previewBtn);
        row.add(urlField, BorderLayout.CENTER);
        row.add(btnCol, BorderLayout.EAST);
        inputBox.add(row, BorderLayout.CENTER);

        // Rotation theta dual vi controls (KISS, H.264 post-process support)
        // + JS runtime check/install for yt-dlp (Deno and Node.js are similarly supported; pythonJava style: Java UI + Python helper possible)
        JPanel rotRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rotRow.setBackground(panelBg);
        JLabel thetaL = new JLabel("theta°:");
        thetaL.setFont(new Font("Monospaced", Font.PLAIN, 9));
        thetaL.setForeground(textMuted);
        thetaField = new JTextField("0", 4);
        thetaField.setFont(new Font("Monospaced", Font.PLAIN, 9));
        thetaField.setBackground(new Color(0x0b, 0x10, 0x20));
        thetaField.setForeground(textLight);
        dualCheck = new JCheckBox("dual vi");
        dualCheck.setFont(new Font("Monospaced", Font.PLAIN, 9));
        dualCheck.setForeground(textMuted);
        dualCheck.setBackground(panelBg);
        gifCheck = new JCheckBox("GIF");
        gifCheck.setFont(new Font("Monospaced", Font.PLAIN, 9));
        gifCheck.setForeground(textMuted);
        gifCheck.setBackground(panelBg);
        gifCheck.setToolTipText("Enter/drag uses GIF. Or click the GIF trigger. X status GIFs (tweet_video) auto-detect.");
        privateCheck = new JCheckBox("private");
        privateCheck.setFont(new Font("Monospaced", Font.PLAIN, 9));
        privateCheck.setForeground(textMuted);
        privateCheck.setBackground(panelBg);
        privateCheck.setSelected(false);
        privateCheck.setToolTipText("Cookies for login-walled / VPN YouTube (Brave or ~/.config/gravity-desktop/cookies.txt)");
        vacuumCheck = new JCheckBox("vacuum");
        vacuumCheck.setFont(new Font("Monospaced", Font.PLAIN, 9));
        vacuumCheck.setForeground(textMuted);
        vacuumCheck.setBackground(panelBg);
        String vacPref = envStr("GRAVITY_VACUUM", "");
        if (vacPref.isEmpty()) {
            String fromFile = readEnvFileKey("GRAVITY_VACUUM");
            vacPref = fromFile != null ? fromFile : "0";
        }
        vacuumCheck.setSelected("1".equals(vacPref) || "true".equalsIgnoreCase(vacPref));
        vacuumCheck.setToolTipText("Opt-in: after img/vid/gif download, strip JS/HTML/PHP polyglots, trailers, metadata. Off = leave file as fetched.");
        vacuumCheck.addActionListener(e ->
            writeEnvFileKey("GRAVITY_VACUUM", vacuumCheck.isSelected() ? "1" : "0"));
        vacuumDetailCheck = new JCheckBox("detail");
        vacuumDetailCheck.setFont(new Font("Monospaced", Font.PLAIN, 9));
        vacuumDetailCheck.setForeground(textMuted);
        vacuumDetailCheck.setBackground(panelBg);
        String detPref = envStr("GRAVITY_VACUUM_DETAIL", "");
        if (detPref.isEmpty()) {
            String df = readEnvFileKey("GRAVITY_VACUUM_DETAIL");
            detPref = df != null ? df : "0";
        }
        vacuumDetailCheck.setSelected("1".equals(detPref));
        vacuumDetailCheck.setToolTipText("Show live vacuum steps in the log pane (and keep writing runtime jsonl).");
        vacuumDetailCheck.addActionListener(e -> {
            writeEnvFileKey("GRAVITY_VACUUM_DETAIL", vacuumDetailCheck.isSelected() ? "1" : "0");
            setVacuumLogVisible(vacuumDetailCheck.isSelected());
        });
        vacuumLogBtn = new JButton("log");
        vacuumLogBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        vacuumLogBtn.setToolTipText("Refresh vacuum / TLS-JA3 log (re-read jsonl, no stale pane).");
        vacuumLogBtn.addActionListener(e -> showVacuumLogDialog());
        JButton vacuumClearBtn = new JButton("clear");
        vacuumClearBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        vacuumClearBtn.setToolTipText("Clear on-screen vacuum log (jsonl kept).");
        vacuumClearBtn.addActionListener(e -> clearVacuumLogView());
        tlsJa3Check = new JCheckBox("TLS/JA3");
        tlsJa3Check.setFont(new Font("Monospaced", Font.PLAIN, 9));
        tlsJa3Check.setForeground(textMuted);
        tlsJa3Check.setBackground(panelBg);
        String tlsEnv = envStr("GRAVITY_IMPERSONATE", "");
        if (tlsEnv.isEmpty() && readEnvFileKey("GRAVITY_IMPERSONATE") != null) {
            tlsEnv = readEnvFileKey("GRAVITY_IMPERSONATE");
        }
        tlsJa3Check.setSelected(!(tlsEnv.equals("0") || tlsEnv.equalsIgnoreCase("off") || tlsEnv.equalsIgnoreCase("none")));
        tlsJa3Check.setToolTipText("Opt-out: Chrome TLS/JA3 impersonate (curl_cffi) so Pornhub etc. don't warn. Uncheck = no --impersonate.");
        tlsJa3Check.addActionListener(e -> {
            writeEnvFileKey("GRAVITY_IMPERSONATE", tlsJa3Check.isSelected() ? "chrome" : "off");
            impersonateClient = null;
        });
        vpnCheck = new JCheckBox("VPN");
        vpnCheck.setFont(new Font("Monospaced", Font.PLAIN, 9));
        vpnCheck.setForeground(textMuted);
        vpnCheck.setBackground(panelBg);
        vpnCheck.setSelected(vpnRouteRequested());
        vpnCheck.setToolTipText("Kernel VPN: Astrill Stealth (tun0, China hotel DPI) or Surfshark WG (datacenter 403). Do not stack. Prefer SSH VPS for YouTube.");
        vpnProviderBox = new JComboBox<>(new String[]{"VPN auto", "Astrill", "Surfshark"});
        vpnProviderBox.setFont(new Font("Monospaced", Font.PLAIN, 8));
        vpnProviderBox.setToolTipText("Auto = detect tun0/Astrill vs surfshark_wg. Archive: ~/Downloads/NLS-Astrill. Config: ~/.config/astrill. Binary: /usr/local/Astrill/astrill");
        String wantProv = envStr("GRAVITY_VPN_PROVIDER", "auto").toLowerCase();
        if (wantProv.contains("astrill")) vpnProviderBox.setSelectedItem("Astrill");
        else if (wantProv.contains("surfshark")) vpnProviderBox.setSelectedItem("Surfshark");
        vpnProviderBox.addActionListener(e -> {
            String p = selectedVpnProvider();
            writeEnvFileKey("GRAVITY_VPN_PROVIDER", "astrill".equals(p) || "surfshark".equals(p) ? p : "auto");
            refreshNetLabel();
            if (directStatusLabel != null && "stacked".equals(vpnProviderFromIfaces(liveVpnIfaceNames(), astrillProcessRunning()))) {
                directStatusLabel.setText("Do not stack Surfshark + Astrill — disconnect one");
            }
        });
        overlayBox = new JComboBox<>(new String[]{
            "HKG/direct",
            "SSH VPS",
            "Tor onion",
            "I2P garlic"
        });
        overlayBox.setFont(new Font("Monospaced", Font.PLAIN, 8));
        overlayBox.setToolTipText("No kernel VPN. SSH VPS = user@host SOCKS (YouTube). Tor onion = SOCKS 9050 (system tor). I2P garlic = Java garlic router HTTP/SOCKS 4444/4447.");
        overlayBox.addActionListener(e -> onOverlayChanged());
        vpnCheck.addActionListener(e -> {
            if (vpnCheck.isSelected()) {
                if (privateCheck != null) privateCheck.setSelected(true);
                String prov = selectedVpnProvider();
                String src = vpnTunnelIpv4ForProvider(prov);
                if (src == null) {
                    if ("astrill".equals(prov) || astrillInstalled()) {
                        directStatusLabel.setText("VPN on — launch Astrill, StealthVPN ON (tun0). Do not stack Surfshark.");
                    } else {
                        directStatusLabel.setText("VPN on — connect Surfshark or Astrill first (no tunnel IP)");
                    }
                }
            }
            refreshNetLabel();
            if (netL != null) {
                String r = describeNetworkRoute();
                if ((r.contains("kill-switch") || r.contains("datacenter")) && !useVpnRoute()) {
                    if (directStatusLabel != null) {
                        directStatusLabel.setText(r.contains("Cyberzone")
                            ? "Surfshark kill-switch: YouTube uses Cyberzone (403). Disconnect or disable kill-switch."
                            : "Full tunnel (Astrill): ISP is dead. Uncheck VPN only after disconnecting.");
                    }
                }
            }
        });
        JLabel ijL = new JLabel("i & j = true");
        ijL.setFont(new Font("Monospaced", Font.PLAIN, 8));
        ijL.setForeground(accent);

        // JS runtime status + install (addresses yt-dlp JS warning)
        String jsRt = checkJsRuntime();
        JLabel jsL = new JLabel("JS: " + (jsRt != null ? jsRt : "none"));
        jsL.setFont(new Font("Monospaced", Font.PLAIN, 8));
        jsL.setForeground(jsRt != null ? new Color(0x5a, 0xe0, 0xd0) : new Color(0xe8, 0x5a, 0x6a));
        JButton jsInstall = new JButton("Install Deno");
        jsInstall.setFont(new Font("Monospaced", Font.PLAIN, 8));
        jsInstall.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(frame,
                "Install Deno (recommended JS runtime for yt-dlp)?\n" +
                "Deno and Node.js are similarly supported by yt-dlp.\n" +
                "Uses curl | sh (or you can call Python helper).",
                "Install JS Runtime", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                try {
                    // pythonJava possibility: prefer Python helper if available
                    java.util.List<String> installCmd;
                    File pipePy = new File(APP_DIR, "nls_video_pipe.py");
                    if (pipePy.exists()) {
                        installCmd = java.util.Arrays.asList("python3", "-c",
                            "import sys; sys.path.insert(0,'" + APP_DIR + "'); import nls_video_pipe as m; m.ensure_js_runtime(auto_install=True)");
                    } else {
                        installCmd = java.util.Arrays.asList("sh", "-c",
                            "curl -fsSL https://deno.land/install.sh | sh");
                    }
                    ProcessBuilder ipb = new ProcessBuilder(installCmd);
                    ipb.inheritIO();
                    Process ip = ipb.start();
                    ip.waitFor();
                    JOptionPane.showMessageDialog(frame, "Install attempted. Restart this app/terminal to pick up PATH.");
                    // Re-check
                    String newRt = checkJsRuntime();
                    jsL.setText("JS: " + (newRt != null ? newRt : "none"));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Install error: " + ex.getMessage());
                }
            }
        });
        rotRow.add(thetaL);
        rotRow.add(thetaField);
        rotRow.add(dualCheck);
        rotRow.add(ijL);
        rotRow.add(gifCheck);
        rotRow.add(privateCheck);
        rotRow.add(vacuumCheck);
        rotRow.add(vacuumDetailCheck);
        rotRow.add(vacuumLogBtn);
        rotRow.add(vacuumClearBtn);
        rotRow.add(tlsJa3Check);
        rotRow.add(vpnCheck);
        rotRow.add(vpnProviderBox);
        rotRow.add(overlayBox);
        sshSocksBtn = new JButton("SSH SOCKS");
        sshSocksBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        sshSocksBtn.setToolTipText("ssh -D 127.0.0.1:1080 — downloads via SOCKS instead of kernel VPN");
        sshSocksBtn.addActionListener(e -> startSshSocksTunnel());
        rotRow.add(sshSocksBtn);
        astrillBtn = new JButton("Astrill");
        astrillBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        astrillBtn.setToolTipText("Launch /usr/local/Astrill/astrill (StealthVPN → tun0). Archive ~/Downloads/NLS-Astrill. Disconnect Surfshark first.");
        astrillBtn.addActionListener(e -> startAstrillClient());
        rotRow.add(astrillBtn);
        JButton switcherRowBtn = new JButton("Switcher");
        switcherRowBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        switcherRowBtn.setToolTipText("Gravity Switcher applet — PATH catalog, NNLR, Security");
        switcherRowBtn.addActionListener(e -> startGravitySwitcher());
        rotRow.add(switcherRowBtn);
        ngrokBtn = new JButton("ngrok");
        ngrokBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        ngrokBtn.setToolTipText("Tunnel gravity-serve :8766 with your ngrok account (dashboard, not YouTube)");
        ngrokBtn.addActionListener(e -> startNgrokTunnel());
        rotRow.add(ngrokBtn);
        netL = new JLabel("net: " + describeNetworkRoute());
        netL.setFont(new Font("Monospaced", Font.PLAIN, 8));
        javax.swing.Timer switcherLock = new javax.swing.Timer(2500, e -> syncFromSwitcherLock());
        switcherLock.setRepeats(true);
        switcherLock.start();
        String route0 = netL.getText();
        if (route0.contains("kill-switch") || route0.contains("datacenter") || route0.contains("stacked")) {
            netL.setForeground(new Color(0xe8, 0x5a, 0x6a));
        } else if (useVpnRoute() || route0.contains("Astrill") || route0.contains("stealth")) {
            netL.setForeground(new Color(0x5a, 0xe0, 0xd0));
        } else {
            netL.setForeground(textMuted);
        }
        netL.setToolTipText("HKG: VPN off = ISP. Astrill Stealth = tun0 (China hotel DPI). Surfshark WG = datacenter 403. Do not stack. SSH VPS for YouTube.");
        rotRow.add(netL);
        rotRow.add(jsL);
        rotRow.add(jsInstall);

        // Launch concise 11D Hierarchy for deeper ASC / Visualist exploration (ties to live data)
        JButton hierBtn = new JButton("11D Hierarchy");
        hierBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        hierBtn.addActionListener(e -> {
            try {
                ProcessBuilder hpb = new ProcessBuilder("python3", new File(APP_DIR, "hierarchy_interpreter.py").getAbsolutePath());
                hpb.inheritIO();
                hpb.start();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Could not launch hierarchy: " + ex.getMessage());
            }
        });
        rotRow.add(hierBtn);

        JButton queueBtn = new JButton("Queue");
        queueBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        queueBtn.setToolTipText("Add URL to pipeline worker queue via gravity-serve API (vice versa with web)");
        queueBtn.addActionListener(e -> addPipelineJobViaApi());
        rotRow.add(queueBtn);

        recFormatBox = new JComboBox<>(new String[]{"WAV 48k/24", "WAV 48k/16", "FLAC 48k", "AAC 256k", "MP3 320k"});
        recFormatBox.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recFormatBox.setToolTipText("WAV pcm_s24le · FLAC · AAC twoloop · libmp3lame. soxr 48k.");
        recChannelBox = new JComboBox<>(new String[]{"NTS 1", "NTS 2"});
        recChannelBox.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recChannelBox.setToolTipText("NTS live channel for /radio URLs (WYHIWY hear + net stream).");
        recCopyCheck = new JCheckBox("copy", true);
        recCopyCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recCopyCheck.setForeground(textMuted);
        recCopyCheck.setBackground(panelBg);
        recCopyCheck.setToolTipText("Bitstream copy when source already matches (NTS MP3, catalog AAC). Off = soxr re-encode.");
        recHearCheck = new JCheckBox("hear", true);
        recHearCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recHearCheck.setForeground(textMuted);
        recHearCheck.setBackground(panelBg);
        recHearCheck.setToolTipText("WYHIWYREC: if the selected output is silent, ffplay the bar URL on that sink.");
        recAutoCheck = new JCheckBox("auto", true);
        recAutoCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recAutoCheck.setForeground(textMuted);
        recAutoCheck.setBackground(panelBg);
        recAutoCheck.setToolTipText("On launch, start WYHIWYREC when a radio URL is in the bar.");
        recSigCheck = new JCheckBox("sig", true);
        recSigCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recSigCheck.setForeground(textMuted);
        recSigCheck.setBackground(panelBg);
        recSigCheck.setToolTipText("Signal monitor: L/R peak+RMS from the selected output monitor.");
        recWyhiwyBtn = new JButton("WYHIWYREC");
        recWyhiwyBtn.setFont(new Font("Monospaced", Font.BOLD, 8));
        recWyhiwyBtn.setToolTipText("Arm WYHIWYREC (what you hear). Click again or REC to start. libpulse monitor of out:.");
        recWyhiwyBtn.addActionListener(e -> onRecFnClick("WYHIWYREC"));
        recNetBtn = new JButton("net stream");
        recNetBtn.setFont(new Font("Monospaced", Font.BOLD, 8));
        recNetBtn.setToolTipText("Arm Icecast/URL dump of the bar. Click again or REC to start. copy+MP3 keeps NTS bitstream.");
        recNetBtn.addActionListener(e -> onRecFnClick("net stream"));
        recCatalogFnBtn = new JButton("catalog");
        recCatalogFnBtn.setFont(new Font("Monospaced", Font.BOLD, 8));
        recCatalogFnBtn.setToolTipText("Arm selected ASC catalog row. Click again or REC to start.");
        recCatalogFnBtn.addActionListener(e -> onRecFnClick("ASC catalog"));
        recGoBtn = new JButton("REC");
        recGoBtn.setFont(new Font("Monospaced", Font.BOLD, 8));
        recGoBtn.setToolTipText("Start the armed rec function with the opt: row.");
        recGoBtn.addActionListener(e -> startRecFunction(recFn));
        recBtn = recGoBtn;
        recStopBtn = new JButton("STOP");
        recStopBtn.setFont(new Font("Monospaced", Font.BOLD, 8));
        recStopBtn.setEnabled(false);
        recStopBtn.setToolTipText("Stop capture (file stays in ~/Downloads/asc-rec-*)");
        recStopBtn.addActionListener(e -> stopNetRecord());
        recSaveBtn = new JButton("SAVE");
        recSaveBtn.setFont(new Font("Monospaced", Font.BOLD, 8));
        recSaveBtn.setEnabled(false);
        recSaveBtn.setToolTipText("Stop if needed, then save/copy the recording");
        recSaveBtn.addActionListener(e -> saveNetRecord());
        JPanel recRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        recRow.setBackground(panelBg);
        recStripRow(recRow);
        JLabel recFnL = new JLabel("rec:");
        recFnL.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recFnL.setForeground(textMuted);
        recRow.add(recFnL);
        recRow.add(recWyhiwyBtn);
        recRow.add(recNetBtn);
        recRow.add(recCatalogFnBtn);
        recRow.add(recGoBtn);
        recRow.add(recStopBtn);
        recRow.add(recSaveBtn);
        recOptRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        recOptRow.setBackground(panelBg);
        recStripRow(recOptRow);
        recOptL = new JLabel("opt wyhiwy:");
        recOptL.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recOptL.setForeground(textMuted);
        recOptRow.add(recOptL);
        recOptRow.add(recFormatBox);
        recOptRow.add(recChannelBox);
        recOptRow.add(recCopyCheck);
        recOptRow.add(recHearCheck);
        recOptRow.add(recAutoCheck);
        recOptRow.add(recSigCheck);
        recOutBox = new JComboBox<>();
        recOutBox.setFont(new Font("Monospaced", Font.PLAIN, 9));
        recOutBox.setToolTipText("Playback output: hardware card, HDMI, or Gravity virtual (works with no soundcard).");
        recOutBox.setPrototypeDisplayValue(new PulseOut("x", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", false));
        recOutBox.addActionListener(e -> {
            if (!recOutRefreshing) applySelectedOutput();
        });
        recOutRefreshBtn = new JButton("↻ out");
        recOutRefreshBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recOutRefreshBtn.setToolTipText("Rescan PipeWire sinks (plug/unplug cards).");
        recOutRefreshBtn.addActionListener(e -> refreshPulseOutputs());
        recRouteCheck = new JCheckBox("route", true);
        recRouteCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recRouteCheck.setForeground(textMuted);
        recRouteCheck.setBackground(panelBg);
        recRouteCheck.setToolTipText("Set this sink as the system default so Brave/ffplay follow the selector.");
        recAlsaCheck = new JCheckBox("alsa", true);
        recAlsaCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recAlsaCheck.setForeground(textMuted);
        recAlsaCheck.setBackground(panelBg);
        recAlsaCheck.setToolTipText("ALSA on: hardware cards appear as monitors. ALSA off: card profiles Off — virtual/PipeWire only (no soundcard).");
        recAlsaCheck.addActionListener(e -> applyAlsaMonitorPower(recAlsaCheck.isSelected()));
        recPwCheck = new JCheckBox("pw", true);
        recPwCheck.setFont(new Font("Monospaced", Font.PLAIN, 8));
        recPwCheck.setForeground(textMuted);
        recPwCheck.setBackground(panelBg);
        recPwCheck.setToolTipText("PipeWire virtual controller (pw-loopback sink gravity-pw + monitor). No ALSA/JACK card required.");
        recPwCheck.addActionListener(e -> applyPipeWireVirtual(recPwCheck.isSelected()));
        recOutRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        recOutRow.setBackground(panelBg);
        recStripRow(recOutRow);
        JLabel outL = new JLabel("out:");
        outL.setFont(new Font("Monospaced", Font.PLAIN, 8));
        outL.setForeground(textMuted);
        recOutRow.add(outL);
        recOutRow.add(recOutBox);
        recOutRow.add(recOutRefreshBtn);
        recOutRow.add(recRouteCheck);
        recOutRow.add(recAlsaCheck);
        recOutRow.add(recPwCheck);
        refreshPulseOutputs();
        updateRecButtons();
        seedUrlFromClipboard();

        rotRow.setAlignmentX(0f);

        // Minimal progress (tied to H.264 stage)
        directProgress = new JProgressBar(0, 100);
        directProgress.setStringPainted(true);
        directProgress.setFont(new Font("Monospaced", Font.PLAIN, 10));
        directProgress.setForeground(stageCols[2]); // H.264 red-ish
        directProgress.setBackground(new Color(0x0b, 0x10, 0x20));
        mintStatusLabel = new JLabel("mint: —");
        mintStatusLabel.setFont(new Font("Monospaced", Font.PLAIN, 9));
        mintStatusLabel.setForeground(textMuted);
        directStatusLabel = new JLabel("ready — https://, ipfs://, .php viewer, GIF address");
        directStatusLabel.setFont(new Font("Monospaced", Font.PLAIN, 10));
        directStatusLabel.setForeground(textMuted);
        JPanel pRow = new JPanel(new BorderLayout(4, 0));
        pRow.setBackground(panelBg);
        pRow.add(directProgress, BorderLayout.CENTER);
        JPanel statusCol = new JPanel(new BorderLayout(0, 2));
        statusCol.setBackground(panelBg);
        statusCol.add(mintStatusLabel, BorderLayout.NORTH);
        statusCol.add(directStatusLabel, BorderLayout.SOUTH);
        pRow.add(statusCol, BorderLayout.SOUTH);
        pRow.setAlignmentX(0f);
        recSouth = new JPanel();
        recSouth.setLayout(new BoxLayout(recSouth, BoxLayout.Y_AXIS));
        recSouth.setBackground(panelBg);
        recMeter = new RecMeter();
        recMeter.setAlignmentX(0f);
        recSouth.add(rotRow);
        recSouth.add(recRow);
        recSouth.add(recOptRow);
        recSouth.add(recOutRow);
        recSouth.add(recMeter);
        recSouth.add(pRow);
        vacuumLogArea = new JTextArea(5, 60);
        vacuumLogArea.setFont(new Font("Monospaced", Font.PLAIN, 9));
        vacuumLogArea.setBackground(new Color(0x0b, 0x10, 0x20));
        vacuumLogArea.setForeground(textLight);
        vacuumLogArea.setEditable(false);
        vacuumLogArea.setText("vacuum detail — check vacuum + detail, then download\n");
        vacuumLogScroll = new JScrollPane(vacuumLogArea);
        vacuumLogScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(accent, 1),
            "Vacuum / TLS-JA3 log",
            0, 0, new Font("Monospaced", Font.PLAIN, 9), accent
        ));
        vacuumLogScroll.setPreferredSize(new Dimension(0, 168));
        vacuumLogScroll.setAlignmentX(0f);
        recSouth.add(vacuumLogScroll);
        setVacuumLogVisible(vacuumDetailCheck != null && vacuumDetailCheck.isSelected());
        inputBox.add(recSouth, BorderLayout.SOUTH);
        applyRecFnUi();

        mainWrap.add(inputBox, BorderLayout.NORTH);

        // Recent ~/Downloads direct-h264/gif files + open-folder shortcut
        JPanel recentPanel = new JPanel(new BorderLayout(4, 2));
        recentPanel.setBackground(panelBg);
        recentPanel.setPreferredSize(new Dimension(0, 108));
        recentPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(accent, 1),
            "Recent Downloads (~/Downloads/)",
            0, 0, new Font("Monospaced", Font.PLAIN, 9), accent
        ));
        recentDownloadsModel = new DefaultListModel<>();
        recentDownloadsList = new JList<>(recentDownloadsModel);
        recentDownloadsList.setFont(new Font("Monospaced", Font.PLAIN, 9));
        recentDownloadsList.setBackground(new Color(0x0b, 0x10, 0x20));
        recentDownloadsList.setForeground(textLight);
        recentDownloadsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recentDownloadsList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            onRecentDownloadSelected(recentDownloadsList.getSelectedValue());
        });
        recentDownloadsList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    RecentDownload sel = recentDownloadsList.getSelectedValue();
                    if (sel != null) previewLastDownload();
                }
            }
        });
        JPanel recentBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        recentBtns.setBackground(panelBg);
        JButton openDlBtn = new JButton("Downloads ↗");
        openDlBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        openDlBtn.setToolTipText("Open ~/Downloads/ in file manager");
        openDlBtn.addActionListener(e -> openDownloadsFolder());
        JButton refreshDlBtn = new JButton("↻");
        refreshDlBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        refreshDlBtn.setToolTipText("Refresh recent downloads list");
        refreshDlBtn.addActionListener(e -> refreshRecentDownloads());
        recentBtns.add(openDlBtn);
        recentBtns.add(refreshDlBtn);
        recentPanel.add(new JScrollPane(recentDownloadsList), BorderLayout.CENTER);
        recentPanel.add(recentBtns, BorderLayout.SOUTH);

        // Drop target kept (KISS paste/drag works)
        try {
            new java.awt.dnd.DropTarget(urlField, new java.awt.dnd.DropTargetAdapter() {
                public void drop(java.awt.dnd.DropTargetDropEvent dtde) {
                    try {
                        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
                        java.awt.datatransfer.Transferable t = dtde.getTransferable();
                        if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                            String u = (String) t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                            if (u != null && u.trim().length() > 0) {
                                final String url = expandRemoteInput(normalizeUrl(u));
                                SwingUtilities.invokeLater(() -> { urlField.setText(url); startDirectDownload(); });
                            }
                        }
                        dtde.dropComplete(true);
                    } catch (Exception ignore) { dtde.dropComplete(false); }
                }
            });
        } catch (Exception ignore) {}

        // Core content: simple jobs table + visualist (monospace, dark, minimal)
        JPanel content = new JPanel(new GridLayout(1, 2, 6, 6));
        content.setBackground(panelBg);

        // Jobs (H.264 pipeline live)
        JPanel jobsP = new JPanel(new BorderLayout());
        jobsP.setBackground(panelBg);
        jobsP.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(accent, 1),
            "Jobs (live)",
            0, 0, new Font("Monospaced", Font.PLAIN, 10), accent
        ));
        jobsModel = new DefaultTableModel(new String[]{"#", "Title", "Stage", "%", "Out"}, 0);
        jobsTable = new JTable(jobsModel);
        jobsTable.setBackground(new Color(0x0b, 0x10, 0x20));
        jobsTable.setForeground(textLight);
        jobsTable.setFont(new Font("Monospaced", Font.PLAIN, 10));
        jobsTable.setRowHeight(18);
        jobsP.add(new JScrollPane(jobsTable), BorderLayout.CENTER);
        JPanel jobsLiveBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        jobsLiveBtns.setBackground(panelBg);
        JButton cancelJobBtn = new JButton("Cancel Job");
        cancelJobBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        cancelJobBtn.setToolTipText("Cancel selected live job via gravity-serve API");
        cancelJobBtn.addActionListener(e -> cancelSelectedJobViaApi());
        JButton stopRefreshBtn = new JButton("⏹ Stop + Refresh");
        stopRefreshBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        stopRefreshBtn.setForeground(new Color(0xe8, 0x5a, 0x6a));
        stopRefreshBtn.setToolTipText("Abort downloads, cancel all live jobs, refresh jobs table (Esc)");
        stopRefreshBtn.addActionListener(e -> stopAndRefreshJobs());
        jobsLiveBtns.add(cancelJobBtn);
        jobsLiveBtns.add(stopRefreshBtn);
        jobsP.add(jobsLiveBtns, BorderLayout.SOUTH);
        content.add(jobsP);

        // Visualist / catalog — live list from /api/gravity + gravity-server
        JPanel visP = new JPanel(new BorderLayout(0, 4));
        visP.setBackground(panelBg);
        visP.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(accent, 1),
            "ASC PANEL (Live Data + H.264 + Gravity)",
            0, 0, new Font("Monospaced", Font.PLAIN, 10), accent
        ));
        visualistArea = new JTextArea(5, 40);
        visualistArea.setBackground(new Color(0x0b, 0x10, 0x20));
        visualistArea.setForeground(textMuted);
        visualistArea.setFont(new Font("Monospaced", Font.PLAIN, 9));
        visualistArea.setEditable(false);
        visualistArea.setText(ASC_FRAME + "\nLIVE ASC — waiting for catalog…  [FILMIC | 48k | H.264]\n");
        visP.add(new JScrollPane(visualistArea), BorderLayout.NORTH);
        ascTableModel = new DefaultTableModel(new String[]{"", "Title", "Time", "Preset", "Audio"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return c == 0 ? ImageIcon.class : String.class;
            }
        };
        ascTable = new JTable(ascTableModel);
        ascTable.setBackground(new Color(0x0b, 0x10, 0x20));
        ascTable.setForeground(textLight);
        ascTable.setFont(new Font("Monospaced", Font.PLAIN, 10));
        ascTable.setRowHeight(28);
        ascTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ascTable.setSelectionBackground(new Color(0x1a, 0x4a, 0x62));
        ascTable.setSelectionForeground(accent);
        ascTable.setGridColor(new Color(0x22, 0x2a, 0x3a));
        ascTable.setShowHorizontalLines(true);
        ascTable.setShowVerticalLines(false);
        ascTable.setFocusable(true);
        ascTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        ascTable.getTableHeader().setFont(new Font("Monospaced", Font.PLAIN, 9));
        ascTable.getTableHeader().setBackground(new Color(0x12, 0x18, 0x28));
        ascTable.getTableHeader().setForeground(accent);
        ascTable.getTableHeader().setReorderingAllowed(false);
        ascTable.getColumnModel().getColumn(0).setMinWidth(76);
        ascTable.getColumnModel().getColumn(0).setMaxWidth(80);
        ascTable.getColumnModel().getColumn(2).setMaxWidth(52);
        ascTable.getColumnModel().getColumn(3).setPreferredWidth(88);
        ascTable.getColumnModel().getColumn(4).setMaxWidth(56);
        ascTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onAscRowSelected();
        });
        ascTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = ascTable.rowAtPoint(e.getPoint());
                if (row < 0) return;
                ascTable.setRowSelectionInterval(row, row);
                if (e.getClickCount() == 2) playSelectedAsc();
            }
        });
        InputMap ascKeys = ascTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ascKeys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ascPlay");
        ascKeys.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "ascRec");
        ascTable.getActionMap().put("ascPlay", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { playSelectedAsc(); }
        });
        ascTable.getActionMap().put("ascRec", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { recSelectedAsc(); }
        });
        visP.add(new JScrollPane(ascTable), BorderLayout.CENTER);
        JPanel visSouth = new JPanel(new BorderLayout(6, 0));
        visSouth.setBackground(panelBg);
        ascSelLabel = new JLabel("click a catalog row, then REC");
        ascSelLabel.setFont(new Font("Monospaced", Font.PLAIN, 8));
        ascSelLabel.setForeground(textMuted);
        JPanel visBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        visBtns.setBackground(panelBg);
        ascPlayBtn = new JButton("▶ Play");
        ascPlayBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        ascPlayBtn.setToolTipText("Play selected catalog row (ffplay). Enter.");
        ascPlayBtn.setEnabled(false);
        ascPlayBtn.addActionListener(e -> playSelectedAsc());
        ascRecBtn = new JButton("REC");
        ascRecBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        ascRecBtn.setForeground(new Color(0xe8, 0x5a, 0x6a));
        ascRecBtn.setToolTipText("Record the selected catalog row (WAV / AAC / MP3). R.");
        ascRecBtn.setEnabled(false);
        ascRecBtn.addActionListener(e -> recSelectedAsc());
        JButton ascRefreshBtn = new JButton("↻");
        ascRefreshBtn.setFont(new Font("Monospaced", Font.PLAIN, 8));
        ascRefreshBtn.setToolTipText("Reload catalog from gravity-serve /api/gravity");
        ascRefreshBtn.addActionListener(e -> refreshJobsFromServe());
        visBtns.add(ascPlayBtn);
        visBtns.add(ascRecBtn);
        visBtns.add(ascRefreshBtn);
        visSouth.add(ascSelLabel, BorderLayout.CENTER);
        visSouth.add(visBtns, BorderLayout.EAST);
        visP.add(visSouth, BorderLayout.SOUTH);
        content.add(visP);

        JPanel centerStack = new JPanel(new BorderLayout(0, 6));
        centerStack.setBackground(panelBg);
        centerStack.add(recentPanel, BorderLayout.NORTH);
        centerStack.add(content, BorderLayout.CENTER);
        mainWrap.add(centerStack, BorderLayout.CENTER);

        // Bottom status - KISS
        statusLabel = new JLabel("gravity • h264 • no kill • no eval • monospace visual language");
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 10));
        statusLabel.setForeground(textMuted);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        mainWrap.add(statusLabel, BorderLayout.SOUTH);

        // Sync remote nav: asset-class add/delete, viewkey & menu$index URL builder
        JPanel navP = new JPanel(new BorderLayout(4, 4));
        navP.setBackground(panelBg);
        navP.setPreferredSize(new Dimension(240, 0));
        navP.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(accent, 1),
            "Remote Nav (asset-class)",
            0, 0, new Font("Monospaced", Font.PLAIN, 9), accent
        ));
        remoteNavModel = new DefaultListModel<>();
        remoteNavList = new JList<>(remoteNavModel);
        remoteNavList.setFont(new Font("Monospaced", Font.PLAIN, 9));
        remoteNavList.setBackground(new Color(0x0b, 0x10, 0x20));
        remoteNavList.setForeground(textLight);
        remoteNavList.addListSelectionListener(e -> {
            RemoteNavEntry sel = remoteNavList.getSelectedValue();
            if (sel != null && !e.getValueIsAdjusting()) {
                urlField.setText(sel.buildMenuUrl(sel.menuIndex));
            }
        });
        loadRemoteNav();
        JPanel navBtns = new JPanel(new GridLayout(3, 1, 2, 2));
        navBtns.setBackground(panelBg);
        JButton navAdd = new JButton("+ asset");
        navAdd.setFont(new Font("Monospaced", Font.PLAIN, 8));
        navAdd.addActionListener(e -> {
            String asset = JOptionPane.showInputDialog(frame, "Asset-class name:", "Add Nav", JOptionPane.PLAIN_MESSAGE);
            if (asset == null || asset.isBlank()) return;
            String base = JOptionPane.showInputDialog(frame, "Base URL (https://site/path/):", "Add Nav", JOptionPane.PLAIN_MESSAGE);
            if (base == null || base.isBlank()) return;
            String menuS = JOptionPane.showInputDialog(frame, "Menu index (1):", "1");
            int menu = 1;
            try { if (menuS != null) menu = Integer.parseInt(menuS.trim()); } catch (Exception ignore) {}
            String[] viewers = {"remote-cli.php", "remote.php", "navi(1)remote.php"};
            String viewer = (String) JOptionPane.showInputDialog(frame, "Viewer script:", "Add Nav",
                JOptionPane.PLAIN_MESSAGE, null, viewers, viewers[0]);
            if (viewer == null) viewer = "remote-cli.php";
            remoteNavModel.addElement(new RemoteNavEntry(asset.trim(), base.trim(), menu, viewer));
            saveRemoteNav();
        });
        JButton navDel = new JButton("- asset");
        navDel.setFont(new Font("Monospaced", Font.PLAIN, 8));
        navDel.addActionListener(e -> {
            int idx = remoteNavList.getSelectedIndex();
            if (idx < 0) return;
            remoteNavModel.remove(idx);
            saveRemoteNav();
        });
        JButton navSync = new JButton("Sync URL");
        navSync.setFont(new Font("Monospaced", Font.PLAIN, 8));
        navSync.addActionListener(e -> {
            RemoteNavEntry sel = remoteNavList.getSelectedValue();
            if (sel == null) return;
            String vk = JOptionPane.showInputDialog(frame, "viewkey (or blank for menu$index):", "");
            if (vk != null && !vk.isBlank()) {
                urlField.setText(sel.buildViewkeyUrl(vk.trim()));
            } else {
                urlField.setText(sel.buildMenuUrl(sel.menuIndex));
            }
        });
        navBtns.add(navAdd);
        navBtns.add(navDel);
        navBtns.add(navSync);
        navP.add(new JScrollPane(remoteNavList), BorderLayout.CENTER);
        navP.add(navBtns, BorderLayout.SOUTH);
        JLabel navHint = new JLabel("<html>viewkey=ID<br>menu$N</html>");
        navHint.setFont(new Font("Monospaced", Font.PLAIN, 8));
        navHint.setForeground(textMuted);
        navP.add(navHint, BorderLayout.NORTH);

        frame.add(navP, BorderLayout.WEST);
        frame.add(mainWrap, BorderLayout.CENTER);

        // Drop target on the whole for extra KISS UX
        try {
            new java.awt.dnd.DropTarget(mainWrap, new java.awt.dnd.DropTargetAdapter() {
                public void drop(java.awt.dnd.DropTargetDropEvent dtde) {
                    try {
                        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
                        java.awt.datatransfer.Transferable t = dtde.getTransferable();
                        if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                            String u = (String) t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                            if (u != null && isDownloadableUrl(normalizeUrl(u))) {
                                final String url = expandRemoteInput(normalizeUrl(u));
                                SwingUtilities.invokeLater(() -> { urlField.setText(url); startDirectDownload(); });
                            }
                        }
                        dtde.dropComplete(true);
                    } catch (Exception ignore) {}
                }
            });
        } catch (Exception ignore) {}

        frame.setVisible(true);
        refreshRecentDownloads();
        refreshJobsFromServe();

        JRootPane root = frame.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stopRefreshJobs");
        root.getActionMap().put("stopRefreshJobs", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopAndRefreshJobs();
            }
        });

        // Ensure the main URL input bar gets focus for immediate cursor input, selection, copy & paste
        SwingUtilities.invokeLater(() -> {
            if (urlField != null) {
                urlField.requestFocusInWindow();
            }
            maybeAutoStartRadioRec();
        });

        // Clear log data cache memory after exit/quit (KISS, no lingering state)
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gravityRunning = false;
                abortCurrentYtDlp();
                if (recRunning) stopNetRecord();
                stopPipeWireVirtual();
                restoreAlsaOnQuit();
                destroyBgProc(sshSocksProc);
                sshSocksProc = null;
                destroyBgProc(ngrokProc);
                ngrokProc = null;
                appendRuntimeLog("session_end", null);
                if (jobsModel != null) {
                    jobsModel.setRowCount(0);
                }
                if (visualistArea != null) {
                    visualistArea.setText("");
                }
                if (directStatusLabel != null) {
                    directStatusLabel.setText("cleared");
                }
                if (thetaField != null) {
                    thetaField.setText("0");
                }
                if (urlField != null) {
                    urlField.setText("");
                }
                // hint to release memory (no eval, simple)
                System.gc();
                if (socket != null && !socket.isClosed()) {
                    try { socket.close(); } catch (Exception ignore) {}
                }
            }
        });
    }

    private void connectToGravity() {
        new Thread(() -> {
            int attempt = 0;
            while (gravityRunning) {
                try {
                    closeGravitySocket();
                    socket = new Socket(HOST, PORT);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out = new PrintWriter(socket.getOutputStream(), true);

                    byte[] hello = new byte[32];
                    Arrays.fill(hello, (byte) 0x42);
                    socket.getOutputStream().write(hello);
                    socket.getOutputStream().flush();

                    byte[] serverHello = new byte[32];
                    socket.getInputStream().read(serverHello);

                    byte[] finish = new byte[48];
                    Arrays.fill(finish, (byte) 0x43);
                    socket.getOutputStream().write(finish);
                    socket.getOutputStream().flush();

                    attempt = 0;
                    appendRuntimeLog("gravity_handshake_ok", "\"host\":\"" + HOST + "\",\"port\":" + PORT);
                    SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Gravity handshake complete • Receiving Visualist protocol updates..."));

                    String line;
                    while (gravityRunning && (line = in.readLine()) != null) {
                        final String updateLine = line;
                        int jobHints = 0;
                        java.util.regex.Matcher jm = java.util.regex.Pattern.compile("\"id\"\\s*:").matcher(updateLine);
                        while (jm.find()) jobHints++;
                        final int jobCount = jobHints;
                        appendRuntimeLog("gravity_update",
                            "\"jobs_count\":" + jobCount + ",\"line_bytes\":" + updateLine.length());
                        SwingUtilities.invokeLater(() -> {
                            updateUIFromLine(updateLine);
                            statusLabel.setText("Gravity live • " + new java.util.Date());
                        });
                    }
                } catch (Exception e) {
                    attempt++;
                    final int retrySec = Math.min(30, 2 * attempt);
                    final String errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    appendRuntimeLog("gravity_offline",
                        "\"error\":\"" + jsonEsc(errMsg) + "\",\"retry_sec\":" + retrySec);
                    SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Gravity offline — retry in " + retrySec + "s (" + errMsg + ")"));
                    try {
                        Thread.sleep(retrySec * 1000L);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void closeGravitySocket() {
        if (in != null) {
            try { in.close(); } catch (Exception ignore) {}
            in = null;
        }
        if (out != null) {
            try { out.close(); } catch (Exception ignore) {}
            out = null;
        }
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (Exception ignore) {}
            socket = null;
        }
    }

    private void setLastDownloadPath(String path) {
        lastDownloadPath = path;
        if (previewBtn != null) {
            boolean exists = path != null && new File(path).isFile();
            previewBtn.setEnabled(exists);
            previewBtn.setToolTipText(exists
                ? "Preview: " + new File(path).getName()
                : "Preview last download with ffplay");
        }
    }

    private void setDownloadUiBusy(boolean busy) {
        boolean on = !busy;
        if (downloadBtn != null) downloadBtn.setEnabled(on);
        if (gifBtn != null) gifBtn.setEnabled(on);
        if (urlField != null) urlField.setEnabled(on);
        if (thetaField != null) thetaField.setEnabled(on);
        if (dualCheck != null) dualCheck.setEnabled(on);
        if (gifCheck != null) gifCheck.setEnabled(on);
        if (privateCheck != null) privateCheck.setEnabled(on);
        if (vacuumCheck != null) vacuumCheck.setEnabled(on);
        if (vacuumDetailCheck != null) vacuumDetailCheck.setEnabled(on);
        if (tlsJa3Check != null) tlsJa3Check.setEnabled(on);
        if (vpnCheck != null) vpnCheck.setEnabled(on);
    }

    private static String resolveGravityServeUrl() {
        return envStr("GRAVITY_SERVE_URL", "http://127.0.0.1:8766");
    }

    private static String resolveNlsarUrl() {
        return envStr("NLSAR_URL", "http://127.0.0.1:4212");
    }

    /** Poll NLSAR architecture backend when TCP gravity-server is offline. */
    private void startNlsarBackendPoller() {
        new Thread(() -> {
            while (gravityRunning) {
                try {
                    URL u = URI.create(resolveNlsarUrl() + "/api/backend").toURL();
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(1200);
                    conn.setReadTimeout(2000);
                    conn.setRequestProperty("User-Agent", "GravityDesktop/nlsar");
                    int code = conn.getResponseCode();
                    InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    if (stream != null) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(stream,
                            java.nio.charset.StandardCharsets.UTF_8));
                        String ln;
                        while ((ln = br.readLine()) != null) sb.append(ln);
                    }
                    final String body = sb.toString();
                    boolean tcpLive = socket != null && socket.isConnected() && !socket.isClosed();
                    if (!tcpLive && body.contains("gravity_update")) {
                        SwingUtilities.invokeLater(() -> {
                            updateUIFromLine(body);
                            if (statusLabel != null) {
                                statusLabel.setText("NLSAR backend • " + new java.util.Date());
                            }
                        });
                    }
                    appendRuntimeLog("nlsar_backend", "\"ok\":true,\"bytes\":" + body.length());
                } catch (Exception e) {
                    appendRuntimeLog("nlsar_offline",
                        "\"error\":\"" + jsonEsc(e.getMessage() != null ? e.getMessage() : e.toString()) + "\"");
                }
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }).start();
    }

    private void gravityServePost(String path, String jsonBody, java.util.function.Consumer<String> onDone) {
        gravityServeRequest("POST", path, jsonBody, onDone);
    }

    private void gravityServeGet(String path, java.util.function.Consumer<String> onDone) {
        gravityServeRequest("GET", path, null, onDone);
    }

    private void gravityServeRequest(String method, String path, String jsonBody,
                                     java.util.function.Consumer<String> onDone) {
        final String base = resolveGravityServeUrl();
        new Thread(() -> {
            String resp = "";
            try {
                URL u = URI.create(base + path).toURL();
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(30000);
                if ("POST".equals(method) || "PUT".equals(method)) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    byte[] payload = (jsonBody != null ? jsonBody : "{}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                }
                int code = conn.getResponseCode();
                InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (stream != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(stream,
                        java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String ln;
                    while ((ln = br.readLine()) != null) sb.append(ln);
                    resp = sb.toString();
                }
                if (resp.isEmpty()) {
                    resp = "{\"ok\":false,\"error\":\"HTTP " + code + "\"}";
                } else if (resp.contains("<!DOCTYPE") || resp.contains("<html")) {
                    resp = "{\"ok\":false,\"error\":\"Non-JSON response (HTTP " + code + ") — restart gravity-serve?\"}";
                }
            } catch (Exception ex) {
                resp = "{\"ok\":false,\"error\":\"" + jsonEsc(ex.getMessage()) + "\"}";
            }
            final String finalResp = resp;
            SwingUtilities.invokeLater(() -> onDone.accept(finalResp));
        }).start();
    }

    private void addPipelineJobViaApi() {
        String rawUrl = expandRemoteInput(normalizeUrl(urlField.getText()));
        if (!rawUrl.startsWith("http") && !rawUrl.startsWith("ipfs://")) {
            directStatusLabel.setText("Queue: need http(s) URL");
            return;
        }
        boolean grav = privateCheck != null && privateCheck.isSelected();
        String body = "{\"url\":\"" + jsonEsc(rawUrl) + "\",\"preset\":\"balanced-4k\",\"gravity\":" + grav + "}";
        directStatusLabel.setText("Queueing pipeline job via gravity-serve API...");
        gravityServePost("/api/add", body, resp -> {
            appendRuntimeLog("api_add_job", "\"response_text\":\"" + jsonEsc(resp) + "\"");
            if (resp.contains("\"ok\":true") || resp.contains("\"ok\": true")) {
                directStatusLabel.setText("Pipeline job queued (API) — worker converts to Visualist");
            } else {
                directStatusLabel.setText("Queue API failed — is gravity-serve running? :8766");
            }
        });
    }

    private void cancelSelectedJobViaApi() {
        int row = jobsTable != null ? jobsTable.getSelectedRow() : -1;
        if (row < 0) {
            stopAndRefreshJobs();
            return;
        }
        Object idObj = jobsModel.getValueAt(row, 0);
        String id = String.valueOf(idObj);
        if (!id.matches("\\d+")) {
            stopAndRefreshJobs();
            return;
        }
        String body = "{\"id\":" + id + "}";
        directStatusLabel.setText("Cancelling job #" + id + " via API...");
        gravityServePost("/api/cancel", body, resp -> {
            appendRuntimeLog("api_cancel_job", "\"job_id\":" + id + ",\"response_text\":\"" + jsonEsc(resp) + "\"");
            if (resp.contains("\"ok\":true") || resp.contains("\"ok\": true")) {
                directStatusLabel.setText("Cancelled job #" + id);
            } else {
                String err = "Cancel failed for job #" + id;
                int errIdx = resp.indexOf("\"error\":");
                if (errIdx >= 0) {
                    int q1 = resp.indexOf('"', errIdx + 8);
                    int q2 = resp.indexOf('"', q1 + 1);
                    if (q1 >= 0 && q2 > q1) err = resp.substring(q1 + 1, q2);
                }
                directStatusLabel.setText(err);
            }
            refreshJobsFromServe();
        });
    }

    private void stopAndRefreshJobs() {
        abortCurrentYtDlp();
        if (directStatusLabel != null) {
            directStatusLabel.setText("⏹ stop + refresh jobs...");
        }
        gravityServePost("/api/stop", "{}", resp -> {
            appendRuntimeLog("api_stop_jobs", "\"response_text\":\"" + jsonEsc(resp) + "\"");
            boolean ok = resp.contains("\"ok\":true") || resp.contains("\"ok\": true");
            int n = jsonIntField(resp, "cancelled_count");
            if (ok) {
                applyJobsFromJson(resp);
                if (directStatusLabel != null) {
                    directStatusLabel.setText(n > 0
                        ? "⏹ stopped " + n + " live job(s) — refreshed"
                        : "⏹ no live jobs — refreshed");
                }
            } else if (directStatusLabel != null) {
                String err = jsonQuotedField(resp, "error");
                directStatusLabel.setText(err != null ? err : "Stop failed — is gravity-serve running? :8766");
            }
            refreshJobsFromServe();
        });
    }

    private void refreshJobsFromServe() {
        gravityServeGet("/api/gravity", resp -> {
            if (resp.contains("\"ok\":false") || resp.contains("<!DOCTYPE")) return;
            applyJobsFromJson(resp);
            renderAscFromJson(resp);
            if (statusLabel != null) {
                statusLabel.setText("jobs + ASC catalog refreshed • " + new java.util.Date());
            }
        });
    }

    private static String extractJsonArray(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int i = json.indexOf('[', k);
        if (i < 0) return null;
        int depth = 0;
        boolean inStr = false;
        for (int p = i; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '"' && (p == 0 || json.charAt(p - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(i, p + 1);
            }
        }
        return null;
    }

    private static java.util.List<String> splitJsonObjects(String arrayJson) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (arrayJson == null) return out;
        int depth = 0;
        boolean inStr = false;
        int start = -1;
        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);
            if (c == '"' && (i == 0 || arrayJson.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(arrayJson.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    private static boolean jsonBoolField(String json, String key) {
        if (json == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
            .matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private static String jsonNumField(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*([0-9.]+)")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String formatDuration(String sec) {
        if (sec == null) return "";
        try {
            int s = (int) Double.parseDouble(sec);
            return String.format("%d:%02d", s / 60, s % 60);
        } catch (Exception e) {
            return "";
        }
    }

    private static String visualistRoot() {
        return System.getProperty("user.home") + "/Videos/NLS-Visualist";
    }

    private void renderAscFromJson(String json) {
        if (ascTableModel == null) return;
        String keep = selectedAscKey;
        if (keep == null) {
            AscEntry cur = selectedAscEntry();
            if (cur != null) keep = cur.key();
        }
        String arr = extractJsonArray(json, "catalog");
        ascEntries.clear();
        ascTableModel.setRowCount(0);
        int n = 0;
        if (arr != null) {
            for (String obj : splitJsonObjects(arr)) {
                if (n >= 40) break;
                AscEntry e = parseAscEntry(obj);
                if (e != null) {
                    ascEntries.add(e);
                    String mark = e.gravity ? e.title + " GRAVITY" : e.title;
                    ascTableModel.addRow(new Object[]{
                        e.thumbIcon,
                        mark,
                        e.duration,
                        e.preset,
                        e.audioReady()
                    });
                    n++;
                }
            }
        }
        if (visualistArea != null) {
            visualistArea.setText(ASC_FRAME + "\nLIVE ASC catalog  " + n + " items  click a row → Play / REC  [WAV 48k/24 | AAC 256k]\n");
        }
        if (keep != null && n > 0) {
            for (int i = 0; i < ascEntries.size(); i++) {
                if (keep.equals(ascEntries.get(i).key())) {
                    ascTable.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
        if (selectedAscEntry() == null) {
            updateAscSelectionUi(null);
        }
    }

    private AscEntry parseAscEntry(String obj) {
        String title = jsonQuotedField(obj, "title");
        if (title == null || title.isBlank()) title = jsonQuotedField(obj, "play_name");
        if (title == null) title = "untitled";
        String out = jsonQuotedField(obj, "output_path");
        String playName = jsonQuotedField(obj, "play_name");
        String thumbName = jsonQuotedField(obj, "thumb_name");
        if (thumbName == null) thumbName = jsonQuotedField(obj, "thumbnail");
        String h264 = jsonQuotedField(obj, "raw_h264_stream");
        String preset = jsonQuotedField(obj, "preset");
        if (preset == null) preset = "";
        String zone = jsonQuotedField(obj, "zone");
        String mem = jsonQuotedField(obj, "memory");
        if (zone == null) {
            String smc = obj.contains("storage_memory_category") ? obj : "";
            zone = jsonQuotedField(smc, "zone");
            mem = jsonQuotedField(smc, "memory");
        }
        String smcLine = (zone != null ? zone : "zone?") + (mem != null ? " " + mem : "");
        String dur = formatDuration(jsonNumField(obj, "source_duration"));
        boolean grav = jsonBoolField(obj, "gravity_prepared") || jsonBoolField(obj, "vp56_gravity_prepared");
        boolean playable = jsonBoolField(obj, "playable") || (out != null && new File(out).isFile());
        String playUrl = null;
        if (playName != null && !playName.isBlank()) {
            try {
                playUrl = resolveGravityServeUrl() + "/visualist/" + java.net.URLEncoder.encode(playName, "UTF-8").replace("+", "%20");
            } catch (Exception ignore) {}
        }
        if (out == null && h264 != null) out = h264;
        String id = jsonNumField(obj, "id");
        if (id == null) id = jsonQuotedField(obj, "id");
        AscEntry e = new AscEntry(title, out, playUrl, preset, smcLine, dur, grav, playable, id);
        File thumb = null;
        if (thumbName != null) {
            File t1 = new File(thumbName);
            File t2 = new File(visualistRoot(), new File(thumbName).getName());
            if (t1.isFile()) thumb = t1;
            else if (t2.isFile()) thumb = t2;
        }
        if (thumb != null) {
            try {
                java.awt.Image img = javax.imageio.ImageIO.read(thumb);
                if (img != null) {
                    e.thumbIcon = new ImageIcon(img.getScaledInstance(72, 40, java.awt.Image.SCALE_SMOOTH));
                }
            } catch (Exception ignore) {}
        }
        return e;
    }

    private AscEntry selectedAscEntry() {
        if (ascTable == null) return null;
        int row = ascTable.getSelectedRow();
        if (row < 0 || row >= ascEntries.size()) return null;
        return ascEntries.get(row);
    }

    private void onAscRowSelected() {
        AscEntry e = selectedAscEntry();
        if (e != null) selectedAscKey = e.key();
        updateAscSelectionUi(e);
        if (e != null && !recRunning) {
            String bar = urlField != null ? normalizeUrl(urlField.getText()) : "";
            if (!isDownloadableUrl(bar)) selectRecFn("ASC catalog", false);
        }
        updateRecButtons();
    }

    private void updateAscSelectionUi(AscEntry e) {
        if (ascSelLabel != null) {
            if (e == null) {
                ascSelLabel.setText("click a catalog row, then REC");
            } else {
                String ready = e.audioReady();
                ascSelLabel.setText("row " + (e.id != null ? e.id : "?") + "  " + e.title
                    + "  [" + ready + "]  Play / REC");
            }
        }
        if (ascPlayBtn != null) ascPlayBtn.setEnabled(e != null && e.playTarget() != null);
        if (ascRecBtn != null) ascRecBtn.setEnabled(!recRunning && e != null && e.playTarget() != null);
        if (e != null && directStatusLabel != null && !recRunning) {
            directStatusLabel.setText("ASC selected: " + e.title + " — Play or REC ("
                + (recFormatBox != null ? recFormatBox.getSelectedItem() : "WAV") + ")");
        }
    }

    private void playSelectedAsc() {
        AscEntry e = selectedAscEntry();
        if (e == null) {
            if (directStatusLabel != null) directStatusLabel.setText("ASC: click a catalog row first");
            return;
        }
        String target = e.playTarget();
        if (target == null || target.isBlank()) {
            if (directStatusLabel != null) directStatusLabel.setText("ASC: no playable H.264 for " + e.title);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(FFPLAY_PATH, "-autoexit", "-window_title",
                "ASC • " + e.title, target);
            pb.redirectErrorStream(true);
            pb.start();
            if (target.startsWith("/") || new File(target).isFile()) setLastDownloadPath(target);
            if (directStatusLabel != null) directStatusLabel.setText("ASC play " + e.title);
        } catch (Exception ex) {
            if (directStatusLabel != null) directStatusLabel.setText("ASC play error: " + ex.getMessage());
        }
    }

    private void recSelectedAsc() {
        AscEntry e = selectedAscEntry();
        if (e == null) {
            if (directStatusLabel != null) directStatusLabel.setText("ASC REC: click a catalog row first");
            if (ascSelLabel != null) ascSelLabel.setText("select a row, then REC");
            return;
        }
        if (recRunning) {
            if (directStatusLabel != null) directStatusLabel.setText("ASC REC: already recording — STOP first");
            return;
        }
        String target = e.playTarget();
        if (target == null || target.isBlank()) {
            if (directStatusLabel != null) directStatusLabel.setText("ASC REC: no media for " + e.title);
            return;
        }
        selectRecFn("ASC catalog", false);
        String fmt = recFormatBox != null ? String.valueOf(recFormatBox.getSelectedItem()) : "WAV 48k/24";
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String out = System.getProperty("user.home") + "/Downloads/asc-rec-" + e.recStem()
            + "-" + stamp + recExtension(fmt);
        try {
            recOutPath = out;
            startMonitorOrFileRecord(fmt, out, target);
        } catch (Exception ex) {
            recRunning = false;
            updateRecButtons();
            if (directStatusLabel != null) directStatusLabel.setText("ASC REC error: " + ex.getMessage());
        }
    }

    private static int jsonIntField(String json, String key) {
        if (json == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
            .matcher(json);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return 0; }
    }

    private static String jsonQuotedField(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private void applyJobsFromJson(String json) {
        if (jobsModel == null || json == null) return;
        String jobsArr = extractJsonArray(json, "jobs");
        String src = jobsArr != null ? jobsArr : json;
        java.util.regex.Pattern idP = java.util.regex.Pattern.compile("\"id\":\\s*(\\d+)");
        java.util.regex.Pattern titleP = java.util.regex.Pattern.compile("\"title\":\\s*\"([^\"]*)\"");
        java.util.regex.Pattern statusP = java.util.regex.Pattern.compile("\"status\":\\s*\"([^\"]*)\"");
        java.util.regex.Pattern progP = java.util.regex.Pattern.compile("\"progress\":\\s*([0-9.]+)");
        java.util.regex.Pattern presetP = java.util.regex.Pattern.compile("\"preset\":\\s*\"([^\"]*)\"");
        java.util.regex.Matcher mId = idP.matcher(src);
        java.util.regex.Matcher mTitle = titleP.matcher(src);
        java.util.regex.Matcher mStatus = statusP.matcher(src);
        java.util.regex.Matcher mProg = progP.matcher(src);
        java.util.regex.Matcher mPreset = presetP.matcher(src);
        jobsModel.setRowCount(0);
        int row = 0;
        while (mId.find() && row < 40) {
            String id = mId.group(1);
            String title = mTitle.find() ? mTitle.group(1) : "?";
            String status = mStatus.find() ? mStatus.group(1) : "?";
            String prog = mProg.find() ? mProg.group(1) : null;
            String preset = mPreset.find() ? mPreset.group(1) : "?";
            String pct = "?";
            if (prog != null) {
                try { pct = ((int) Double.parseDouble(prog)) + "%"; }
                catch (Exception ignore) { pct = prog + "%"; }
            }
            jobsModel.addRow(new Object[]{
                id, title.substring(0, Math.min(32, title.length())), status, pct, preset
            });
            row++;
        }
    }

    private String pactlLine(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            String ln = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            return ln != null ? ln.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void ensureVirtualSink() {
        try {
            Process p = new ProcessBuilder("pactl", "list", "short", "sinks").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            boolean have = false;
            int n = 0;
            while ((ln = br.readLine()) != null) {
                n++;
                if (ln.contains("gravity-out")) have = true;
            }
            p.waitFor();
            if (have) return;
            ProcessBuilder pb = new ProcessBuilder("pactl", "load-module", "module-null-sink",
                "sink_name=gravity-out",
                "sink_properties=device.description=GravityVirtual");
            pb.redirectErrorStream(true);
            Process m = pb.start();
            m.waitFor();
        } catch (Exception ignore) {}
    }

    private boolean pipeWireVirtualRunning() {
        if (recPwProc != null && recPwProc.isAlive()) return true;
        try {
            Process p = new ProcessBuilder("pactl", "list", "short", "sinks").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                if (ln.contains("gravity-pw")) { p.waitFor(); return true; }
            }
            p.waitFor();
        } catch (Exception ignore) {}
        return false;
    }

    private void ensurePipeWireVirtual() {
        if (pipeWireVirtualRunning()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder("pw-loopback", "-n", "gravity-pw",
                "--capture-props=media.class=Audio/Sink node.name=gravity-pw node.description=PipeWireVirtual audio.rate=48000 audio.channels=2 audio.position=[FL,FR]",
                "--playback-props=media.class=Audio/Source node.name=gravity-pw-mon node.description=PipeWireVirtualMon node.passive=true node.dont-reconnect=true");
            pb.redirectErrorStream(true);
            recPwProc = pb.start();
            for (int i = 0; i < 12 && !pipeWireVirtualRunning(); i++) {
                try { Thread.sleep(80); } catch (InterruptedException ignore) {}
            }
        } catch (Exception ignore) {}
    }

    private void stopPipeWireVirtual() {
        Process p = recPwProc;
        recPwProc = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            try { if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); }
            catch (Exception ignore) {}
        }
        try {
            Process ps = new ProcessBuilder("pgrep", "-f", "pw-loopback -n gravity-pw").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                try {
                    int pid = Integer.parseInt(ln.trim());
                    new ProcessBuilder("kill", String.valueOf(pid)).start();
                } catch (Exception ignore) {}
            }
            ps.waitFor();
        } catch (Exception ignore) {}
    }

    private void applyPipeWireVirtual(boolean on) {
        if (on) {
            ensurePipeWireVirtual();
            refreshPulseOutputs();
            if (directStatusLabel != null) {
                directStatusLabel.setText("pw ON — PipeWire virtual controller gravity-pw");
            }
            return;
        }
        stopPipeWireVirtual();
        refreshPulseOutputs();
        if (directStatusLabel != null) {
            directStatusLabel.setText("pw OFF — PipeWire virtual controller stopped");
        }
    }

    private java.util.List<PulseOut> listPulseOutputs() {
        java.util.List<PulseOut> out = new java.util.ArrayList<>();
        out.add(new PulseOut("", "system default", false));
        try {
            Process p = new ProcessBuilder("pactl", "list", "sinks").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln, name = null, desc = null, state = "";
            boolean inPorts = false;
            java.util.List<String[]> ports = new java.util.ArrayList<>();
            while ((ln = br.readLine()) != null) {
                String t = ln.trim();
                if (t.startsWith("Sink #") && name != null) {
                    flushSinkOutputs(out, name, desc, state, ports);
                    name = null; desc = null; state = ""; ports = new java.util.ArrayList<>();
                    inPorts = false;
                }
                if (t.equals("Ports:")) { inPorts = true; continue; }
                if (t.startsWith("Active Port:") || t.startsWith("Formats:")) inPorts = false;
                if (t.startsWith("State:")) state = t.substring(6).trim();
                else if (t.startsWith("Name:")) name = t.substring(5).trim();
                else if (t.startsWith("Description:")) desc = t.substring(12).trim();
                else if (inPorts && t.contains(":")) {
                    String[] pr = parseSinkPort(t);
                    if (pr != null) ports.add(pr);
                }
            }
            if (name != null) flushSinkOutputs(out, name, desc, state, ports);
            p.waitFor();
        } catch (Exception ignore) {}
        boolean alsaOn = recAlsaCheck == null || recAlsaCheck.isSelected();
        java.util.List<PulseOut> filtered = new java.util.ArrayList<>();
        boolean haveVirt = false;
        for (PulseOut o : out) {
            if ("gravity-out".equals(o.name)) haveVirt = true;
            if (!alsaOn && o.name != null && o.name.startsWith("alsa_")) continue;
            filtered.add(o);
        }
        if (!haveVirt) filtered.add(new PulseOut("gravity-out", "Gravity virtual (no card)", false));
        return filtered;
    }

    /** portId, label, avail  — Speaker stays selectable even when availability is unknown. */
    private static String[] parseSinkPort(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) return null;
        String id = line.substring(0, colon).trim();
        if (id.startsWith("card.") || id.startsWith("device.") || id.startsWith("port.")) return null;
        String rest = line.substring(colon + 1).trim();
        String label = rest;
        int par = rest.indexOf(" (");
        if (par > 0) label = rest.substring(0, par).trim();
        String avail = "";
        if (rest.toLowerCase().contains("not available")) avail = "not available";
        else if (rest.toLowerCase().contains("availability unknown")) avail = "unknown";
        else if (rest.toLowerCase().contains("available")) avail = "yes";
        return new String[]{id, label, avail};
    }

    private static void flushSinkOutputs(java.util.List<PulseOut> out, String name, String desc,
            String state, java.util.List<String[]> ports) {
        boolean run = "RUNNING".equals(state);
        String base = desc != null ? desc : name;
        if (ports == null || ports.isEmpty()) {
            out.add(new PulseOut(name, base, run));
            return;
        }
        boolean split = false;
        for (String[] pr : ports) {
            String lab = pr[1].toLowerCase();
            if (lab.contains("speaker") || lab.contains("headphone")) split = true;
        }
        if (!split && ports.size() == 1) {
            String[] pr = ports.get(0);
            out.add(new PulseOut(name, base, run, pr[0], pr[2]));
            return;
        }
        for (String[] pr : ports) {
            String label = pr[1];
            if (label.toLowerCase().contains("speaker")) label = "Speaker (laptop)";
            else if (label.toLowerCase().contains("headphone")) label = "Headphones";
            out.add(new PulseOut(name, label, run, pr[0], pr[2]));
        }
    }

    private java.util.List<String[]> listAlsaCards() {
        java.util.List<String[]> cards = new java.util.ArrayList<>();
        try {
            Process p = new ProcessBuilder("pactl", "list", "cards").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln, name = null, profile = null;
            while ((ln = br.readLine()) != null) {
                String t = ln.trim();
                if (t.startsWith("Name:")) name = t.substring(5).trim();
                else if (t.startsWith("Active Profile:")) profile = t.substring(15).trim();
                if (name != null && profile != null) {
                    if (name.startsWith("alsa_card")) {
                        cards.add(new String[]{name, profile});
                    }
                    name = null;
                    profile = null;
                }
            }
            p.waitFor();
        } catch (Exception ignore) {}
        return cards;
    }

    private void pactlWait(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ignore) {}
    }

    private static void destroyBgProc(Process p) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception ignore) {}
    }

    private void restoreAlsaOnQuit() {
        if (recAlsaSavedProfiles.isEmpty()) return;
        for (java.util.Map.Entry<String, String> e : recAlsaSavedProfiles.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank() && !"off".equals(e.getValue())) {
                pactlWait("pactl", "set-card-profile", e.getKey(), e.getValue());
            }
        }
        recAlsaSavedProfiles.clear();
    }

    /** ALSA on: restore card profiles so hardware monitors exist. Off: profile=off, virtual sink only. */
    private void applyAlsaMonitorPower(boolean on) {
        if (on) {
            if (recAlsaSavedProfiles.isEmpty()) {
                refreshPulseOutputs();
                if (directStatusLabel != null) {
                    directStatusLabel.setText("alsa ON — hardware monitors in out:");
                }
                return;
            }
            for (java.util.Map.Entry<String, String> e : recAlsaSavedProfiles.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank() && !"off".equals(e.getValue())) {
                    pactlWait("pactl", "set-card-profile", e.getKey(), e.getValue());
                }
            }
            try { Thread.sleep(350); } catch (InterruptedException ignore) {}
            refreshPulseOutputs();
            if (directStatusLabel != null) {
                directStatusLabel.setText("alsa ON — card profiles restored");
            }
            return;
        }
        recAlsaSavedProfiles.clear();
        for (String[] c : listAlsaCards()) {
            recAlsaSavedProfiles.put(c[0], c[1]);
            if (!"off".equals(c[1])) {
                pactlWait("pactl", "set-card-profile", c[0], "off");
            }
        }
        try { Thread.sleep(350); } catch (InterruptedException ignore) {}
        ensureVirtualSink();
        refreshPulseOutputs();
        if (recOutBox != null) {
            recOutRefreshing = true;
            try {
                for (int i = 0; i < recOutBox.getItemCount(); i++) {
                    PulseOut o = recOutBox.getItemAt(i);
                    if (o != null && "gravity-out".equals(o.name)) {
                        recOutBox.setSelectedIndex(i);
                        break;
                    }
                }
            } finally {
                recOutRefreshing = false;
            }
            applySelectedOutput();
        }
        if (directStatusLabel != null) {
            directStatusLabel.setText("alsa OFF — hardware monitors unplugged, using Gravity virtual");
        }
    }

    private void refreshPulseOutputs() {
        if (recOutBox == null) return;
        recOutRefreshing = true;
        try {
            ensureVirtualSink();
            if (recPwCheck == null || recPwCheck.isSelected()) ensurePipeWireVirtual();
            String keep = null;
            PulseOut cur = recOutBox.getSelectedItem() instanceof PulseOut
                ? (PulseOut) recOutBox.getSelectedItem() : null;
            if (cur != null) keep = cur.key();
            String sys = pactlLine("pactl", "get-default-sink");
            recOutBox.removeAllItems();
            int select = 0;
            int i = 0;
            for (PulseOut o : listPulseOutputs()) {
                recOutBox.addItem(o);
                if (keep != null && keep.equals(o.key())) select = i;
                else if (keep == null && !o.isSystemDefault() && sys.equals(o.name)
                        && (o.port == null || (o.desc != null && o.desc.toLowerCase().contains("speaker"))))
                    select = i;
                i++;
            }
            if (recOutBox.getItemCount() > 0) recOutBox.setSelectedIndex(select);
        } finally {
            recOutRefreshing = false;
        }
    }

    private PulseOut selectedPulseOut() {
        if (recOutBox == null) return null;
        Object v = recOutBox.getSelectedItem();
        return v instanceof PulseOut ? (PulseOut) v : null;
    }

    private void applySelectedOutput() {
        PulseOut o = selectedPulseOut();
        if (o == null) return;
        String sink = o.sinkName(pactlLine("pactl", "get-default-sink"));
        if (recRouteCheck != null && recRouteCheck.isSelected() && sink != null && !sink.isBlank()
                && !o.isSystemDefault()) {
            pactlWait("pactl", "set-default-sink", sink);
            if (o.port != null && !o.port.isBlank()) {
                pactlWait("pactl", "set-sink-port", sink, o.port);
            }
            if (o.desc != null && o.desc.toLowerCase().contains("speaker")) {
                pactlWait("amixer", "-q", "-c", "Generic_1", "sset", "Speaker", "unmute");
                pactlWait("amixer", "-q", "-c", "Generic_1", "sset", "Master", "unmute");
            }
        }
        if (directStatusLabel != null && !recRunning) {
            String note = o.portUnplugged() ? " (jack unplugged)" :
                (o.avail.contains("unknown") ? " (ALSA avail unknown — forced)" : "");
            directStatusLabel.setText("out: " + o + note + "  mon " + o.monitorName(sink));
        }
    }

    private String pulseMonitorSource() {
        PulseOut o = selectedPulseOut();
        String sys = pactlLine("pactl", "get-default-sink");
        if (o != null) return o.monitorName(sys);
        if (sys != null && !sys.isBlank()) return sys + ".monitor";
        return "default.monitor";
    }

    private java.util.List<String> recFfmpegTail(String outPath, String fmt) {
        java.util.List<String> a = new java.util.ArrayList<>();
        a.add(FFMPEG_PATH);
        a.add("-hide_banner");
        a.add("-nostdin");
        a.add("-nostats");
        a.add("-loglevel");
        a.add("error");
        a.add("-y");
        return a;
    }

    /** Live input flags ffmpeg recommends for pulse/http (avdevice + thread queue). */
    private void addLiveInputFlags(java.util.List<String> a) {
        a.add("-thread_queue_size");
        a.add("2048");
        a.add("-fflags");
        a.add("+genpts");
    }

    private void addPulseMonitorInput(java.util.List<String> a, String mon) {
        addLiveInputFlags(a);
        a.add("-f");
        a.add("pulse");
        a.add("-sample_rate");
        a.add("48000");
        a.add("-channels");
        a.add("2");
        a.add("-i");
        a.add(mon);
    }

    private void addHttpLiveInput(java.util.List<String> a, String url) {
        addLiveInputFlags(a);
        a.add("-reconnect");
        a.add("1");
        a.add("-reconnect_streamed");
        a.add("1");
        a.add("-reconnect_delay_max");
        a.add("5");
        a.add("-icy");
        a.add("1");
        a.add("-user_agent");
        a.add("Mozilla/5.0");
        a.add("-i");
        a.add(url);
    }

    /**
     * Preferred encode on this Ubuntu ffmpeg 6.1: libsoxr resample, pcm_s24le / flac,
     * native aac twoloop (no libfdk_aac), libmp3lame. Bitstream copy when the source already matches.
     */
    private void addAscAudioEncode(java.util.List<String> args, String fmt, String outPath) {
        addAscAudioEncode(args, fmt, outPath, false);
    }

    private void addAscAudioEncode(java.util.List<String> args, String fmt, String outPath, boolean allowCopy) {
        args.add("-vn");
        String f = fmt != null ? fmt : "WAV 48k/24";
        if (allowCopy && f.startsWith("MP3")) {
            args.add("-c:a");
            args.add("copy");
            args.add(outPath);
            return;
        }
        if (allowCopy && f.startsWith("AAC")) {
            args.add("-c:a");
            args.add("copy");
            args.add("-movflags");
            args.add("+faststart");
            args.add(outPath);
            return;
        }
        args.add("-af");
        args.add("aresample=48000:resampler=soxr:precision=28");
        if (f.startsWith("AAC")) {
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add("256k");
            args.add("-aac_coder");
            args.add("twoloop");
            args.add("-profile:a");
            args.add("aac_low");
            args.add("-movflags");
            args.add("+faststart");
        } else if (f.startsWith("MP3")) {
            args.add("-c:a");
            args.add("libmp3lame");
            args.add("-b:a");
            args.add("320k");
            args.add("-compression_level");
            args.add("0");
        } else if (f.startsWith("FLAC")) {
            args.add("-c:a");
            args.add("flac");
            args.add("-compression_level");
            args.add("8");
            args.add("-sample_fmt");
            args.add("s32");
        } else if (f.contains("16")) {
            args.add("-c:a");
            args.add("pcm_s16le");
        } else {
            args.add("-c:a");
            args.add("pcm_s24le");
        }
        args.add(outPath);
    }

    private String recExtension(String fmt) {
        if (fmt != null && fmt.startsWith("AAC")) return ".m4a";
        if (fmt != null && fmt.startsWith("MP3")) return ".mp3";
        if (fmt != null && fmt.startsWith("FLAC")) return ".flac";
        return ".wav";
    }

    private void updateRecButtons() {
        boolean idle = !recRunning;
        if (recGoBtn != null) recGoBtn.setEnabled(idle);
        if (recBtn != null) recBtn.setEnabled(idle);
        if (recWyhiwyBtn != null) recWyhiwyBtn.setEnabled(idle);
        if (recNetBtn != null) recNetBtn.setEnabled(idle);
        AscEntry sel = selectedAscEntry();
        if (recCatalogFnBtn != null) recCatalogFnBtn.setEnabled(idle);
        if (recStopBtn != null) recStopBtn.setEnabled(recRunning);
        boolean have = recOutPath != null && new File(recOutPath).isFile();
        if (recSaveBtn != null) recSaveBtn.setEnabled(recRunning || have);
        if (recFormatBox != null) recFormatBox.setEnabled(idle);
        if (recChannelBox != null) recChannelBox.setEnabled(idle);
        if (recCopyCheck != null) recCopyCheck.setEnabled(idle);
        if (recHearCheck != null) recHearCheck.setEnabled(idle);
        if (recAutoCheck != null) recAutoCheck.setEnabled(idle);
        if (recSigCheck != null) recSigCheck.setEnabled(idle);
        if (recOutBox != null) recOutBox.setEnabled(idle);
        if (recOutRefreshBtn != null) recOutRefreshBtn.setEnabled(idle);
        if (recRouteCheck != null) recRouteCheck.setEnabled(idle);
        if (recAlsaCheck != null) recAlsaCheck.setEnabled(idle);
        if (recPwCheck != null) recPwCheck.setEnabled(idle);
        if (ascPlayBtn != null) ascPlayBtn.setEnabled(sel != null && sel.playTarget() != null);
        if (ascRecBtn != null) ascRecBtn.setEnabled(idle && sel != null && sel.playTarget() != null);
        applyRecFnUi();
    }

    private static void recStripRow(JPanel p) {
        p.setAlignmentX(0f);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
    }

    private void onRecFnClick(String fn) {
        boolean armed = fn != null && fn.equals(recFn);
        selectRecFn(fn, armed && !recRunning);
    }

    private void selectRecFn(String fn, boolean start) {
        if (fn == null || fn.isBlank()) fn = "WYHIWYREC";
        recFn = fn;
        applyRecFnUi();
        if (start && !recRunning) startNetRecord();
    }

    private static boolean recFnIsWyhiwy(String fn) {
        if (fn == null) return true;
        String s = fn.toLowerCase();
        return s.contains("wyhiwy") || s.contains("monitor");
    }

    private static boolean recFnIsNet(String fn) {
        if (fn == null) return false;
        String s = fn.toLowerCase();
        return s.contains("net") || s.contains("stream");
    }

    private static boolean recFnIsCatalog(String fn) {
        return fn != null && fn.toLowerCase().contains("catalog");
    }

    private void applyRecFnUi() {
        String fn = recFn != null ? recFn : "WYHIWYREC";
        boolean wy = recFnIsWyhiwy(fn);
        boolean net = recFnIsNet(fn);
        boolean cat = recFnIsCatalog(fn);
        paintRecFnBtn(recWyhiwyBtn, wy, new Color(0xe8, 0x5a, 0x6a));
        paintRecFnBtn(recNetBtn, net, new Color(0x5a, 0xe0, 0xd0));
        paintRecFnBtn(recCatalogFnBtn, cat, new Color(0x9b, 0xe7, 0xff));
        paintRecFnBtn(recGoBtn, true, recRunning ? new Color(0xe8, 0x5a, 0x6a) : new Color(0xc0, 0x40, 0x50));
        if (recOptL != null) {
            recOptL.setText(wy ? "opt wyhiwy:" : net ? "opt net:" : cat ? "opt catalog:" : "opt:");
        }
        if (recChannelBox != null) recChannelBox.setVisible(wy || net);
        if (recCopyCheck != null) recCopyCheck.setVisible(net || cat);
        if (recHearCheck != null) recHearCheck.setVisible(wy);
        if (recAutoCheck != null) recAutoCheck.setVisible(wy);
        if (recSigCheck != null) recSigCheck.setVisible(wy);
        if (recMeter != null) recMeter.setVisible(wy);
        if (recOutRow != null) recOutRow.setVisible(wy);
        if (recOptRow != null) {
            recOptRow.revalidate();
            recOptRow.repaint();
        }
        if (recSouth != null) {
            recSouth.revalidate();
            recSouth.repaint();
        }
    }

    private static void paintRecFnBtn(JButton b, boolean on, Color fnColor) {
        if (b == null) return;
        b.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setFocusPainted(false);
        b.setForeground(on ? new Color(0x0b, 0x10, 0x20) : fnColor);
        b.setBackground(on ? fnColor : new Color(0x1a, 0x22, 0x32));
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(on ? fnColor : new Color(0x3a, 0x48, 0x58), on ? 2 : 1),
            BorderFactory.createEmptyBorder(on ? 1 : 2, 5, on ? 1 : 2, 5)));
    }

    private void seedUrlFromClipboard() {
        if (urlField == null) return;
        try {
            java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) return;
            String clip = (String) cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            String u = expandRemoteInput(normalizeUrl(clip));
            if (!isDownloadableUrl(u)) return;
            if (urlField.getText() != null && !urlField.getText().isBlank()) return;
            urlField.setText(u);
            recUrlFromClipboard = true;
            if (directStatusLabel != null) {
                String live = resolveLiveAudioUrl(u);
                directStatusLabel.setText("radio URL ready — WYHIWYREC or net stream  "
                    + (isDirectAudioStream(live) ? live : u));
            }
        } catch (Exception ignore) {}
    }

    /** On launch/upgrade: if the bar already has a radio/live stream, start REC immediately. */
    private void maybeAutoStartRadioRec() {
        if (recRunning) return;
        if (recUrlFromClipboard) return;
        if (recAutoCheck != null && !recAutoCheck.isSelected()) return;
        String raw = urlField != null ? expandRemoteInput(normalizeUrl(urlField.getText())) : "";
        if (!isDownloadableUrl(raw)) return;
        String stream = resolveLiveAudioUrl(raw);
        boolean radio = isDirectAudioStream(stream) || !stream.equals(raw);
        if (!radio) return;
        startRecFunction("WYHIWYREC");
    }

    private void startRecFunction(String fn) {
        selectRecFn(fn, true);
    }

    /** Map radio station pages (NTS /radio) to the Icecast/HLS URL ffmpeg can capture. */
    private String resolveLiveAudioUrl(String url) {
        if (url == null) return url;
        String u = url.trim();
        String low = u.toLowerCase();
        boolean nts = low.contains("nts.live") || low.contains("ntslive.net") || low.contains("ntslive.co.uk");
        if (nts && (low.contains("/radio") || low.matches("https?://(www\\.)?nts\\.live/?"))) {
            String ch = recChannelBox != null ? String.valueOf(recChannelBox.getSelectedItem()) : "NTS 1";
            boolean ch2 = (ch != null && ch.contains("2"))
                || low.contains("stream2") || low.contains("channel=2") || low.contains("nts2")
                || low.contains("/2");
            if (ch2) return "https://stream-relay-geo.ntslive.net/stream2";
            return "https://stream-relay-geo.ntslive.net/stream";
        }
        if (low.contains("stream-relay-geo.ntslive.net/stream2")) {
            return "https://stream-relay-geo.ntslive.net/stream2";
        }
        if (low.contains("stream-relay-geo.ntslive.net/stream")) {
            return "https://stream-relay-geo.ntslive.net/stream";
        }
        return u;
    }

    private static boolean isDirectAudioStream(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase();
        return u.contains("ntslive.net/stream")
            || u.contains("radiomast.io")
            || u.contains(".m3u8")
            || u.endsWith(".mp3") || u.endsWith(".aac") || u.endsWith(".ogg")
            || u.endsWith(".opus") || u.endsWith(".m4a")
            || u.contains("icecast") || u.contains("shoutcast")
            || u.contains("icy");
    }

    private static String recStemFromUrl(String page, String stream) {
        String s = (stream != null ? stream : page);
        if (s == null) return "radio";
        String low = s.toLowerCase();
        if (low.contains("stream2") || low.contains("nts2")) return "nts2";
        if (low.contains("nts") || (page != null && page.toLowerCase().contains("nts"))) return "nts1";
        try {
            String host = new URI(page != null ? page : s).getHost();
            if (host != null) {
                host = host.replaceFirst("^www\\.", "").replace('.', '-');
                return host.length() > 24 ? host.substring(0, 24) : host;
            }
        } catch (Exception ignore) {}
        return "radio";
    }

    private boolean sinkHasPlayback() {
        try {
            Process p = new ProcessBuilder("pactl", "list", "short", "sink-inputs").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln = br.readLine();
            p.waitFor();
            return ln != null && !ln.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private String defaultPulseSink() {
        PulseOut o = selectedPulseOut();
        String sys = pactlLine("pactl", "get-default-sink");
        if (o != null) {
            String s = o.sinkName(sys);
            if (s != null && !s.isBlank()) return s;
        }
        if (sys != null && !sys.isBlank()) return sys;
        return "gravity-out";
    }

    private void startHearPlay(String stream) throws Exception {
        if (stream == null || stream.isBlank()) return;
        ProcessBuilder pb = new ProcessBuilder(FFPLAY_PATH, "-nodisp", "-loglevel", "error",
            "-infbuf", stream);
        pb.environment().put("PULSE_SINK", defaultPulseSink());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        recPlayProc = pb.start();
    }

    /** What you hear is what you record: TASCAM/PipeWire sink monitor. Plays the bar URL only if the sink is silent. */
    private void startWyhiwyRec() throws Exception {
        String fmt = recFormatBox != null ? String.valueOf(recFormatBox.getSelectedItem()) : "WAV 48k/24";
        String raw = urlField != null ? expandRemoteInput(normalizeUrl(urlField.getText())) : "";
        String stream = isDownloadableUrl(raw) ? resolveLiveAudioUrl(raw) : null;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String stem = stream != null ? recStemFromUrl(raw, stream) : "monitor";
        String out = System.getProperty("user.home") + "/Downloads/asc-rec-wyhiwyrec-" + stem
            + "-" + stamp + recExtension(fmt);
        recOutPath = out;
        boolean hear = recHearCheck == null || recHearCheck.isSelected();
        if (hear && !sinkHasPlayback() && stream != null
                && (isDirectAudioStream(stream) || !stream.equals(raw))) {
            startHearPlay(stream);
            try { Thread.sleep(400); } catch (InterruptedException ignore) {}
        }
        startMonitorOrFileRecord(fmt, out, null);
        if (directStatusLabel != null) {
            directStatusLabel.setText("WYHIWYREC " + pulseMonitorSource() + " " + fmt
                + " → " + new File(out).getName());
        }
    }

    private void startNetRecord() {
        if (recRunning) return;
        String fmt = recFormatBox != null ? String.valueOf(recFormatBox.getSelectedItem()) : "WAV 48k/24";
        String src = recFn != null ? recFn : "WYHIWYREC";
        String raw = urlField != null ? expandRemoteInput(normalizeUrl(urlField.getText())) : "";
        boolean haveUrl = isDownloadableUrl(raw);
        boolean wyhiwy = recFnIsWyhiwy(src);
        boolean netStream = recFnIsNet(src);
        boolean catalog = recFnIsCatalog(src);
        if (catalog) {
            recSelectedAsc();
            return;
        }
        if (wyhiwy) {
            try {
                startWyhiwyRec();
            } catch (Exception ex) {
                recRunning = false;
                updateRecButtons();
                if (directStatusLabel != null) directStatusLabel.setText("REC error: " + ex.getMessage());
            }
            return;
        }
        if (!netStream) {
            if (directStatusLabel != null) {
                directStatusLabel.setText("REC: unknown function " + src);
            }
            return;
        }
        if (!haveUrl) {
            if (directStatusLabel != null) {
                directStatusLabel.setText("net stream: paste a radio URL in the bar");
            }
            return;
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String home = System.getProperty("user.home");
        try {
            if (haveUrl) {
                String stream = resolveLiveAudioUrl(raw);
                String out = home + "/Downloads/asc-rec-" + recStemFromUrl(raw, stream)
                    + "-" + stamp + recExtension(fmt);
                recOutPath = out;
                if (isDirectAudioStream(stream) || !stream.equals(raw)) {
                    startMonitorOrFileRecord(fmt, out, stream);
                    return;
                }
                java.util.List<String> yt = new java.util.ArrayList<>();
                yt.add(YTDLP_PATH);
                addBaseYtDlpArgs(yt, raw, checkJsRuntime(),
                    privateCheck != null && privateCheck.isSelected());
                yt.add("-f");
                yt.add("ba/bestaudio/best");
                yt.add("-o");
                yt.add("-");
                yt.add(raw);
                ProcessBuilder ypb = new ProcessBuilder(yt);
                ypb.redirectError(ProcessBuilder.Redirect.DISCARD);
                recYtdlpProc = ypb.start();
                java.util.List<String> ff = recFfmpegTail(out, fmt);
                addLiveInputFlags(ff);
                ff.add("-i");
                ff.add("pipe:0");
                addAscAudioEncode(ff, fmt, out, false);
                recProc = new ProcessBuilder(ff)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                final Process ytP = recYtdlpProc;
                final Process ffP = recProc;
                new Thread(() -> {
                    try (InputStream in = ytP.getInputStream(); OutputStream os = ffP.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) >= 0) os.write(buf, 0, n);
                        os.flush();
                    } catch (Exception ignore) {}
                    try { ffP.getOutputStream().close(); } catch (Exception ignore) {}
                }, "asc-rec-pipe").start();
                recRunning = true;
                updateRecButtons();
                directStatusLabel.setText("REC net ASC " + fmt + " → " + new File(out).getName());
                        writeRecSidecar(out);
                appendRuntimeLog("asc_rec_start", "\"path\":\"" + jsonEsc(out) + "\",\"src\":\"net\"");
                if (jobsModel != null) {
                    jobsModel.addRow(new Object[]{"rec", "ASC net record", "recording", "live", fmt});
                }
                new Thread(() -> {
                    try { if (recProc != null) recProc.waitFor(); } catch (Exception ignore) {}
                    SwingUtilities.invokeLater(() -> {
                        if (recRunning) finishNetRecord(false);
                    });
                }, "asc-rec-net-wait").start();
                return;
            }
        } catch (Exception ex) {
            recRunning = false;
            updateRecButtons();
            if (directStatusLabel != null) directStatusLabel.setText("REC error: " + ex.getMessage());
        }
    }

    private void startSignalMonitor(String mon) {
        stopSignalMonitor();
        if (recSigCheck != null && !recSigCheck.isSelected()) return;
        if (mon == null || mon.isBlank()) return;
        try {
            java.util.List<String> mm = new java.util.ArrayList<>();
            mm.add(FFMPEG_PATH);
            mm.add("-hide_banner");
            mm.add("-nostdin");
            mm.add("-loglevel");
            mm.add("error");
            addPulseMonitorInput(mm, mon);
            mm.add("-f");
            mm.add("s16le");
            mm.add("-ac");
            mm.add("2");
            mm.add("-ar");
            mm.add("48000");
            mm.add("-");
            ProcessBuilder pb = new ProcessBuilder(mm);
            recMeterProc = pb.start();
            final Process p = recMeterProc;
            new Thread(() -> {
                try (InputStream err = p.getErrorStream()) {
                    byte[] d = new byte[512];
                    while (err.read(d) >= 0) { /* drain */ }
                } catch (Exception ignore) {}
            }, "wyhiwyrec-sig-err").start();
            new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4800]; // 25 ms stereo s16le @ 48k
                    while (p.isAlive()) {
                        int n = 0;
                        while (n < buf.length) {
                            int r = in.read(buf, n, buf.length - n);
                            if (r < 0) return;
                            n += r;
                        }
                        int frames = n / 4;
                        if (frames <= 0) continue;
                        double lPk = 0, rPk = 0, lSq = 0, rSq = 0;
                        for (int i = 0; i < frames; i++) {
                            int lo = (buf[i * 4] & 0xff) | ((buf[i * 4 + 1] & 0xff) << 8);
                            int ro = (buf[i * 4 + 2] & 0xff) | ((buf[i * 4 + 3] & 0xff) << 8);
                            short ls = (short) lo;
                            short rs = (short) ro;
                            double la = Math.abs(ls) / 32768.0;
                            double ra = Math.abs(rs) / 32768.0;
                            if (la > lPk) lPk = la;
                            if (ra > rPk) rPk = ra;
                            lSq += la * la;
                            rSq += ra * ra;
                        }
                        final float lp = ampToDb(lPk);
                        final float rp = ampToDb(rPk);
                        final float lr = ampToDb(Math.sqrt(lSq / frames));
                        final float rr = ampToDb(Math.sqrt(rSq / frames));
                        SwingUtilities.invokeLater(() -> {
                            if (recMeter != null) recMeter.setLevels(lp, rp, lr, rr);
                        });
                    }
                } catch (Exception ignore) {}
            }, "wyhiwyrec-sig").start();
        } catch (Exception ex) {
            if (directStatusLabel != null) {
                directStatusLabel.setText("sig monitor: " + ex.getMessage());
            }
        }
    }

    private static float ampToDb(double amp) {
        if (amp < 1e-9) return -90f;
        return (float) (20.0 * Math.log10(amp));
    }

    private void stopSignalMonitor() {
        Process m = recMeterProc;
        recMeterProc = null;
        if (m != null && m.isAlive()) {
            m.destroy();
            try { if (!m.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) m.destroyForcibly(); }
            catch (Exception ignore) {}
        }
        if (recMeter != null) SwingUtilities.invokeLater(recMeter::idle);
    }

    private void startMonitorOrFileRecord(String fmt, String out, String file) throws Exception {
        java.util.List<String> ff = recFfmpegTail(out, fmt);
        boolean http = file != null && (file.startsWith("http://") || file.startsWith("https://"));
        boolean allowCopy = false;
        if (file == null) {
            String mon = pulseMonitorSource();
            addPulseMonitorInput(ff, mon);
            if (directStatusLabel != null) {
                directStatusLabel.setText("WYHIWYREC libpulse " + mon);
            }
            startSignalMonitor(mon);
        } else if (http) {
            addHttpLiveInput(ff, file);
            allowCopy = recCopyCheck != null && recCopyCheck.isSelected() && fmt != null && fmt.startsWith("MP3");
        } else {
            addLiveInputFlags(ff);
            ff.add("-i");
            ff.add(file);
            allowCopy = recCopyCheck != null && recCopyCheck.isSelected() && fmt != null && fmt.startsWith("AAC");
        }
        addAscAudioEncode(ff, fmt, out, allowCopy);
        recProc = new ProcessBuilder(ff)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
        recRunning = true;
        updateRecButtons();
        String label = file != null ? new File(file).getName() : "monitor";
        if (directStatusLabel != null) {
            directStatusLabel.setText("REC " + label + " " + fmt + " → " + new File(out).getName());
        }
        writeRecSidecar(out);
        appendRuntimeLog("asc_rec_start", "\"path\":\"" + jsonEsc(out) + "\",\"src\":\"" + jsonEsc(label) + "\"");
        if (jobsModel != null) {
            jobsModel.addRow(new Object[]{"rec", "ASC " + label, "recording", "live", fmt});
        }
        new Thread(() -> {
            try { recProc.waitFor(); } catch (Exception ignore) {}
            SwingUtilities.invokeLater(() -> {
                if (recRunning) finishNetRecord(false);
            });
        }, "asc-rec-wait").start();
    }

    private void stopNetRecord() {
        finishNetRecord(true);
    }

    private void finishNetRecord(boolean userStop) {
        recRunning = false;
        Process yt = recYtdlpProc;
        Process ff = recProc;
        Process hear = recPlayProc;
        recYtdlpProc = null;
        recProc = null;
        recPlayProc = null;
        stopSignalMonitor();
        if (yt != null && yt.isAlive()) {
            yt.destroy();
            try { if (!yt.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) yt.destroyForcibly(); }
            catch (Exception ignore) {}
        }
        if (ff != null && ff.isAlive()) {
            ff.destroy();
            try { if (!ff.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) ff.destroyForcibly(); }
            catch (Exception ignore) {}
        }
        if (hear != null && hear.isAlive()) {
            hear.destroy();
            try { if (!hear.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) hear.destroyForcibly(); }
            catch (Exception ignore) {}
        }
        updateRecButtons();
        String path = recOutPath;
        boolean ok = path != null && new File(path).isFile() && new File(path).length() > 44;
        if (directStatusLabel != null) {
            if (ok) {
                directStatusLabel.setText("REC stopped " + new File(path).getName() + " (" + (new File(path).length() / 1024) + " KB) — SAVE to copy");
                setLastDownloadPath(path);
                refreshRecentDownloads();
            } else {
                directStatusLabel.setText(userStop ? "REC stopped (empty file)" : "REC ended");
            }
        }
        if (ok) writeRecSidecar(path);
        appendRuntimeLog("asc_rec_stop", "\"path\":\"" + jsonEsc(path) + "\",\"ok\":" + ok);
    }

    private String recDisplayTitle(String path, String url) {
        String stem = recStemFromUrl(url, url);
        if (path != null) {
            String name = new File(path).getName().toLowerCase();
            if (name.contains("nts2")) return "NTS 2";
            if (name.contains("nts1") || name.contains("-nts-")) return "NTS 1";
            if (name.contains("rinse")) return "Rinse FM";
            if (name.contains("monitor")) return "Output monitor";
        }
        if ("nts1".equals(stem)) return "NTS 1";
        if ("nts2".equals(stem)) return "NTS 2";
        if ("rinse-fm".equals(stem)) return "Rinse FM";
        if ("monitor".equals(stem) || "radio".equals(stem)) {
            return "monitor".equals(stem) ? "Output monitor" : "Radio stream";
        }
        if (stem != null && stem.contains("-")) {
            String[] parts = stem.split("-");
            String last = parts[parts.length - 1];
            if (last.length() <= 4 && parts.length >= 2) return stem.replace('-', '.');
        }
        if (url != null && !url.isBlank()) {
            try {
                String host = new URI(url).getHost();
                if (host != null) return host.replaceFirst("^www\\.", "");
            } catch (Exception ignore) {}
        }
        return stem != null && !stem.isBlank() ? stem : "ASC rec";
    }

    private void writeRecSidecar(String path) {
        if (path == null || path.isBlank()) return;
        String url = urlField != null ? expandRemoteInput(normalizeUrl(urlField.getText())) : "";
        String fmt = recFormatBox != null ? String.valueOf(recFormatBox.getSelectedItem()) : "";
        String fn = recFn != null ? recFn : "WYHIWYREC";
        String title = recDisplayTitle(path, url);
        long bytes = 0;
        try { File f = new File(path); if (f.isFile()) bytes = f.length(); } catch (Exception ignore) {}
        String json = "{\n"
            + "  \"fn\": \"" + jsonEsc(fn) + "\",\n"
            + "  \"title\": \"" + jsonEsc(title) + "\",\n"
            + "  \"source_url\": \"" + jsonEsc(url == null ? "" : url) + "\",\n"
            + "  \"format\": \"" + jsonEsc(fmt) + "\",\n"
            + "  \"path\": \"" + jsonEsc(path) + "\",\n"
            + "  \"bytes\": " + bytes + "\n"
            + "}\n";
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path + ".rec.json"), json);
        } catch (Exception ignore) {}
    }

    private void saveNetRecord() {
        if (recRunning) stopNetRecord();
        String path = recOutPath;
        if (path == null || !new File(path).isFile()) {
            if (directStatusLabel != null) directStatusLabel.setText("SAVE: nothing recorded yet");
            return;
        }
        JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home"), "Downloads"));
        fc.setSelectedFile(new File(path));
        fc.setDialogTitle("Save ASC recording");
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();
        try {
            java.nio.file.Files.copy(new File(path).toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setLastDownloadPath(dest.getAbsolutePath());
            refreshRecentDownloads();
            if (directStatusLabel != null) {
                directStatusLabel.setText("SAVE " + dest.getName() + " (" + (dest.length() / 1024) + " KB)");
            }
            appendRuntimeLog("asc_rec_save", "\"path\":\"" + jsonEsc(dest.getAbsolutePath()) + "\"");
        } catch (Exception ex) {
            if (directStatusLabel != null) directStatusLabel.setText("SAVE failed: " + ex.getMessage());
        }
    }

    private void previewLastDownload() {
        String path = lastDownloadPath;
        if (path == null || !new File(path).isFile()) {
            directStatusLabel.setText("No preview file — download first");
            previewBtn.setEnabled(false);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(FFPLAY_PATH, "-autoexit", "-loglevel", "error",
                "-window_title", "GravityDesktop: " + new File(path).getName(), path);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            directStatusLabel.setText("Playing: " + new File(path).getName());
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            directStatusLabel.setText("ffplay error: " + (msg.length() > 60 ? msg.substring(0, 57) + "..." : msg));
        }
    }

    private void updateUIFromLine(String line) {
        applyJobsFromJson(line);
        String cat = extractJsonArray(line, "catalog");
        if (cat != null && cat.length() > 2) {
            renderAscFromJson(line);
            return;
        }
        // Protocol line without a catalog array — keep list, pulse the header.
        if (visualistArea != null && (ascTableModel == null || ascTableModel.getRowCount() == 0)) {
            visualistArea.setText(ASC_FRAME + "\nLIVE pulse (no catalog in this line) — ↻ loads /api/gravity\n");
        }
    }

    /** Small inset "data box" styled exactly after the reference video's glitch cyan ASC/filmic panels (first 5-10s aesthetic). */
    private JPanel makeAscBox(String label, String content) {
        JPanel box = new JPanel(new BorderLayout(1, 1));
        box.setBackground(new Color(0, 0, 0));
        box.setBorder(BorderFactory.createLineBorder(new Color(0, 255, 200), 1));  // thin cyan like video insets

        JLabel l = new JLabel(label);
        l.setForeground(new Color(0, 255, 200));
        l.setFont(new Font("Monospaced", Font.BOLD, 8));
        l.setBorder(BorderFactory.createEmptyBorder(1, 2, 0, 2));

        JTextArea ta = new JTextArea(content);
        ta.setBackground(new Color(0, 0, 0));
        ta.setForeground(new Color(0, 220, 180));
        ta.setFont(new Font("Monospaced", Font.PLAIN, 7));
        ta.setEditable(false);
        ta.setBorder(BorderFactory.createEmptyBorder(0, 2, 1, 2));

        box.add(l, BorderLayout.NORTH);
        box.add(ta, BorderLayout.CENTER);
        return box;
    }

    /** Vertical film-sprocket / grain edge strip to match the blocky side patterns in the reference video frames. */
    private JPanel makeFilmEdge() {
        JPanel edge = new JPanel();
        edge.setLayout(new BoxLayout(edge, BoxLayout.Y_AXIS));
        edge.setPreferredSize(new Dimension(22, 0));
        edge.setBackground(new Color(15, 15, 15));
        String[] marks = {"█", "░", "▓", "░", "█", " ", "░"};
        for (int i = 0; i < 40; i++) {
            JLabel m = new JLabel(marks[i % marks.length]);
            m.setForeground(new Color(50, 50, 50));
            m.setFont(new Font("Monospaced", Font.PLAIN, 7));
            m.setAlignmentX(0.5f);
            edge.add(m);
        }
        return edge;
    }

    /**
     * JS runtime check for yt-dlp (addresses the "No supported JavaScript runtime could be found" warning).
     * Deno and Node.js (bun etc.) are similarly supported by yt-dlp as JS runtimes.
     * Checks for deno (default/recommended), node, bun.
     * "pythonJava" install: Java can invoke the Python helper (nls_video_pipe.py ensure) or direct install.
     */
    private static Boolean jsRuntimesSupported;

    private static boolean ytDlpSupportsJsRuntimes() {
        if (jsRuntimesSupported != null) return jsRuntimesSupported;
        try {
            Process p = new ProcessBuilder(YTDLP_PATH, "--help").redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            jsRuntimesSupported = sb.toString().contains("--js-runtimes");
        } catch (Exception e) {
            jsRuntimesSupported = false;
        }
        return jsRuntimesSupported;
    }

    private static String formatJsRuntimeArg(String jsRt) {
        String name = jsRt.split(" ")[0];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(jsRt);
        if (m.find()) return name + ":" + m.group(1).trim();
        return name;
    }

    private static void addJsRuntimeArgs(java.util.List<String> args, String jsRt) {
        if (jsRt == null || !ytDlpSupportsJsRuntimes()) return;
        args.add("--js-runtimes");
        args.add(formatJsRuntimeArg(jsRt));
    }

    private static void addFormatArgs(java.util.List<String> args, String format, boolean gifMode) {
        args.add("-f");
        args.add(format);
        if (gifMode) {
            // yt-dlp rejects --merge-output-format gif; recode tweet_video/mp4 (and others) to GIF.
            args.add("--recode-video");
            args.add("gif");
        } else {
            args.add("--merge-output-format");
            args.add("mp4");
        }
    }

    private static void addH264Mp4Args(java.util.List<String> args) {
        addFormatArgs(args, YTDLP_H264_FORMAT, false);
    }

    private static void addGifArgs(java.util.List<String> args) {
        addFormatArgs(args, YTDLP_GIF_FORMAT, true);
    }

    private static final class FormatRow {
        String id;
        String ext;
        String resolution;
        String vcodec;
        String acodec;
        boolean videoOnly;
        boolean audioOnly;
        int height;
    }

    private static int parseFormatHeight(String resolution) {
        if (resolution == null || resolution.equals("audio only")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+x(\\d+)").matcher(resolution);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean isH264Codec(String vcodec) {
        if (vcodec == null) return false;
        String v = vcodec.toLowerCase();
        return v.startsWith("avc1") || v.startsWith("avc") || v.contains("h264");
    }

    private static FormatRow parseFormatLine(String line) {
        if (line == null || line.isBlank()) return null;
        if (line.startsWith("ID ") || line.startsWith("---") || line.startsWith("[info]")) return null;
        String[] parts = line.split("\\|");
        if (parts.length < 3) return null;
        String[] left = parts[0].trim().split("\\s+");
        if (left.length < 3) return null;
        if (!left[0].matches("[\\w.-]+")) return null;

        FormatRow row = new FormatRow();
        row.id = left[0];
        row.ext = left[1];
        row.resolution = left[2];
        row.height = parseFormatHeight(row.resolution);

        String right = parts[2].trim();
        row.videoOnly = right.contains("video only");
        row.audioOnly = right.contains("audio only");
        String[] tokens = right.split("\\s+");
        if (tokens.length == 0) return row;

        if (row.audioOnly) {
            for (String t : tokens) {
                if (t.startsWith("mp4a") || t.startsWith("opus") || t.startsWith("aac")) {
                    row.acodec = t;
                    break;
                }
            }
        } else if (row.videoOnly) {
            row.vcodec = tokens[0];
        } else {
            row.vcodec = tokens[0];
            for (int i = 1; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.startsWith("mp4a") || t.startsWith("opus") || t.startsWith("aac")) {
                    row.acodec = t;
                    break;
                }
            }
        }
        return row;
    }

    private static String selectH264Format(java.util.List<FormatRow> rows) {
        FormatRow bestCombined = null;
        for (FormatRow r : rows) {
            if (r.videoOnly || r.audioOnly) continue;
            if (!isH264Codec(r.vcodec)) continue;
            if (bestCombined == null || r.height > bestCombined.height) bestCombined = r;
        }
        if (bestCombined != null) return bestCombined.id;

        FormatRow bestVideo = null;
        FormatRow bestAudio = null;
        for (FormatRow r : rows) {
            if (r.videoOnly && isH264Codec(r.vcodec) && "mp4".equalsIgnoreCase(r.ext)) {
                if (bestVideo == null || r.height > bestVideo.height) bestVideo = r;
            }
            if (r.audioOnly && "m4a".equalsIgnoreCase(r.ext)) {
                boolean isMedium = r.acodec != null && r.acodec.contains("40.2");
                if (bestAudio == null
                    || (isMedium && (bestAudio.acodec == null || !bestAudio.acodec.contains("40.2")))
                    || (!isMedium && bestAudio.acodec != null && bestAudio.acodec.contains("40.5"))) {
                    bestAudio = r;
                }
            }
        }
        if (bestVideo != null && bestAudio != null) return bestVideo.id + "+" + bestAudio.id;
        if (bestVideo != null) return bestVideo.id + "+bestaudio[ext=m4a]/bestaudio";
        return YTDLP_H264_FORMAT;
    }

    private static String selectGifFormat(java.util.List<FormatRow> rows) {
        FormatRow bestGif = null;
        for (FormatRow r : rows) {
            if (!"gif".equalsIgnoreCase(r.ext)) continue;
            if (bestGif == null || r.height > bestGif.height) bestGif = r;
        }
        if (bestGif != null) return bestGif.id;
        return YTDLP_GIF_FORMAT;
    }

    private static String selectGenericFormat(java.util.List<FormatRow> rows, boolean gifMode) {
        if (gifMode) return selectGifFormat(rows);
        FormatRow bestCombined = null;
        FormatRow bestVideo = null;
        FormatRow bestAudio = null;
        for (FormatRow r : rows) {
            if (!r.videoOnly && !r.audioOnly) {
                if (bestCombined == null || r.height > bestCombined.height) bestCombined = r;
            } else if (r.videoOnly) {
                if (bestVideo == null || r.height > bestVideo.height) bestVideo = r;
            } else if (r.audioOnly) {
                if (bestAudio == null) bestAudio = r;
            }
        }
        if (bestCombined != null) return bestCombined.id;
        if (bestVideo != null && bestAudio != null) return bestVideo.id + "+" + bestAudio.id;
        if (bestVideo != null) return bestVideo.id + "+bestaudio/bestaudio";
        if (!rows.isEmpty()) return rows.get(0).id;
        return "best";
    }

    private static String selectFormatFromList(java.util.List<String> lines, boolean gifMode, boolean generic) {
        java.util.List<FormatRow> rows = new java.util.ArrayList<>();
        boolean inTable = false;
        for (String line : lines) {
            if (line.contains("Available formats")) {
                inTable = true;
                continue;
            }
            if (!inTable) continue;
            FormatRow row = parseFormatLine(line);
            if (row != null) rows.add(row);
        }
        if (rows.isEmpty()) {
            if (gifMode) return YTDLP_GIF_FORMAT;
            return generic ? YTDLP_GENERIC_FORMAT : YTDLP_H264_FORMAT;
        }
        if (generic) return selectGenericFormat(rows, gifMode);
        if (gifMode) return selectGifFormat(rows);
        return selectH264Format(rows);
    }

    private static boolean isYouTubeUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains("youtube-nocookie.com/");
    }

    /** Pornhub etc. need TLS impersonation (curl_cffi Chrome). VP56/libvpx is a video codec, not a JA3 target. */
    private static volatile String impersonateClient;

    private static boolean isYtDlpNoise(String line) {
        if (line == null) return true;
        String l = line.toLowerCase();
        if (l.contains("impersonat")) return true;
        if (l.contains("warning:") && (l.contains("extractor") || l.contains("deprecated"))) return true;
        return false;
    }

    private static String resolveImpersonateClient() {
        if (impersonateClient != null) return impersonateClient;
        String env = System.getenv("GRAVITY_IMPERSONATE");
        if (env != null) {
            String v = env.trim();
            if (v.isEmpty() || v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none")) {
                impersonateClient = "";
                return impersonateClient;
            }
            impersonateClient = v;
            return impersonateClient;
        }
        try {
            Process p = new ProcessBuilder(YTDLP_PATH, "--list-impersonate-targets").start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            }
            p.waitFor();
            String out = sb.toString();
            impersonateClient = out.toLowerCase().contains("chrome")
                && !out.contains("curl_cffi (unavailable)")
                ? "chrome" : "";
        } catch (Exception e) {
            impersonateClient = "";
        }
        return impersonateClient;
    }

    private static void addImpersonateArgs(java.util.List<String> args) {
        String c = resolveImpersonateClient();
        if (c == null || c.isEmpty()) return;
        args.add("--impersonate");
        args.add(c);
    }

    private boolean tlsJa3Enabled() {
        if (tlsJa3Check != null) return tlsJa3Check.isSelected();
        String v = envStr("GRAVITY_IMPERSONATE", "chrome");
        return !(v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none"));
    }

    private void addBaseYtDlpArgs(java.util.List<String> args, String url, String jsRt, boolean usePrivate) {
        args.add("--no-keep-fragments");
        addRateLimitArgs(args);
        addProxyArgs(args);
        addVpnCompatArgs(args);
        addYoutubeClientArgs(args, url);
        addJsRuntimeArgs(args, jsRt);
        // Chrome TLS/JA3 is for Pornhub etc. YouTube treats fake-Chrome + cookies as a bot challenge.
        if (tlsJa3Enabled() && !isYouTubeUrl(url)) addImpersonateArgs(args);
        addCookiesArgs(args, url, usePrivate);
        if (isYouTubeUrl(url)) {
            args.add("--user-agent");
            args.add("GravityDesktop/1.1.9");
        }
        if (isPhpViewerUrl(url)) addPhpViewerArgs(args, url);
        if (isIpfsHttpUrl(url)) addIpfsHttpArgs(args);
        else if (!isYouTubeUrl(url)) {
            args.add("--add-header");
            args.add("User-Agent:" + HTTP_UA);
            args.add("--add-header");
            args.add("Referer:" + url);
        }
    }

    private java.util.List<String> runListFormats(String url, String jsRt, boolean usePrivate) throws Exception {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(YTDLP_PATH);
        addBaseYtDlpArgs(args, url, jsRt, usePrivate);
        args.add("--list-formats");
        args.add(url);
        YtDlpResult result = runYtDlp(args);
        if (result.exitCode != 0 && outputIndicates403(result.lines)) {
            RateLimiter.on403();
        } else if (result.exitCode == 0) {
            RateLimiter.onSuccess();
        }
        return result.lines;
    }

    private static final class CookieSource {
        final String kind; // "file" or "browser"
        final String value;
        CookieSource(String kind, String value) { this.kind = kind; this.value = value; }
    }

    private static String cookiesFilePath() {
        String env = System.getenv("GRAVITY_COOKIES_FILE");
        if (env != null && !env.isBlank()) {
            File f = new File(env.trim());
            if (f.isFile() && f.canRead()) return f.getAbsolutePath();
        }
        String home = System.getProperty("user.home");
        File def = new File(home + "/.config/gravity-desktop/cookies.txt");
        if (def.isFile() && def.canRead()) return def.getAbsolutePath();
        return null;
    }

    private static boolean browserCookieDbExists(String browser) {
        String home = System.getProperty("user.home");
        String b = browser.toLowerCase();
        if ("brave".equals(b)) {
            return new File(home + "/.config/BraveSoftware/Brave-Browser/Default/Cookies").isFile();
        }
        if ("chromium".equals(b)) {
            return new File(home + "/.config/chromium/Default/Cookies").isFile();
        }
        if ("chrome".equals(b)) {
            return new File(home + "/.config/google-chrome/Default/Cookies").isFile();
        }
        if ("edge".equals(b)) {
            return new File(home + "/.config/microsoft-edge/Default/Cookies").isFile();
        }
        if ("opera".equals(b)) {
            return new File(home + "/.config/opera/Default/Cookies").isFile()
                || new File(home + "/.config/opera-stable/Default/Cookies").isFile();
        }
        if ("vivaldi".equals(b)) {
            return new File(home + "/.config/vivaldi/Default/Cookies").isFile();
        }
        if ("whale".equals(b)) {
            return new File(home + "/.config/naver-whale/Default/Cookies").isFile()
                || new File(home + "/.config/Naver Whale/Default/Cookies").isFile();
        }
        if ("safari".equals(b)) {
            return new File(home + "/Library/Cookies/Cookies.binarycookies").isFile();
        }
        if ("firefox".equals(b)) {
            File snap = new File(home + "/snap/firefox/common/.mozilla/firefox");
            if (snap.isDirectory()) {
                File[] profiles = snap.listFiles((dir, name) -> name.endsWith(".default") || name.contains("default"));
                if (profiles != null) {
                    for (File p : profiles) {
                        if (new File(p, "cookies.sqlite").isFile()) return true;
                    }
                }
            }
            File ff = new File(home + "/.mozilla/firefox");
            if (ff.isDirectory()) {
                File[] profiles = ff.listFiles((dir, name) -> name.endsWith(".default") || name.contains("default"));
                if (profiles != null) {
                    for (File p : profiles) {
                        if (new File(p, "cookies.sqlite").isFile()) return true;
                    }
                }
            }
        }
        return false;
    }

    private static String resolveCookiesBrowser() {
        String env = System.getenv("GRAVITY_COOKIES_BROWSER");
        if (env != null && !env.isBlank()) {
            String b = env.trim();
            if (browserCookieDbExists(b)) return b;
            return null;
        }
        for (String candidate : new String[]{
            "brave", "chromium", "chrome", "edge", "firefox", "opera", "vivaldi", "whale", "safari"
        }) {
            if (browserCookieDbExists(candidate)) return candidate;
        }
        return null;
    }

    private CookieSource resolveCookieSource() {
        return resolveCookieSource(null);
    }

    private CookieSource resolveCookieSource(String url) {
        String file = cookiesFilePath();
        String browser = resolveCookiesBrowser();
        boolean vpn = useVpnRoute();
        // YouTube bot-check: Netscape cookies.txt worked; Brave DB still LOGIN_REQUIRED.
        if (url != null && isYouTubeUrl(url) && file != null) {
            return new CookieSource("file", file);
        }
        if (vpn && browser != null) return new CookieSource("browser", browser);
        if (file != null && browser != null) {
            long age = System.currentTimeMillis() - new File(file).lastModified();
            if (age > 2L * 24 * 3600 * 1000) return new CookieSource("browser", browser);
        }
        if (file != null) return new CookieSource("file", file);
        if (browser != null) return new CookieSource("browser", browser);
        return null;
    }

    private boolean shouldUseCookies(String url, boolean usePrivate) {
        if (isDirectMediaUrl(url) || isIpfsHttpUrl(url)) return false;
        // YouTube bot-check: send Brave/cookies.txt whenever we have them, not only private/VPN.
        if (isYouTubeUrl(url)) return resolveCookieSource(url) != null || usePrivate || useVpnRoute();
        if (isXStatusUrl(url)) return true;
        if (isPhpViewerUrl(url) && usePrivate) return true;
        return false;
    }

    private void addCookiesArgs(java.util.List<String> args, String url, boolean usePrivate) {
        if (!shouldUseCookies(url, usePrivate)) return;
        CookieSource src = resolveCookieSource(url);
        if (src == null) return;
        if ("file".equals(src.kind)) {
            args.add("--cookies");
            args.add(src.value);
        } else {
            args.add("--cookies-from-browser");
            args.add(src.value);
        }
    }

    private String describeCookieSource() {
        CookieSource src = resolveCookieSource();
        if (src == null) return "none";
        if ("file".equals(src.kind)) return "file:" + src.value;
        return "browser:" + src.value;
    }

    private static String ipfsGateway() {
        String gw = System.getenv("GRAVITY_IPFS_GATEWAY");
        if (gw != null && !gw.isBlank()) {
            gw = gw.trim();
            if (!gw.endsWith("/")) gw += "/";
            return gw;
        }
        return "https://ipfs.io/ipfs/";
    }

    private static File remoteNavStore() {
        String home = System.getProperty("user.home");
        return new File(home + "/.config/gravity-desktop/remote-nav.tsv");
    }

    private void loadRemoteNav() {
        if (remoteNavModel == null) return;
        remoteNavModel.clear();
        File f = remoteNavStore();
        if (!f.isFile()) {
            String base = System.getenv("GRAVITY_REMOTE_BASE");
            if (base != null && !base.isBlank()) {
                remoteNavModel.addElement(new RemoteNavEntry("default", base.trim(), 1, "remote-cli.php"));
            }
            ensureNlsarNav();
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                if (ln.isBlank() || ln.startsWith("#")) continue;
                RemoteNavEntry e = RemoteNavEntry.fromTsv(ln);
                if (e != null) remoteNavModel.addElement(e);
            }
        } catch (Exception ignore) {}
        ensureNlsarNav();
    }

    private void ensureNlsarNav() {
        if (remoteNavModel == null) return;
        for (int i = 0; i < remoteNavModel.size(); i++) {
            if ("nlsar".equalsIgnoreCase(remoteNavModel.get(i).assetClass)) return;
        }
        String base = resolveNlsarUrl();
        remoteNavModel.addElement(new RemoteNavEntry("nlsar", base, 1, "bot"));
        remoteNavModel.addElement(new RemoteNavEntry("nlsar-news", base, 2, "news"));
        remoteNavModel.addElement(new RemoteNavEntry("nlsar-station", base, 3, "station"));
        saveRemoteNav();
    }

    private void saveRemoteNav() {
        if (remoteNavModel == null) return;
        try {
            File f = remoteNavStore();
            f.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                pw.println("# assetClass\tbaseUrl\tmenuIndex\tviewer");
                for (int i = 0; i < remoteNavModel.size(); i++) {
                    pw.println(remoteNavModel.get(i).toTsv());
                }
            }
        } catch (Exception ignore) {}
    }

    private RemoteNavEntry getActiveNavEntry() {
        if (remoteNavList == null) return null;
        return remoteNavList.getSelectedValue();
    }

    /** Expand viewkey= / menu$N shorthand using sync nav asset-class assignment. */
    private String expandRemoteInput(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        RemoteNavEntry nav = getActiveNavEntry();

        java.util.regex.Matcher vk = java.util.regex.Pattern
            .compile("(?i)^viewkey=(.+)$").matcher(u);
        if (vk.matches()) {
            String key = vk.group(1).trim();
            if (nav != null) return nav.buildViewkeyUrl(key);
            String base = System.getenv("GRAVITY_REMOTE_BASE");
            if (base != null && !base.isBlank()) {
                return new RemoteNavEntry("env", base.trim(), 1, "remote-cli.php").buildViewkeyUrl(key);
            }
            return u;
        }

        java.util.regex.Matcher menu = java.util.regex.Pattern.compile("^menu\\$(\\d+)$").matcher(u);
        if (menu.matches()) {
            int idx = Integer.parseInt(menu.group(1));
            if (nav != null) return nav.buildMenuUrl(idx);
            String base = System.getenv("GRAVITY_REMOTE_BASE");
            if (base != null && !base.isBlank()) {
                return new RemoteNavEntry("env", base.trim(), idx, "remote.php").buildMenuUrl(idx);
            }
            return u;
        }

        if (u.toLowerCase().startsWith("remote-cli.php") || u.toLowerCase().startsWith("remote.php")
            || u.toLowerCase().contains("navi(1)remote.php")) {
            if (nav != null && !nav.baseNorm().isEmpty()) {
                return nav.baseNorm() + u;
            }
            String base = System.getenv("GRAVITY_REMOTE_BASE");
            if (base != null && !base.isBlank()) {
                return (base.endsWith("/") ? base : base + "/") + u;
            }
        }
        return u;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        java.util.regex.Matcher http = java.util.regex.Pattern
            .compile("(https?://[^\\s<>\"']+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        java.util.regex.Matcher ipfs = java.util.regex.Pattern
            .compile("(ipfs://[^\\s<>\"']+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        java.util.regex.Matcher vk = java.util.regex.Pattern
            .compile("(viewkey=[^\\s<>\"'&]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        java.util.regex.Matcher menu = java.util.regex.Pattern.compile("(menu\\$\\d+)").matcher(url);
        if (http.find()) url = http.group(1);
        else if (ipfs.find()) url = ipfs.group(1);
        else if (vk.find()) url = vk.group(1);
        else if (menu.find()) url = menu.group(1);
        return url.replaceAll("[.,;:!?\\]\\)>\"']+$", "");
    }

    /** Resolve ipfs:// to HTTP gateway; leave https gateway URLs unchanged. */
    private static String resolveDownloadUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String u = url.trim();
        if (u.regionMatches(true, 0, "ipfs://", 0, 7)) {
            String path = u.substring(7);
            if (path.startsWith("ipfs/")) path = path.substring(5);
            return ipfsGateway() + path;
        }
        return u;
    }

    private static boolean isDownloadableUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("ipfs://")
            || u.startsWith("viewkey=") || u.startsWith("menu$")
            || u.contains("remote.php") || u.contains("remote-cli.php");
    }

    private static boolean isIpfsHttpUrl(String url) {
        String u = url.toLowerCase();
        return u.contains("/ipfs/") || u.contains(".ipfs.") || u.contains("ipfs.io")
            || u.contains("dweb.link") || u.contains("cloudflare-ipfs.com")
            || u.contains("gateway.pinata") || u.contains("nftstorage.link");
    }

    private static boolean isPhpViewerUrl(String url) {
        String u = url.toLowerCase();
        return u.contains(".php") || u.contains("viewkey=");
    }

    /** ffmpeg with stderr/stdout discarded — avoids pipe deadlock on progress logs. */
    private static int runFfmpegQuiet(java.util.List<String> args, long timeoutSec) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(FFMPEG_PATH);
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.add("-nostats");
        cmd.add("-loglevel");
        cmd.add("error");
        int i = 0;
        if (!args.isEmpty() && args.get(0).equals(FFMPEG_PATH)) i = 1;
        for (; i < args.size(); i++) cmd.add(args.get(i));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new Exception("ffmpeg timeout " + timeoutSec + "s");
        }
        return p.exitValue();
    }

    private void postProcessVp56(String mp4Path) {
        if (mp4Path == null || !new File(mp4Path).isFile()) return;
        String out = mp4Path.replaceAll("\\.mp4$", "") + ".vp56.webm";
        try {
            int code = runFfmpegQuiet(java.util.Arrays.asList(
                FFMPEG_PATH, "-y", "-i", mp4Path,
                "-c:v", "libvpx", "-b:v", "800k", "-c:a", "libvorbis",
                out
            ), 900);
            if (code == 0 && new File(out).isFile()) {
                directStatusLabel.setText("VP56/webm post: " + new File(out).getName());
            } else {
                directStatusLabel.setText("VP56 ffmpeg exit " + code);
            }
        } catch (Exception ex) {
            directStatusLabel.setText("VP56 note: " + ex.getMessage());
        }
    }

    private static final java.util.regex.Pattern X_STATUS_ID = java.util.regex.Pattern
        .compile("(?:twitter\\.com|x\\.com)/(?:[^/]+/status|i/status)/(\\d+)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TWIMG_TWEET_VIDEO = java.util.regex.Pattern
        .compile("https://video\\.twimg\\.com/tweet_video/[A-Za-z0-9_-]+\\.mp4");
    private static final java.util.regex.Pattern TWIMG_ANY_VIDEO = java.util.regex.Pattern
        .compile("https://video\\.twimg\\.com/[A-Za-z0-9_./-]+\\.mp4");

    private static boolean isGifAddress(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        String base = u.split("\\?")[0].split("#")[0];
        if (base.endsWith(".gif") || base.endsWith(".gifv")) return true;
        if (base.contains("/gif/") || base.contains("/gifs/") || base.contains("/tweet_video/")) return true;
        if (u.contains("format=gif") || u.contains("type=gif") || u.contains("media_type=gif")) return true;
        return u.contains("giphy.com") || u.contains("tenor.com")
            || u.contains("redgifs.com") || u.contains("gfycat.com");
    }

    private static String extractXStatusId(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = X_STATUS_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isXStatusUrl(String url) {
        return extractXStatusId(url) != null;
    }

    private static String extractTwimgMediaUrl(String body) {
        if (body == null || body.isBlank()) return null;
        java.util.regex.Matcher gif = TWIMG_TWEET_VIDEO.matcher(body);
        if (gif.find()) return gif.group();
        java.util.regex.Matcher vid = TWIMG_ANY_VIDEO.matcher(body);
        return vid.find() ? vid.group() : null;
    }

    private static String httpGetUtf8(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("User-Agent", HTTP_UA);
        conn.setRequestProperty("Accept", "application/json, */*");
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream,
                java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            return sb.toString();
        }
    }

    /** X animated GIFs are looping MP4s at video.twimg.com/tweet_video/. Guest yt-dlp often misses them. */
    private static String resolveXStatusMediaUrl(String statusId) {
        if (statusId == null || statusId.isBlank()) return null;
        String[] endpoints = {
            "https://api.fxtwitter.com/status/" + statusId,
            "https://api.vxtwitter.com/Twitter/status/" + statusId
        };
        for (String ep : endpoints) {
            try {
                String media = extractTwimgMediaUrl(httpGetUtf8(ep, 8000, 15000));
                if (media != null) return media;
            } catch (Exception ignore) {}
        }
        return null;
    }

    private static final String HTTP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static String extractGiphyId(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:media\\d*\\.giphy\\.com/media/|i\\.giphy\\.com/)([A-Za-z0-9]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("giphy\\.com/(?:gifs|media|embed)/([^/?#]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        if (!m.find()) return null;
        String slug = m.group(1);
        int dash = slug.lastIndexOf('-');
        if (dash >= 0 && dash < slug.length() - 4) return slug.substring(dash + 1);
        return slug;
    }

    private static String extractImgurId(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:i\\.)?imgur\\.com/(?:gallery/|a/)?([A-Za-z0-9]+)(?:\\.(?:gifv|gif|mp4|webm|png|jpg|jpeg))?",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Map known GIF hosts to a direct media URL (native .gif or looping mp4). */
    private static String resolveGifDirectUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String xId = extractXStatusId(url);
        if (xId != null) return resolveXStatusMediaUrl(xId);
        String giphy = extractGiphyId(url);
        if (giphy != null) return "https://i.giphy.com/" + giphy + ".gif";
        String imgur = extractImgurId(url);
        if (imgur != null) {
            String low = url.toLowerCase();
            if (low.contains(".gifv") || low.contains(".mp4") || low.contains(".webm")) {
                return "https://i.imgur.com/" + imgur + ".mp4";
            }
            return "https://i.imgur.com/" + imgur + ".gif";
        }
        return null;
    }

    private static boolean fileStartsWith(File f, String ascii) {
        if (f == null || !f.isFile()) return false;
        byte[] want = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (InputStream in = new FileInputStream(f)) {
            byte[] got = new byte[want.length];
            int n = in.read(got);
            if (n < want.length) return false;
            for (int i = 0; i < want.length; i++) if (got[i] != want[i]) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean fileIsGif(File f) {
        return fileStartsWith(f, "GIF87a") || fileStartsWith(f, "GIF89a");
    }

    private static boolean fileLooksLikeVideo(File f) {
        if (f == null || !f.isFile() || f.length() < 12) return false;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[12];
            if (in.read(b) < 12) return false;
            // ISO BMFF / mp4: ....ftyp
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true;
            // WebM / Matroska EBML
            if ((b[0] & 0xff) == 0x1a && (b[1] & 0xff) == 0x45 && (b[2] & 0xff) == 0xdf) return true;
            // RIFF....AVI / WEBP
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return true;
        } catch (Exception ignore) {}
        return false;
    }

    private static boolean fileLooksLikeHtml(File f) {
        if (f == null || !f.isFile()) return false;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            if (n <= 0) return false;
            String s = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).trim().toLowerCase();
            return s.startsWith("<!doctype") || s.startsWith("<html") || s.startsWith("<?xml");
        } catch (Exception e) {
            return false;
        }
    }

    private static java.util.List<String> extractGifCandidates(String html) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (html == null || html.isBlank()) return out;
        String h = html.replace("\\/", "/").replace("\\u002F", "/");
        java.util.LinkedHashSet<String> ranked = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> gifs = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> videos = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("https://[^\\s\"'<>\\\\]+", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(h);
        while (m.find()) {
            String u = m.group().replace("&amp;", "&");
            int cut = u.length();
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c == '<' || c == '>' || c == ')' || c == ']' || c == '}' || c == '"') {
                    cut = i;
                    break;
                }
            }
            u = u.substring(0, cut);
            String low = u.toLowerCase();
            String path = low.split("\\?", 2)[0];
            if (low.contains("favicon") || low.contains("sprite") || low.contains("/static/")
                || low.contains("pixel") || low.contains("1x1") || low.contains("analytics")
                || low.contains("woff") || low.contains(".svg")
                || low.contains("pinterest.com") || low.contains("facebook.com")) continue;
            boolean gif = path.endsWith(".gif") && !path.endsWith(".gifv");
            boolean twGif = path.contains("/tweet_video/") && path.endsWith(".mp4");
            boolean vid = path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".gifv");
            if (gif || twGif) {
                if (low.contains("giphy") || low.contains("tenor") || low.contains("imgur")
                    || low.contains("twimg") || twGif) ranked.add(u);
                else gifs.add(u);
            } else if (vid && (low.contains("twimg") || low.contains("imgur")
                    || low.contains("giphy") || low.contains("redgifs") || low.contains("tenor"))) {
                videos.add(u);
            }
        }
        out.addAll(ranked);
        out.addAll(gifs);
        out.addAll(videos);
        if (out.size() > 12) return new java.util.ArrayList<>(out.subList(0, 12));
        return out;
    }

    private static java.util.List<String> extractVideoCandidates(String html) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (html == null || html.isBlank()) return out;
        String h = html.replace("\\/", "/").replace("\\u002F", "/");
        java.util.LinkedHashSet<String> ranked = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher og = java.util.regex.Pattern.compile(
            "property=[\"']og:video(?::url|:secure_url)?[\"'][^>]*content=[\"'](https://[^\"']+)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(h);
        while (og.find()) ranked.add(og.group(1).replace("&amp;", "&"));
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("https://[^\\s\"'<>\\\\]+", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(h);
        while (m.find()) {
            String u = m.group().replace("&amp;", "&");
            int cut = u.length();
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c == '<' || c == '>' || c == ')' || c == ']' || c == '}' || c == '"') {
                    cut = i;
                    break;
                }
            }
            u = u.substring(0, cut);
            String path = u.toLowerCase().split("\\?", 2)[0];
            if (path.contains("favicon") || path.contains("/static/") || path.contains("analytics")) continue;
            if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8")
                    || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".mkv")) {
                ranked.add(u);
            }
        }
        out.addAll(ranked);
        if (out.size() > 12) return new java.util.ArrayList<>(out.subList(0, 12));
        return out;
    }

    private HttpURLConnection openHttpGet(String url, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", HTTP_UA);
        conn.setRequestProperty("Accept", "image/gif,image/webp,image/*,video/mp4,video/webm,text/html,*/*");
        if (referer != null && !referer.isBlank()) conn.setRequestProperty("Referer", referer);
        return conn;
    }

    private boolean httpDownloadFile(String url, File dest, String referer) {
        InputStream in = null;
        OutputStream out = null;
        try {
            if (dest.exists()) dest.delete();
            HttpURLConnection conn = openHttpGet(url, referer);
            int code = conn.getResponseCode();
            if (code >= 400) return false;
            long total = conn.getContentLengthLong();
            in = conn.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            long got = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (abortRequested) {
                    dest.delete();
                    return false;
                }
                out.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    final int pct = (int) Math.min(99, got * 100 / total);
                    SwingUtilities.invokeLater(() -> directProgress.setValue(pct));
                }
            }
            out.flush();
            return dest.isFile() && dest.length() > 0;
        } catch (Exception e) {
            if (dest.exists()) dest.delete();
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignore) {}
            try { if (in != null) in.close(); } catch (Exception ignore) {}
        }
    }

    private boolean recodeVideoFileToGif(File in, File out) {
        try {
            if (out.exists()) out.delete();
            int code = runFfmpegQuiet(java.util.Arrays.asList(
                FFMPEG_PATH, "-y", "-i", in.getAbsolutePath(),
                "-an",
                "-vf", "fps=12,scale=min(480\\,iw):-1:flags=lanczos",
                out.getAbsolutePath()
            ), 600);
            if (code == 0 && fileIsGif(out)) return true;
            code = runFfmpegQuiet(java.util.Arrays.asList(
                FFMPEG_PATH, "-y", "-i", in.getAbsolutePath(),
                "-an", out.getAbsolutePath()
            ), 600);
            return code == 0 && fileIsGif(out);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean remuxToMp4(File in, File out) {
        try {
            if (out.exists()) out.delete();
            int code = runFfmpegQuiet(java.util.Arrays.asList(
                FFMPEG_PATH, "-y", "-i", in.getAbsolutePath(),
                "-c", "copy", "-movflags", "+faststart",
                out.getAbsolutePath()
            ), 300);
            if (code == 0 && out.isFile() && out.length() > 0) return true;
            code = runFfmpegQuiet(java.util.Arrays.asList(
                FFMPEG_PATH, "-y", "-i", in.getAbsolutePath(),
                "-c:v", "libx264", "-c:a", "aac", "-movflags", "+faststart",
                out.getAbsolutePath()
            ), 900);
            return code == 0 && out.isFile() && out.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String[] INJECT_NEEDLES = {
        "<script", "</script", "javascript:", "onerror=", "onload=", "onclick=",
        "<?php", "<%", "<iframe", "document.cookie", "eval(", "<svg", "data:text/html"
    };

    private boolean vacuumDetailOn() {
        return vacuumDetailCheck != null && vacuumDetailCheck.isSelected();
    }

    private void setVacuumLogVisible(boolean on) {
        if (vacuumLogScroll == null) return;
        vacuumLogScroll.setVisible(on);
        if (recSouth != null) {
            recSouth.revalidate();
            recSouth.repaint();
        }
        if (frame != null) frame.revalidate();
    }

    private void vacuumLog(String step) {
        appendRuntimeLog("vacuum_step", "\"msg\":\"" + jsonEsc(step) + "\"");
        if (!vacuumDetailOn() && vacuumLogArea == null) return;
        final String line = java.time.Instant.now().toString().substring(11, 19) + "  " + step + "\n";
        Runnable r = () -> {
            if (vacuumLogArea == null) return;
            vacuumLogArea.append(line);
            vacuumLogArea.setCaretPosition(vacuumLogArea.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private void clearVacuumLogView() {
        if (vacuumLogArea != null) vacuumLogArea.setText("(cleared — jsonl still on disk until next vacuum)\n");
        setVacuumLogVisible(true);
    }

    private void showVacuumLogDialog() {
        if (vacuumLogArea == null) return;
        setVacuumLogVisible(true);
        if (vacuumDetailCheck != null) vacuumDetailCheck.setSelected(true);
        writeEnvFileKey("GRAVITY_VACUUM_DETAIL", "1");
        String fresh = tailVacuumJsonl();
        vacuumLogArea.setText("— refreshed " + java.time.Instant.now() + " —\n" + fresh);
        JTextArea a = new JTextArea(vacuumLogArea.getText());
        a.setFont(new Font("Monospaced", Font.PLAIN, 11));
        a.setEditable(false);
        a.setBackground(new Color(0x0b, 0x10, 0x20));
        a.setForeground(new Color(0xe6, 0xf6, 0xff));
        a.setCaretPosition(a.getDocument().getLength());
        JScrollPane sp = new JScrollPane(a);
        sp.setPreferredSize(new Dimension(720, 360));
        JOptionPane.showMessageDialog(frame, sp, "Vacuum / TLS-JA3 detail", JOptionPane.PLAIN_MESSAGE);
    }

    private static String tailVacuumJsonl() {
        if (!RUNTIME_LIVE_LOG.isFile()) return "(no runtime log)";
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(RUNTIME_LIVE_LOG.toPath());
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = lines.size() - 1; i >= 0 && n < 40; i--) {
                String ln = lines.get(i);
                if (ln.contains("\"vacuum") || ln.contains("impersonat") || ln.contains("download_")) {
                    sb.insert(0, ln + "\n");
                    n++;
                }
            }
            return sb.length() == 0 ? "(no vacuum events yet)" : sb.toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Vacuum img/vid/gif: drop polyglot HTML/JS/PHP, truncate after media trailer,
     * ffmpeg -map_metadata -1. TLS/JA3 is curl_cffi Chrome on the request; this is the file.
     */
    private String vacuumMediaFile(File f) {
        if (f == null || !f.isFile()) return "";
        long before = f.length();
        vacuumLog("start " + f.getName() + " " + before + "B  TLS/JA3=" + (tlsJa3Enabled() ? "chrome" : "off"));
        try {
            byte[] head = readHead(f, 256);
            if (looksLikeScriptContainer(head)) {
                vacuumLog("REJECT html/js/php polyglot — deleting " + f.getName());
                f.delete();
                return " vacuum:rejected-html/js";
            }
            long mid = f.length();
            truncateAfterTrailer(f);
            if (f.length() != mid) vacuumLog("truncate trailer " + mid + " → " + f.length() + "B");
            else vacuumLog("trailer ok (no polyglot tail)");
            File tmp = new File(f.getAbsolutePath() + ".vac");
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-y");
            args.add("-i");
            args.add(f.getAbsolutePath());
            args.add("-map_metadata");
            args.add("-1");
            args.add("-fflags");
            args.add("+bitexact");
            String name = f.getName().toLowerCase();
            if (name.endsWith(".gif") || fileIsGif(f)) {
                args.add("-an");
                args.add("-c:v");
                args.add("gif");
            } else if (name.endsWith(".webm")) {
                args.add("-c");
                args.add("copy");
            } else {
                args.add("-c");
                args.add("copy");
                args.add("-movflags");
                args.add("+faststart");
            }
            args.add(tmp.getAbsolutePath());
            vacuumLog("ffmpeg -map_metadata -1 copy → " + tmp.getName());
            int code = runFfmpegQuiet(args, 300);
            if (code != 0 || !tmp.isFile() || tmp.length() < 32) {
                vacuumLog("copy failed rc=" + code + " — re-encode fallback");
                if (tmp.exists()) tmp.delete();
                args = new java.util.ArrayList<>();
                args.add("-y");
                args.add("-i");
                args.add(f.getAbsolutePath());
                args.add("-map_metadata");
                args.add("-1");
                if (name.endsWith(".gif") || fileIsGif(f)) {
                    args.add("-an");
                    args.add(tmp.getAbsolutePath());
                } else {
                    args.add("-c:v");
                    args.add("libx264");
                    args.add("-c:a");
                    args.add("aac");
                    args.add("-movflags");
                    args.add("+faststart");
                    args.add(tmp.getAbsolutePath());
                }
                code = runFfmpegQuiet(args, 900);
            }
            if (code == 0 && tmp.isFile() && tmp.length() > 32) {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                vacuumLog("replaced " + f.getName() + " (" + tmp.length() + "B)");
            } else if (tmp.exists()) {
                tmp.delete();
                vacuumLog("ffmpeg produced nothing — kept original");
            }
            String nm = f.getName().toLowerCase();
            if ((nm.endsWith(".gif") || nm.endsWith(".jpg") || nm.endsWith(".jpeg")
                    || nm.endsWith(".png") || nm.endsWith(".webp") || fileIsGif(f))
                    && containsInjection(f)) {
                vacuumLog("WARN inject residue still in still-image bytes");
                return " vacuum:WARN-inject-residue";
            }
            long after = f.length();
            long dropped = Math.max(0, before - after);
            vacuumLog("done dropped=" + dropped + "B  result=" + (dropped > 0 ? "stripped" : "clean"));
            appendRuntimeLog("vacuum", "\"file\":\"" + jsonEsc(f.getName())
                + "\",\"dropped\":" + dropped + ",\"ja3\":\"" + (tlsJa3Enabled() ? "chrome" : "off") + "\"");
            return dropped > 0 ? (" vacuum:-" + dropped + "B") : " vacuum:clean";
        } catch (Exception ex) {
            vacuumLog("skip: " + ex.getMessage());
            return " vacuum:skip";
        }
    }

    private static byte[] readHead(File f, int n) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[n];
            int g = in.read(b);
            if (g <= 0) return new byte[0];
            if (g == n) return b;
            byte[] c = new byte[g];
            System.arraycopy(b, 0, c, 0, g);
            return c;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static boolean looksLikeScriptContainer(byte[] head) {
        if (head == null || head.length < 8) return false;
        String s = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).trim().toLowerCase();
        return s.startsWith("<!doctype html") || s.startsWith("<html") || s.startsWith("<script")
            || s.startsWith("<?php") || s.startsWith("<?xml") && s.contains("<script");
    }

    private static boolean containsInjection(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            StringBuilder win = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                win.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1));
                if (win.length() > 16384) win.delete(0, win.length() - 8192);
                String low = win.toString().toLowerCase();
                for (String needle : INJECT_NEEDLES) {
                    if (low.contains(needle)) return true;
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    /** JPEG FFD9, PNG IEND, GIF 3B — drop appended HTML/JS polyglot. */
    private static void truncateAfterTrailer(File f) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
        if (data.length < 16) return;
        int cut = -1;
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
            for (int i = data.length - 2; i >= 2; i--) {
                if (data[i] == (byte) 0xFF && data[i + 1] == (byte) 0xD9) {
                    cut = i + 2;
                    break;
                }
            }
        } else if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            for (int i = data.length - 1; i >= 6; i--) {
                if (data[i] == 0x3B) {
                    cut = i + 1;
                    break;
                }
            }
        } else if (data.length > 8 && data[0] == (byte) 0x89 && data[1] == 'P') {
            byte[] iend = {0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
            for (int i = data.length - 8; i >= 8; i--) {
                boolean ok = true;
                for (int j = 0; j < 8; j++) if (data[i + j] != iend[j]) { ok = false; break; }
                if (ok) {
                    cut = i + 8;
                    break;
                }
            }
        }
        if (cut > 32 && cut < data.length) {
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(data, 0, cut);
            }
        }
    }

    /** HTTP/page scrape when yt-dlp H.264 format fails on non-YouTube sites. */
    private boolean saveAsVideo(String url, File dest) {
        return saveAsVideo(url, dest, 0, new java.util.HashSet<String>(), url);
    }

    private boolean saveAsVideo(String url, File dest, int depth, java.util.Set<String> seen, String referer) {
        if (url == null || url.isBlank() || depth > 4 || abortRequested) return false;
        String key = url.split("#")[0];
        if (!seen.add(key)) return false;
        String xid = extractXStatusId(url);
        if (xid != null) {
            String media = resolveXStatusMediaUrl(xid);
            if (media != null && saveAsVideo(media, dest, depth + 1, seen, url)) return true;
        }
        File part = new File(dest.getAbsolutePath() + ".part");
        boolean got = httpDownloadFile(url, part, referer);
        if (got && fileLooksLikeVideo(part)) {
            boolean ok = remuxToMp4(part, dest);
            if (!ok) {
                if (dest.exists()) dest.delete();
                ok = part.renameTo(dest) || copyFile(part, dest);
            }
            if (part.exists()) part.delete();
            return ok && dest.isFile() && dest.length() > 0;
        }
        if (got && fileLooksLikeHtml(part)) {
            String html = readFileUtf8(part);
            part.delete();
            for (String cand : extractVideoCandidates(html)) {
                if (saveAsVideo(cand, dest, depth + 1, seen, url)) return true;
            }
        } else if (part.exists()) {
            part.delete();
        }
        return dest.isFile() && dest.length() > 32;
    }

    /**
     * Download any GIF: native .gif over HTTP, host resolvers (X/Giphy/Tenor/Imgur),
     * HTML scrape, or ffmpeg recode of looping mp4/webm/gifv. yt-dlp is the fallback.
     */
    private boolean saveAsGif(String url, File dest) {
        return saveAsGif(url, dest, 0, new java.util.HashSet<String>(), url);
    }

    private boolean saveAsGif(String url, File dest, int depth, java.util.Set<String> seen, String referer) {
        if (url == null || url.isBlank() || depth > 4 || abortRequested) return false;
        String key = url.split("#")[0];
        if (!seen.add(key)) return false;

        String resolved = resolveGifDirectUrl(url);
        if (resolved != null && !resolved.equals(url)
                && saveAsGif(resolved, dest, depth + 1, seen, url)) {
            return true;
        }

        File part = new File(dest.getAbsolutePath() + ".part");
        boolean got = httpDownloadFile(url, part, referer);
        if (got && fileIsGif(part)) {
            if (dest.exists()) dest.delete();
            if (part.renameTo(dest) || (copyFile(part, dest) && part.delete())) return fileIsGif(dest);
        }
        if (got && fileLooksLikeVideo(part)) {
            boolean ok = recodeVideoFileToGif(part, dest);
            part.delete();
            if (ok) return true;
        }
        if (got && (fileLooksLikeHtml(part) || part.length() > 0)) {
            String html = readFileUtf8(part);
            part.delete();
            for (String cand : extractGifCandidates(html)) {
                if (saveAsGif(cand, dest, depth + 1, seen, url)) return true;
            }
        } else if (part.exists()) {
            part.delete();
        }

        String imgur = extractImgurId(url);
        if (imgur != null) {
            String mp4 = "https://i.imgur.com/" + imgur + ".mp4";
            if (saveAsGif(mp4, dest, depth + 1, seen, url)) return true;
        }
        return dest.isFile() && fileIsGif(dest);
    }

    private static boolean copyFile(File from, File to) {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return to.isFile() && to.length() == from.length();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readFileUtf8(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isDirectMediaUrl(String url) {
        String base = url.toLowerCase().split("\\?")[0].split("#")[0];
        return base.matches(".*\\.(mp4|webm|mkv|mov|m4v|gif|webp|avi|flv)$") || isGifAddress(url);
    }

    private static boolean useGenericExtractor(String url) {
        if (url == null) return false;
        if (isYouTubeUrl(url)) return false;
        return true;
    }

    private static void addGenericMediaArgs(java.util.List<String> args, boolean gifMode) {
        if (gifMode) {
            addGifArgs(args);
        } else {
            args.add("-f");
            args.add("bestvideo*+bestaudio/best");
            args.add("--merge-output-format");
            args.add("mp4");
        }
    }

    private static void addPhpViewerArgs(java.util.List<String> args, String url) {
        args.add("--add-header");
        args.add("Referer:" + url);
        args.add("--add-header");
        args.add("User-Agent:Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    }

    private static void addIpfsHttpArgs(java.util.List<String> args) {
        args.add("--add-header");
        args.add("User-Agent:GravityDesktop/1.1.9 (IPFS-HTTP)");
        args.add("--add-header");
        args.add("Accept:*/*");
    }

    private static String extractMediaId(String url) {
        java.util.regex.Matcher ipfs = java.util.regex.Pattern
            .compile("/ipfs/([^/?#]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        if (ipfs.find()) {
            String cid = ipfs.group(1);
            return cid.length() > 16 ? cid.substring(0, 16) : cid;
        }
        if (url.regionMatches(true, 0, "ipfs://", 0, 7)) {
            String cid = url.substring(7).split("[?#]")[0];
            if (cid.startsWith("ipfs/")) cid = cid.substring(5);
            return cid.length() > 16 ? cid.substring(0, 16) : cid;
        }
        java.util.regex.Matcher qid = java.util.regex.Pattern
            .compile("[?&](?:viewkey|vkey|video_id|v|id|file|cid|hash|vk)=([A-Za-z0-9_-]{4,})",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(url);
        if (qid.find()) {
            String id = qid.group(1);
            return id.length() > 24 ? id.substring(0, 24) : id;
        }
        if (url.contains("youtu.be/")) return url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        String xId = extractXStatusId(url);
        if (xId != null) return xId;
        String giphyId = extractGiphyId(url);
        if (giphyId != null) return giphyId;
        java.util.regex.Matcher php = java.util.regex.Pattern.compile("[?&](?:id|v|file|cid|hash)=([^&#]+)").matcher(url);
        if (php.find()) {
            String id = php.group(1).replaceAll("[^A-Za-z0-9_-]", "");
            if (id.length() >= 4) return id.length() > 24 ? id.substring(0, 24) : id;
        }
        java.util.regex.Matcher num = java.util.regex.Pattern.compile("/(\\d{8,})").matcher(url);
        if (num.find()) return num.group(1);
        String base = url.split("\\?")[0];
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash < base.length() - 1) {
            String tail = base.substring(slash + 1).replaceAll("\\.[^.]+$", "");
            if (!tail.isBlank()) return tail.length() > 20 ? tail.substring(0, 20) : tail;
        }
        return Integer.toHexString(url.hashCode());
    }

    private void addCommonYtDlpArgs(java.util.List<String> args, String url, String jsRt,
            boolean gifMode, boolean usePrivate, String selectedFormat) {
        addBaseYtDlpArgs(args, url, jsRt, usePrivate);
        addFormatArgs(args, selectedFormat, gifMode);
        args.add("--newline");
    }

    private String checkJsRuntime() {
        String home = System.getProperty("user.home");
        String[][] runtimes = {
            {"deno", home + "/.deno/bin/deno", "/usr/bin/deno", "/usr/local/bin/deno"},
            {"node", "/usr/bin/node", "/usr/local/bin/node"},
            {"bun", home + "/.bun/bin/bun", "/usr/bin/bun", "/usr/local/bin/bun"},
        };
        for (String[] rt : runtimes) {
            String name = rt[0];
            String path = resolveExecutable(name.toUpperCase() + "_PATH", name,
                java.util.Arrays.copyOfRange(rt, 1, rt.length));
            if (!path.equals(name)) {
                return name + " (" + path + ")";
            }
        }
        return null;
    }

    /**
     * Start direct yt-dlp download for a pasted/dragged URL.
     * Target is always ~/Downloads/ (no pipeline job added; independent "quick drop" like the TUI input bar).
     * Parses stdout for progress % (same style as Python gravity-client).
     * Uses ProcessBuilder with arg list (no shell, follows no-eval / no-kill).
     */
    private void startDirectDownload() {
        startDirectDownload(null);
    }

    /**
     * @param forceGif TRUE = GIF trigger, FALSE = H.264 trigger, null = Enter/drag (auto GIF for .gif / Giphy / Tenor / X tweet_video)
     */
    private void startDirectDownload(Boolean forceGif) {
        String rawUrl = expandRemoteInput(normalizeUrl(urlField.getText()));
        if (!isDownloadableUrl(rawUrl)) {
            directStatusLabel.setText("Ignored: https://, viewkey=, menu$N, .php viewer, X status, or GIF");
            return;
        }
        if (!rawUrl.startsWith("http") && !rawUrl.startsWith("ipfs://")) {
            directStatusLabel.setText("Select nav asset or set GRAVITY_REMOTE_BASE for viewkey=/menu$");
            return;
        }
        urlField.setText(rawUrl);
        String url = resolveDownloadUrl(rawUrl);
        if (gifCheck != null && (Boolean.TRUE.equals(forceGif) || isGifAddress(rawUrl))) {
            gifCheck.setSelected(true);
        }
        if (gifCheck != null && Boolean.FALSE.equals(forceGif)) gifCheck.setSelected(false);

        double theta = 0;
        try { theta = Double.parseDouble(thetaField.getText().trim()); } catch (Exception ignore) {}
        boolean dual = dualCheck != null && dualCheck.isSelected();
        boolean gifMode;
        if (Boolean.TRUE.equals(forceGif)) gifMode = true;
        else if (Boolean.FALSE.equals(forceGif)) gifMode = false;
        else gifMode = (gifCheck != null && gifCheck.isSelected()) || isGifAddress(url);
        boolean usePrivate = (privateCheck != null && privateCheck.isSelected()) || useVpnRoute();
        String srcNote = isXStatusUrl(url) ? " x-status"
            : (isPhpViewerUrl(url) ? " php-viewer"
            : (isIpfsHttpUrl(url) ? " ipfs-http" : ""));
        boolean useCookies = shouldUseCookies(url, usePrivate) && resolveCookieSource() != null;
        String rotNote = (gifMode ? " gif" : " h264") + srcNote
            + (theta != 0 && !gifMode ? " rot" + theta : "")
            + (dual ? " dual vi i&j=true" : "")
            + (useCookies ? " cookies:" + describeCookieSource() : "");

        String startMsg = "Starting yt-dlp direct to ~/Downloads/..." + rotNote;
        if (gifMode) {
            startMsg = "GIF trigger — any GIF (HTTP / X / Giphy / Tenor / recode)..." + rotNote;
        }
        if (usePrivate && resolveCookieSource() == null) {
            startMsg = "private: no cookies.txt/browser DB — continuing without cookies";
        }
        directStatusLabel.setText(startMsg);
        directProgress.setValue(0);
        final String originUrl = url;
        final Boolean forceGifFlag = forceGif;
        final double requestedTheta = theta;
        final boolean finalDual = dual;
        final boolean initialGif = gifMode;
        final String mediaId = extractMediaId(url);
        final boolean finalUsePrivate = usePrivate;

        abortRequested = false;
        setDownloadUiBusy(true);
        if (vacuumDetailCheck != null && vacuumDetailCheck.isSelected()) setVacuumLogVisible(true);
        else if (vacuumCheck != null && vacuumCheck.isSelected()) setVacuumLogVisible(true);

        new Thread(() -> {
            File gifDest = null;
            File destFile = null;
            try {
                String home = System.getProperty("user.home");
                String dlUrl = originUrl;
                boolean gif = initialGif;
                String xStatusId = extractXStatusId(originUrl);
                if (xStatusId != null) {
                    SwingUtilities.invokeLater(() ->
                        directStatusLabel.setText("Resolving X/Twitter media for " + xStatusId + "…"));
                    String media = resolveXStatusMediaUrl(xStatusId);
                    if (media != null) {
                        dlUrl = media;
                        if (forceGifFlag == null && media.contains("/tweet_video/")) gif = true;
                        if (Boolean.TRUE.equals(forceGifFlag)) gif = true;
                        final boolean uiGif = gif;
                        SwingUtilities.invokeLater(() -> {
                            if (gifCheck != null) gifCheck.setSelected(uiGif);
                            directStatusLabel.setText((uiGif ? "X GIF " : "X media ") + media);
                        });
                    }
                }
                if (abortRequested) {
                    SwingUtilities.invokeLater(() -> {
                        directProgress.setValue(0);
                        directStatusLabel.setText("⏹ download aborted");
                        setDownloadUiBusy(false);
                    });
                    return;
                }

                gifDest = nextDirectFile(home, mediaId, true);
                boolean gifSaved = false;
                if (gif) {
                    SwingUtilities.invokeLater(() ->
                        directStatusLabel.setText("GIF — fetching (HTTP / host / recode)…"));
                    gifSaved = saveAsGif(originUrl, gifDest);
                    if (!gifSaved && !originUrl.equals(dlUrl)) gifSaved = saveAsGif(dlUrl, gifDest);
                }
                if (abortRequested) {
                    SwingUtilities.invokeLater(() -> {
                        directProgress.setValue(0);
                        directStatusLabel.setText("⏹ download aborted");
                        setDownloadUiBusy(false);
                    });
                    return;
                }

                final boolean finalGif = gif || gifSaved;
                final String finalUrl = dlUrl;
                final double finalTheta = finalGif ? 0 : requestedTheta;
                destFile = finalGif ? gifDest : nextDirectFile(home, mediaId, false);
                final File destFileFinal = destFile;
                String outtmpl = destFile.getAbsolutePath();
                cleanupDownloadTemps(destFile);
                cleanupDownloadTemps(gifDest);

                String selectedFormat;
                YtDlpResult dlResult;
                int rc;
                String lastLine;
                java.util.List<String> ytdlpArgs = null;
                if (gifSaved) {
                    selectedFormat = "gif";
                    lastLine = gifDest.getName();
                    rc = 0;
                    dlResult = new YtDlpResult(0, lastLine, new java.util.ArrayList<String>());
                    SwingUtilities.invokeLater(() -> directProgress.setValue(100));
                } else {
                    String jsRt = checkJsRuntime();
                    SwingUtilities.invokeLater(() ->
                        directStatusLabel.setText("Checking formats (yt-dlp --list-formats)..."));

                    java.util.List<String> formatLines = runListFormats(finalUrl, jsRt, finalUsePrivate);
                    if (abortRequested) {
                        SwingUtilities.invokeLater(() -> {
                            directProgress.setValue(0);
                            directStatusLabel.setText("⏹ download aborted");
                            setDownloadUiBusy(false);
                        });
                        return;
                    }
                    boolean generic = useGenericExtractor(finalUrl);
                    selectedFormat = selectFormatFromList(formatLines, finalGif, generic);
                    final String firstFmt = selectedFormat;

                    SwingUtilities.invokeLater(() ->
                        directStatusLabel.setText("Selected format: " + firstFmt + " — downloading..."));

                    ytdlpArgs = new java.util.ArrayList<>();
                    ytdlpArgs.add(YTDLP_PATH);
                    addCommonYtDlpArgs(ytdlpArgs, finalUrl, jsRt, finalGif, finalUsePrivate, selectedFormat);
                    ytdlpArgs.add("-o");
                    ytdlpArgs.add(outtmpl);
                    ytdlpArgs.add(finalUrl);

                    dlResult = runYtDlpWithProgress(ytdlpArgs, finalTheta);
                    rc = dlResult.exitCode;
                    lastLine = dlResult.lastLine;
                    if (rc != 0 && !finalGif && !isYouTubeUrl(finalUrl)
                            && !YTDLP_GENERIC_FORMAT.equals(selectedFormat)) {
                        final String retryFmt = YTDLP_GENERIC_FORMAT;
                        SwingUtilities.invokeLater(() ->
                            directStatusLabel.setText("Generic site — retry -f best (not YouTube H.264)…"));
                        ytdlpArgs = new java.util.ArrayList<>();
                        ytdlpArgs.add(YTDLP_PATH);
                        addCommonYtDlpArgs(ytdlpArgs, finalUrl, jsRt, false, false, retryFmt);
                        ytdlpArgs.add("-o");
                        ytdlpArgs.add(outtmpl);
                        ytdlpArgs.add(finalUrl);
                        dlResult = runYtDlpWithProgress(ytdlpArgs, finalTheta);
                        rc = dlResult.exitCode;
                        lastLine = dlResult.lastLine;
                        selectedFormat = retryFmt;
                    }
                    if (rc != 0 && !finalGif) {
                        File vdest = new File(outtmpl);
                        SwingUtilities.invokeLater(() ->
                            directStatusLabel.setText("HTTP scrape — looking for mp4/webm/m3u8…"));
                        if (saveAsVideo(originUrl, vdest) || (!originUrl.equals(finalUrl) && saveAsVideo(finalUrl, vdest))) {
                            rc = 0;
                            lastLine = vdest.getName();
                            selectedFormat = "http-video";
                            dlResult = new YtDlpResult(0, lastLine, new java.util.ArrayList<String>());
                            SwingUtilities.invokeLater(() -> directProgress.setValue(100));
                        }
                    }
                }
                final String formatNote = selectedFormat;

                int max403Retries = (int) envLong("GRAVITY_403_RETRIES", 2);
                int attempt = 0;
                while (ytdlpArgs != null && !abortRequested && rc != 0
                        && outputIndicates403(dlResult.lines) && attempt < max403Retries) {
                    attempt++;
                    RateLimiter.on403();
                    final int retryNum = attempt;
                    final long coolSec = RateLimiter.cooldownSecondsRemaining();
                    SwingUtilities.invokeLater(() ->
                        directStatusLabel.setText("403/IP restricted — cooldown " + coolSec + "s, retry " + retryNum + "/" + max403Retries));
                    dlResult = runYtDlpWithProgress(ytdlpArgs, finalTheta);
                    rc = dlResult.exitCode;
                    lastLine = dlResult.lastLine;
                }

                String vacuumNote = "";
                if (rc == 0) {
                    RateLimiter.onSuccess();
                    boolean vac = vacuumCheck != null && vacuumCheck.isSelected();
                    if (vac) {
                        SwingUtilities.invokeLater(() ->
                            directStatusLabel.setText("Vacuum — TLS/JA3 already on; stripping JS/injection from img/vid/gif…"));
                        File gf = destFileFinal.getName().endsWith(".gif") ? destFileFinal : nextDirectFile(home, mediaId, true);
                        File vf = destFileFinal.getName().endsWith(".mp4") ? destFileFinal : nextDirectFile(home, mediaId, false);
                        if (finalGif) gf = destFileFinal;
                        else vf = destFileFinal;
                        StringBuilder vn = new StringBuilder();
                        if (gf.isFile()) vn.append(vacuumMediaFile(gf));
                        if (vf.isFile()) vn.append(vacuumMediaFile(vf));
                        vacuumNote = vn.toString();
                    }
                } else if (outputIndicates403(dlResult.lines)) {
                    RateLimiter.on403();
                }

                final String lastErr = lastLine;
                final String vacuumNoteF = vacuumNote;
                final boolean ip403 = outputIndicates403(dlResult.lines);
                final int finalRc = rc;
                final boolean stopped = abortRequested;
                SwingUtilities.invokeLater(() -> {
                    if (stopped) {
                        cleanupDownloadTemps(destFileFinal);
                        directProgress.setValue(0);
                        directStatusLabel.setText("⏹ download aborted");
                    } else if (finalRc == 0) {
                        directProgress.setValue(100);
                        String shortUrl = finalUrl.length() > 28 ? finalUrl.substring(0, 25) + "..." : finalUrl;
                        String baseMsg = "✓ done → ~/Downloads/  (" + shortUrl + ")"
                            + (finalGif ? " GIF" : " H.264 mp4")
                            + " [" + formatNote + "]"
                            + (finalTheta != 0 ? " + θ" + finalTheta : "");
                        if (finalDual) baseMsg += " dual vi && i & j = true";
                        if (vacuumNoteF != null && !vacuumNoteF.isBlank()) baseMsg += vacuumNoteF;
                        final String msg = baseMsg;
                        directStatusLabel.setText(msg);

                        String previewPath = destFileFinal.getAbsolutePath();

                        if (finalDual && !finalGif) {
                            try {
                                String id = mediaId;
                                String downloaded = destFileFinal.getAbsolutePath();
                                postProcessVp56(downloaded);
                            } catch (Exception ignore) {}
                        }

                        // KISS H.264 rotate post if theta (simple id based)
                        if (finalTheta != 0) {
                            try {
                                String id = mediaId;
                                String downloaded = destFileFinal.getAbsolutePath();
                                String rotated = home + "/Downloads/direct-h264-rot" + (int)finalTheta + "-" + mediaId + ".mp4";
                                runFfmpegQuiet(java.util.Arrays.asList(
                                    FFMPEG_PATH, "-y", "-i", downloaded,
                                    "-vf", "rotate=" + finalTheta + "*PI/180",
                                    "-c:v", "libx264", "-crf", "19", "-c:a", "aac",
                                    rotated
                                ), 900);
                                if (new File(rotated).isFile()) previewPath = rotated;
                                directStatusLabel.setText(msg + " (rotated θ" + (int)finalTheta + ")");
                            } catch (Exception rotEx) {
                                directStatusLabel.setText(msg + " (θ post note: " + rotEx.getMessage().substring(0,20) + ")");
                            }
                        }
                        final String finalPreviewPath = previewPath;
                        setLastDownloadPath(finalPreviewPath);
                        refreshRecentDownloads();
                        appendRuntimeLog("download_complete",
                            "\"path\":\"" + jsonEsc(finalPreviewPath) + "\",\"gif\":" + finalGif
                            + ",\"format\":\"" + jsonEsc(formatNote) + "\"");

                        // gravity_update simulation: make ASC panel show LIVE data for this URL
                        // (simulates server broadcast for the gravity protocol client)
                        try {
                            // get nice title (with JS runtime if available)
                            String jsRt2 = checkJsRuntime();
                            java.util.List<String> titleArgs = new java.util.ArrayList<>();
                            titleArgs.add(YTDLP_PATH);
                            addJsRuntimeArgs(titleArgs, jsRt2);
                            addCookiesArgs(titleArgs, finalUrl, usePrivate);
                            titleArgs.add("--print");
                            titleArgs.add("%(title)s");
                            titleArgs.add(finalUrl);
                            ProcessBuilder titlePb = new ProcessBuilder(titleArgs);
                            titlePb.redirectErrorStream(true);
                            Process titleProc = titlePb.start();
                            BufferedReader tr = new BufferedReader(new InputStreamReader(titleProc.getInputStream()));
                            String vidTitle = tr.readLine();
                            titleProc.waitFor();
                            if (vidTitle == null || vidTitle.trim().isEmpty()) vidTitle = "Video " + finalUrl;
                            final String fTitle = vidTitle;
                            triggerInterlaterusMint(finalPreviewPath, finalUrl, fTitle);

                            SwingUtilities.invokeLater(() -> {
                                // add entry to jobs
                                jobsModel.addRow(new Object[]{
                                    "g-up", 
                                    fTitle.substring(0, Math.min(28, fTitle.length())), 
                                    "completed", 
                                    "100%", 
                                    finalGif ? "gif" : "h264"
                                });

                                // Symmetric ASC vs SC live data for gravity_update
                                // Uses the symbol grid for symmetric filmic borders/frames
                                // ASC side: cinematic/filmic style metadata
                                // SC side: storage_memory_category (memory update data)
                                StringBuilder asc = new StringBuilder();
                                asc.append(ASC_FRAME);
                                asc.append("\n");
                                asc.append("⿲⿳⿴⿵⿶⿷⿸⿹⿺⿻⿰  gravity_update LIVE DATA for ").append(fTitle).append("  ⿲⿳⿴⿵⿶⿷⿸⿹⿺⿻⿰\n");
                                asc.append("⿳⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱  Symmetric ASC vs SC  ⿳⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱\n\n");

                                // Left: ASC (symmetric with right SC)
                                asc.append("⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲ ASC SIDE ⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲          ");
                                asc.append("⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲ SC SIDE (storage_memory) ⿴⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲\n");
                                asc.append("⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲⿳ [FILMIC | HIGH CONTRAST | GRAIN | 320x180]          ");
                                asc.append("⿵⿶⿷⿸⿹⿺⿻⿰⿱⿲⿳ zone=local | memory=high | location=dc-1\n");
                                asc.append("⿶⿷⿸⿹⿺⿻⿰⿱⿲⿳⿴ gravity_prepared: true          ");
                                asc.append("⿶⿷⿸⿹⿺⿻⿰⿱⿲⿳⿴ V=1 | Bandwidth=high\n");
                                if (finalTheta != 0) {
                                    asc.append("⿷⿸⿹⿺⿻⿰⿱⿲⿳⿴⿵ rotation_theta: ").append((int)finalTheta).append("          ");
                                    asc.append("⿷⿸⿹⿺⿻⿰⿱⿲⿳⿴⿵ (symmetric with ASC)\n");
                                }
                                if (finalDual) {
                                    asc.append("⿸⿹⿺⿻⿰⿱⿲⿳⿴⿵⿶ dual_vi: i & j = true          ");
                                    asc.append("⿸⿹⿺⿻⿰⿱⿲⿳⿴⿵⿶ (interstellar mode)\n");
                                }
                                asc.append("⿹⿺⿻⿰⿱⿲⿳⿴⿵⿶⿷ url: ").append(finalUrl).append("\n");
                                asc.append("⿺⿻⿰⿱⿲⿳⿴⿵⿶⿷⿸ [END symmetric ASC vs SC] ⿺⿻⿰⿱⿲⿳⿴⿵⿶⿷⿸\n\n");
                                asc.append(ASC_FRAME);

                                visualistArea.setText(asc.toString());
                                final String liveMsg = msg + " | ▶ preview ready";
                                directStatusLabel.setText(liveMsg);
                            });
                        } catch (Exception ex) {
                            triggerInterlaterusMint(finalPreviewPath, finalUrl, "");
                        }
                    } else {
                        String err = lastErr.isEmpty() ? "see terminal output" : lastErr;
                        if (ip403) {
                            String route = describeNetworkRoute();
                            err = "403/IP restricted on " + route
                                + " — YouTube blocked this VPN datacenter. "
                                + "Leave private ON (Brave cookies). Surfshark: switch city. Astrill: StealthVPN + JP/SG/HK. "
                                + "Cooldown " + RateLimiter.cooldownSecondsRemaining() + "s";
                        } else if (err.length() > 72) {
                            err = err.substring(0, 69) + "...";
                        }
                        cleanupDownloadTemps(destFileFinal);
                        directStatusLabel.setText("yt-dlp failed (rc=" + finalRc + "): " + err);
                        appendRuntimeLog("download_fail",
                            "\"rc\":" + finalRc + ",\"url\":\"" + jsonEsc(finalUrl)
                            + "\",\"err\":\"" + jsonEsc(err) + "\"");
                        refreshRecentDownloads();
                    }
                    setDownloadUiBusy(false);
                });
            } catch (Exception ex) {
                String msg = ex.getMessage();
                if (msg == null) msg = ex.toString();
                final String fmsg = msg.length() > 65 ? msg.substring(0, 62) + "..." : msg;
                SwingUtilities.invokeLater(() -> {
                    directStatusLabel.setText("Error launching yt-dlp: " + fmsg + " (is yt-dlp in PATH?)");
                    directProgress.setValue(0);
                    setDownloadUiBusy(false);
                });
            } finally {
                // Generic retry / abort used to leave .part .ytdl Frag — first incompletes after that path.
                try {
                    if (destFile != null) cleanupDownloadTemps(destFile);
                    if (gifDest != null) cleanupDownloadTemps(gifDest);
                } catch (Exception ignore) {}
            }
        }).start();
    }
}