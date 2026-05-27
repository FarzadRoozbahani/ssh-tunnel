"""
SSH Tunnel — v3.0.0
Modern SSH SOCKS5 / System-wide VPN client for Windows
Features: multi-profile, system tray, auto-reconnect, EN/FA i18n
"""

import customtkinter as ctk
import tkinter as tk
from tkinter import messagebox, filedialog, simpledialog
import json, os, sys, threading, socket, time, select, logging, queue
from datetime import datetime
from pathlib import Path
import paramiko

# ── Windows-only imports (graceful fallback for dev on Linux) ──
try:
    import winreg, ctypes
    HAS_WIN = True
except ImportError:
    HAS_WIN = False

try:
    from pystray import Icon as TrayIcon, Menu as TrayMenu, MenuItem as TrayItem
    from PIL import Image, ImageDraw
    HAS_TRAY = True
except ImportError:
    HAS_TRAY = False

# ───────────────────────────────────────────────────────────────
APP_NAME    = "SSH Tunnel"
APP_VERSION = "3.0.0"
CONFIG_FILE = os.path.join(os.path.expanduser("~"), ".ssh_vpn_config.json")
LOG_FILE    = os.path.join(os.path.expanduser("~"), ".ssh_vpn.log")
PROXY_HOST  = "127.0.0.1"
PROXY_PORT  = 9000

DEFAULT_BYPASS = []  # empty by default — user adds their own bypass domains

ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

logging.basicConfig(filename=LOG_FILE, level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# ── Design tokens ───────────────────────────────────────────────
C = {
    "bg":       "#0f1117", "surface":  "#1a1d27",
    "card":     "#21253a", "border":   "#2e3250",
    "accent":   "#4f6ef7", "accent2":  "#6b84ff",
    "success":  "#22c55e", "danger":   "#ef4444",
    "warning":  "#f59e0b", "text":     "#e2e8f0",
    "muted":    "#8892a4", "input_bg": "#161929",
}

# ╔══════════════════════════════════════════════════════════════╗
# ║  i18n                                                        ║
# ╚══════════════════════════════════════════════════════════════╝
STRINGS = {
    "en": {
        # Tabs
        "tab_connect":  "Connect",
        "tab_settings": "Settings",
        "tab_bypass":   "Bypass",
        "tab_profiles": "Profiles",
        "tab_log":      "Log",
        # Header / status
        "status_ready":       "Ready to connect",
        "status_connecting":  "Connecting…",
        "status_connected":   "Connected",
        "status_disconnected":"Disconnected",
        "status_reconnecting":"Reconnecting…",
        "status_lost":        "Connection lost",
        # Connection card
        "sec_server":   "SERVER",
        "lbl_host":     "Host / IP",
        "lbl_port":     "SSH Port",
        "lbl_user":     "Username",
        "lbl_pass":     "Password",
        "lbl_use_key":  "Use SSH Key file",
        "lbl_key_path": "Key path",
        "btn_browse":   "Browse",
        "ph_host":      "your-server.com or 1.2.3.4",
        "ph_pass":      "password",
        # Mode card
        "sec_mode":         "Connection Mode",
        "mode_proxy":       "SOCKS5 Proxy",
        "mode_proxy_desc":  "Only apps that support proxy will use the tunnel",
        "mode_system":      "System-wide VPN",
        "mode_system_desc": "All Windows traffic routed through the tunnel (requires Admin)",
        # Buttons
        "btn_connect":    "▶  Connect",
        "btn_connecting": "⌛  Connecting…",
        "btn_connected":  "✓  Connected",
        "btn_disconnect": "■  Disconnect",
        # Settings tab
        "sec_proxy_port":   "SOCKS5 Local Port",
        "lbl_proxy_port":   "Local port",
        "lbl_auto_connect": "Auto-connect on startup",
        "lbl_auto_reconnect":"Auto-reconnect on drop",
        "lbl_reconnect_delay":"Reconnect delay (sec)",
        "lbl_minimize_tray":"Minimize to system tray",
        "lbl_language":     "Language",
        "sec_options":      "Options",
        "btn_save":         "💾  Save Settings",
        "saved_ok":         "Settings saved",
        # Bypass tab
        "bypass_info":      "Domains below bypass the tunnel and connect directly",
        "ph_new_domain":    "e.g. example.com",
        "btn_add":          "+ Add",
        "btn_remove":       "✕ Remove",
        "btn_reset":        "Reset to defaults",
        # Profiles tab
        "sec_profiles":     "Profiles",
        "btn_new_profile":  "+ New",
        "btn_del_profile":  "Delete",
        "btn_rename_profile":"Rename",
        "lbl_active":       "Active:",
        "profile_exists":   "A profile with that name already exists.",
        "profile_name_prompt":"Profile name:",
        "profile_rename_prompt":"New name:",
        # Log tab
        "btn_clear_log":    "🗗  Clear",
        "lbl_log_title":    "Connection log",
        # Messages
        "err_no_host":      "Please enter the server address.",
        "err_no_user":      "Please enter a username.",
        "err_auth":         "Authentication failed — wrong username or password.",
        "err_ssh":          "SSH error: ",
        "err_timeout":      "Timeout — server did not respond.",
        "err_refused":      "Connection refused — SSH port closed.",
        "err_proxy":        "SSH connected but could not set system proxy.\nRun as Administrator.",
        "err_generic":      "Error: ",
        "msg_conn_lost":    "SSH connection was lost.",
        "msg_disconnected": "Disconnected",
        "msg_connected_proxy": "Connected — Proxy mode | port ",
        "msg_connected_system":"Connected — System VPN | port ",
        "win_title_lost":   "Connection Lost",
        "win_title_err":    "Connection Error",
        # Tray
        "tray_show":        "Show window",
        "tray_connect":     "Connect",
        "tray_disconnect":  "Disconnect",
        "tray_quit":        "Quit",
    },
    "fa": {
        "tab_connect":  "اتصال",
        "tab_settings": "تنظیمات",
        "tab_bypass":   "Bypass",
        "tab_profiles": "پروفایل‌ها",
        "tab_log":      "گزارش",
        "status_ready":       "آماده اتصال",
        "status_connecting":  "در حال اتصال…",
        "status_connected":   "متصل",
        "status_disconnected":"قطع",
        "status_reconnecting":"اتصال مجدد…",
        "status_lost":        "اتصال قطع شد",
        "sec_server":   "سرور",
        "lbl_host":     "هاست / IP",
        "lbl_port":     "پورت SSH",
        "lbl_user":     "نام کاربری",
        "lbl_pass":     "رمز عبور",
        "lbl_use_key":  "استفاده از SSH Key",
        "lbl_key_path": "مسیر کلید",
        "btn_browse":   "انتخاب",
        "ph_host":      "your-server.com or 1.2.3.4",
        "ph_pass":      "رمز عبور",
        "sec_mode":         "حالت اتصال",
        "mode_proxy":       "SOCKS5 Proxy",
        "mode_proxy_desc":  "فقط برنامه‌هایی که پروکسی را پشتیبانی می‌کنند از تونل استفاده می‌کنند",
        "mode_system":      "System-wide VPN",
        "mode_system_desc": "کل ترافیک ویندوز از تونل رد می‌شود (نیاز به Admin)",
        "btn_connect":    "▶  اتصال",
        "btn_connecting": "⌛  در حال اتصال…",
        "btn_connected":  "✓  متصل",
        "btn_disconnect": "■  قطع اتصال",
        "sec_proxy_port":   "پورت لوکال SOCKS5",
        "lbl_proxy_port":   "پورت لوکال",
        "lbl_auto_connect": "اتصال خودکار هنگام اجرا",
        "lbl_auto_reconnect":"اتصال مجدد خودکار پس از قطع",
        "lbl_reconnect_delay":"تأخیر اتصال مجدد (ثانیه)",
        "lbl_minimize_tray":"کمینه کردن در System Tray",
        "lbl_language":     "زبان",
        "sec_options":      "گزینه‌ها",
        "btn_save":         "💾  ذخیره تنظیمات",
        "saved_ok":         "تنظیمات ذخیره شد",
        "bypass_info":      "دامنه‌های زیر از تونل عبور نمی‌کنند و مستقیم وصل می‌شوند",
        "ph_new_domain":    "مثلاً example.com",
        "btn_add":          "+ افزودن",
        "btn_remove":       "✕ حذف",
        "btn_reset":        "بازنشانی",
        "sec_profiles":     "پروفایل‌ها",
        "btn_new_profile":  "+ جدید",
        "btn_del_profile":  "حذف",
        "btn_rename_profile":"تغییر نام",
        "lbl_active":       "فعال:",
        "profile_exists":   "پروفایلی با این نام وجود دارد.",
        "profile_name_prompt":"نام پروفایل:",
        "profile_rename_prompt":"نام جدید:",
        "btn_clear_log":    "🗗  پاک کردن",
        "lbl_log_title":    "گزارش اتصال",
        "err_no_host":      "لطفاً آدرس سرور را وارد کنید.",
        "err_no_user":      "لطفاً نام کاربری را وارد کنید.",
        "err_auth":         "خطای احراز هویت — نام کاربری یا رمز عبور اشتباه است.",
        "err_ssh":          "خطای SSH: ",
        "err_timeout":      "تایم‌اوت — سرور پاسخ نداد.",
        "err_refused":      "اتصال رد شد — پورت SSH بسته است.",
        "err_proxy":        "SSH متصل شد ولی پروکسی سیستم تنظیم نشد.\nبرنامه را با Admin اجرا کنید.",
        "err_generic":      "خطا: ",
        "msg_conn_lost":    "ارتباط با سرور SSH قطع شد.",
        "msg_disconnected": "اتصال قطع شد",
        "msg_connected_proxy": "متصل — حالت Proxy | پورت ",
        "msg_connected_system":"متصل — System VPN | پورت ",
        "win_title_lost":   "اتصال قطع شد",
        "win_title_err":    "خطای اتصال",
        "tray_show":        "نمایش پنجره",
        "tray_connect":     "اتصال",
        "tray_disconnect":  "قطع اتصال",
        "tray_quit":        "خروج",
    },
}

def T(key, lang="en"):
    return STRINGS.get(lang, STRINGS["en"]).get(key, STRINGS["en"].get(key, key))

# ╔══════════════════════════════════════════════════════════════╗
# ║  Config                                                      ║
# ╚══════════════════════════════════════════════════════════════╝
def _default_profile():
    return {
        "host": "", "port": "22", "username": "", "password": "",
        "use_key": False, "key_path": "",
        "mode": "proxy",
        "bypass_domains": DEFAULT_BYPASS.copy(),
        "proxy_port": PROXY_PORT,
    }

def _default_config():
    return {
        "language": "en",
        "auto_connect": False,
        "auto_reconnect": True,
        "reconnect_delay": 5,
        "minimize_tray": True,
        "active_profile": "Default",
        "profiles": {"Default": _default_profile()},
    }

def load_config():
    try:
        if os.path.exists(CONFIG_FILE):
            data = json.loads(Path(CONFIG_FILE).read_text("utf-8"))
            base = _default_config()
            base.update(data)
            if "profiles" not in base or not base["profiles"]:
                base["profiles"] = {"Default": _default_profile()}
            if base["active_profile"] not in base["profiles"]:
                base["active_profile"] = next(iter(base["profiles"]))
            return base
    except Exception as e:
        logger.error(f"load_config: {e}")
    return _default_config()

def save_config(cfg):
    try:
        Path(CONFIG_FILE).write_text(json.dumps(cfg, indent=2, ensure_ascii=False), "utf-8")
    except Exception as e:
        logger.error(f"save_config: {e}")

# ╔══════════════════════════════════════════════════════════════╗
# ║  Windows proxy helpers                                       ║
# ╚══════════════════════════════════════════════════════════════╝
def set_windows_proxy(enable, host=PROXY_HOST, port=PROXY_PORT, bypass=None,
                      http_port=9001):
    if not HAS_WIN:
        return True
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
            0, winreg.KEY_WRITE)
        if enable:
            # Set HTTP, HTTPS, and SOCKS proxies separately so all browsers work.
            # HTTP/HTTPS → our local HTTP CONNECT proxy (supports remote DNS, works in browsers)
            # SOCKS      → our SOCKS5 server (for apps like Telegram, curl, etc.)
            proxy_str = (
                f"http={host}:{http_port};"
                f"https={host}:{http_port};"
                f"socks={host}:{port}"
            )
            winreg.SetValueEx(key, "ProxyEnable",   0, winreg.REG_DWORD, 1)
            winreg.SetValueEx(key, "ProxyServer",   0, winreg.REG_SZ,    proxy_str)
            # Filter out ext: entries — Windows does not understand them
            bp_list = [b for b in (bypass or []) if b and not b.startswith("ext:")]
            bp_str  = ";".join(bp_list) + ";<local>"
            winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, bp_str)
        else:
            winreg.SetValueEx(key, "ProxyEnable",   0, winreg.REG_DWORD, 0)
            winreg.SetValueEx(key, "ProxyServer",   0, winreg.REG_SZ,    "")
            winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ,    "")
        winreg.CloseKey(key)
        # INTERNET_OPTION_SETTINGS_CHANGED = 39, INTERNET_OPTION_REFRESH = 37
        ctypes.windll.wininet.InternetSetOptionW(0, 39, 0, 0)
        ctypes.windll.wininet.InternetSetOptionW(0, 37, 0, 0)
        return True
    except Exception as e:
        logger.error(f"set_windows_proxy: {e}")
        return False


# ╔══════════════════════════════════════════════════════════════╗
# ║  SOCKS5 server                                               ║
# ╚══════════════════════════════════════════════════════════════╝
def _recvall(sock, n: int) -> bytes:
    """Reliably read exactly n bytes from a blocking socket."""
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Socket closed mid-read")
        buf += chunk
    return buf


class SOCKSHandler:
    def __init__(self, ssh_client, local_host, local_port):
        self.ssh        = ssh_client
        self.local_host = local_host
        self.local_port = local_port
        self._sock      = None
        self._running   = False

    def start(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind((self.local_host, self.local_port))
        self._sock.listen(100)
        self._sock.settimeout(1.0)
        self._running = True
        threading.Thread(target=self._loop, daemon=True, name="socks-accept").start()
        logger.info(f"SOCKS5 listening on {self.local_host}:{self.local_port}")

    def stop(self):
        self._running = False
        try:
            self._sock and self._sock.close()
        except Exception:
            pass

    def _loop(self):
        while self._running:
            try:
                client, addr = self._sock.accept()
                client.setblocking(True)
                client.settimeout(30)
                threading.Thread(target=self._handle, args=(client,),
                                 daemon=True, name=f"socks-{addr[1]}").start()
            except socket.timeout:
                continue
            except Exception as e:
                if self._running:
                    logger.error(f"socks accept: {e}")
                break

    def _handle(self, client: socket.socket):
        ch = None
        try:
            # ── greeting ─────────────────────────────────────
            header = _recvall(client, 2)
            if header[0] != 5:
                logger.debug(f"socks: not SOCKS5 (ver={header[0]})")
                return
            nmethods = header[1]
            if nmethods > 0:
                _recvall(client, nmethods)      # discard method list
            client.sendall(b"\x05\x00")         # NO AUTH

            # ── request ──────────────────────────────────────
            req   = _recvall(client, 4)
            ver, cmd, _, atype = req
            if ver != 5 or cmd != 1:
                client.sendall(b"\x05\x07\x00\x01" + b"\x00" * 6)
                return

            if atype == 1:                      # IPv4
                dest_host = socket.inet_ntoa(_recvall(client, 4))
            elif atype == 3:                    # domain name
                length    = _recvall(client, 1)[0]
                dest_host = _recvall(client, length).decode("utf-8", errors="replace")
            elif atype == 4:                    # IPv6
                dest_host = socket.inet_ntop(socket.AF_INET6, _recvall(client, 16))
            else:
                client.sendall(b"\x05\x08\x00\x01" + b"\x00" * 6)
                return

            dest_port = int.from_bytes(_recvall(client, 2), "big")
            logger.debug(f"CONNECT {dest_host}:{dest_port}")

            # ── open SSH direct-tcpip channel ─────────────────
            transport = self.ssh.get_transport()
            if transport is None or not transport.is_active():
                client.sendall(b"\x05\x03\x00\x01" + b"\x00" * 6)
                return

            try:
                ch = transport.open_channel(
                    "direct-tcpip",
                    (dest_host, dest_port),
                    ("127.0.0.1", 0),
                    timeout=15,
                )
            except Exception as e:
                logger.debug(f"open_channel {dest_host}:{dest_port} failed: {e}")
                client.sendall(b"\x05\x05\x00\x01" + b"\x00" * 6)
                return

            if ch is None:
                client.sendall(b"\x05\x05\x00\x01" + b"\x00" * 6)
                return

            # ── success reply (BND 0.0.0.0:0) ────────────────
            client.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")

            # ── relay bytes in both directions ────────────────
            self._relay(client, ch)

        except Exception as e:
            logger.debug(f"socks handle error: {e}")
        finally:
            try: client.close()
            except Exception: pass
            try:
                if ch: ch.close()
            except Exception: pass

    def _relay(self, sock: socket.socket, ch):
        """Two-thread relay — avoids select() which is unreliable on Windows
        with paramiko Channel objects."""
        done = threading.Event()

        def pump(src, dst):
            try:
                while not done.is_set():
                    try:
                        data = src.recv(32768)
                    except Exception:
                        break
                    if not data:
                        break
                    try:
                        dst.sendall(data)
                    except Exception:
                        break
            finally:
                done.set()

        t1 = threading.Thread(target=pump, args=(sock, ch),   daemon=True)
        t2 = threading.Thread(target=pump, args=(ch,   sock), daemon=True)
        t1.start()
        t2.start()
        done.wait()
        t1.join(timeout=3)
        t2.join(timeout=3)


# ╔══════════════════════════════════════════════════════════════╗
# ║  HTTP CONNECT proxy  (bridges browsers → our SOCKS5)        ║
# ╚══════════════════════════════════════════════════════════════╝
class HTTPProxyHandler:
    """
    A minimal HTTP CONNECT proxy that listens locally and forwards
    all connections through our SOCKS5 tunnel.
    Browsers set to HTTP proxy 127.0.0.1:HTTP_PORT will work correctly
    including remote DNS resolution (no DNS leaks).
    """

    def __init__(self, socks_host: str, socks_port: int,
                 local_host: str = "127.0.0.1", local_port: int = 9001):
        self.socks_host  = socks_host
        self.socks_port  = socks_port
        self.local_host  = local_host
        self.local_port  = local_port
        self._sock       = None
        self._running    = False

    def start(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind((self.local_host, self.local_port))
        self._sock.listen(100)
        self._sock.settimeout(1.0)
        self._running = True
        threading.Thread(target=self._loop, daemon=True, name="http-proxy-accept").start()
        logger.info(f"HTTP proxy listening on {self.local_host}:{self.local_port}")

    def stop(self):
        self._running = False
        try:
            self._sock and self._sock.close()
        except Exception:
            pass

    def _loop(self):
        while self._running:
            try:
                client, addr = self._sock.accept()
                client.setblocking(True)
                client.settimeout(30)
                threading.Thread(target=self._handle, args=(client,),
                                 daemon=True, name=f"http-{addr[1]}").start()
            except socket.timeout:
                continue
            except Exception as e:
                if self._running:
                    logger.error(f"http-proxy accept: {e}")
                break

    def _handle(self, client: socket.socket):
        upstream = None
        try:
            # Read the HTTP request line + headers
            req = b""
            while b"\r\n\r\n" not in req:
                chunk = client.recv(4096)
                if not chunk:
                    return
                req += chunk

            first_line = req.split(b"\r\n")[0].decode("utf-8", errors="replace")
            parts = first_line.split()
            if len(parts) < 3:
                return

            method = parts[0].upper()

            if method == "CONNECT":
                # HTTPS tunnel: CONNECT host:port HTTP/1.x
                host_port = parts[1]
                if ":" in host_port:
                    dest_host, dest_port = host_port.rsplit(":", 1)
                    dest_port = int(dest_port)
                else:
                    dest_host, dest_port = host_port, 443

                upstream = self._connect_via_socks5(dest_host, dest_port)
                if upstream is None:
                    client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                    return
                client.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")

            else:
                # Plain HTTP: GET http://host/path HTTP/1.x
                # Extract host from URL or Host header
                dest_host, dest_port = self._parse_http_host(req, parts)
                if not dest_host:
                    client.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
                    return

                upstream = self._connect_via_socks5(dest_host, dest_port)
                if upstream is None:
                    client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                    return
                # Forward the original request to the upstream
                upstream.sendall(req)

            # Relay bytes in both directions
            self._relay(client, upstream)

        except Exception as e:
            logger.debug(f"http-proxy handle: {e}")
        finally:
            try: client.close()
            except Exception: pass
            try:
                if upstream: upstream.close()
            except Exception: pass

    def _parse_http_host(self, raw: bytes, parts: list) -> tuple:
        """Extract (host, port) from a plain HTTP request."""
        try:
            # Try from the URL in the request line
            url = parts[1].decode("utf-8", errors="replace")
            if url.startswith("http://"):
                url = url[7:]
            host_part = url.split("/")[0]
            if ":" in host_part:
                h, p = host_part.rsplit(":", 1)
                return h, int(p)
            return host_part, 80
        except Exception:
            pass
        # Fall back to Host: header
        for line in raw.split(b"\r\n")[1:]:
            if line.lower().startswith(b"host:"):
                hval = line[5:].strip().decode("utf-8", errors="replace")
                if ":" in hval:
                    h, p = hval.rsplit(":", 1)
                    return h, int(p)
                return hval, 80
        return "", 80

    def _connect_via_socks5(self, dest_host: str, dest_port: int):
        """Open a connection to dest via our local SOCKS5 proxy."""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(15)
            s.connect((self.socks_host, self.socks_port))

            # SOCKS5 handshake
            s.sendall(b"\x05\x01\x00")                  # ver=5, 1 method: no-auth
            if s.recv(2) != b"\x05\x00":
                s.close(); return None

            # CONNECT request with domain name
            host_b  = dest_host.encode("utf-8")
            request = (bytes([5, 1, 0, 3, len(host_b)]) +
                       host_b +
                       dest_port.to_bytes(2, "big"))
            s.sendall(request)

            resp = _recvall(s, 10)
            if resp[1] != 0:                               # non-zero = error
                s.close(); return None

            s.settimeout(None)
            return s
        except Exception as e:
            logger.debug(f"socks5 connect for http-proxy: {e}")
            return None

    def _relay(self, a: socket.socket, b: socket.socket):
        done = threading.Event()
        def pump(src, dst):
            try:
                while not done.is_set():
                    d = src.recv(32768)
                    if not d: break
                    dst.sendall(d)
            except Exception:
                pass
            finally:
                done.set()
        threading.Thread(target=pump, args=(a, b), daemon=True).start()
        threading.Thread(target=pump, args=(b, a), daemon=True).start()
        done.wait()


# ╔══════════════════════════════════════════════════════════════╗
# ║  Tunnel Manager                                              ║
# ╚══════════════════════════════════════════════════════════════╝
HTTP_PROXY_PORT = 9001   # local HTTP CONNECT proxy port

class TunnelManager:
    def __init__(self):
        self.ssh: paramiko.SSHClient | None = None
        self.socks: SOCKSHandler | None = None
        self.http_proxy: HTTPProxyHandler | None = None
        self.connected = False
        self._profile: dict = {}

    # ── verify the SSH transport can actually open a channel ──
    def _verify_forwarding(self) -> tuple[bool, str]:
        try:
            ch = self.ssh.get_transport().open_channel(
                "direct-tcpip", ("1.1.1.1", 80), ("127.0.0.1", 0), timeout=8)
            if ch is None:
                return False, "Server opened no channel (AllowTcpForwarding may be off)"
            ch.close()
            return True, ""
        except Exception as e:
            return False, f"TCP forwarding blocked by server: {e}"

    def connect(self, profile: dict, lang: str) -> tuple[bool, str]:
        def t(k): return T(k, lang)
        # ── clean up any previous state first ─────────────────
        self._cleanup()
        try:
            self.ssh = paramiko.SSHClient()
            self.ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            kw = dict(
                hostname = profile["host"].strip(),
                port     = int(profile["port"]),
                username = profile["username"].strip(),
                timeout  = 15,
                # keep-alive so the watchdog can detect drops quickly
                banner_timeout = 15,
            )
            if profile.get("use_key") and profile.get("key_path"):
                kw["key_filename"] = profile["key_path"]
            else:
                kw["password"] = profile["password"]

            logger.info(f"Connecting to {kw['hostname']}:{kw['port']} as {kw['username']}")
            self.ssh.connect(**kw)

            # Enable SSH keep-alive packets (every 30 s)
            self.ssh.get_transport().set_keepalive(30)

            # ── verify TCP forwarding works before advertising success ──
            ok, reason = self._verify_forwarding()
            if not ok:
                self._cleanup()
                return False, reason

            port   = int(profile.get("proxy_port", PROXY_PORT))
            bypass = [d.strip() for d in profile.get("bypass_domains", []) if d.strip()]

            # ── start SOCKS5 listener ──────────────────────────
            self.socks = SOCKSHandler(self.ssh, PROXY_HOST, port)
            try:
                self.socks.start()
            except OSError as e:
                self._cleanup()
                return False, f"Cannot bind port {port}: {e}\nAnother process may be using it."

            # ── start HTTP CONNECT proxy (for browsers) ────────
            http_port = int(profile.get("http_proxy_port", HTTP_PROXY_PORT))
            self.http_proxy = HTTPProxyHandler(PROXY_HOST, port, PROXY_HOST, http_port)
            try:
                self.http_proxy.start()
            except OSError as e:
                logger.warning(f"HTTP proxy port {http_port} busy, skipping: {e}")
                self.http_proxy = None

            # ── system-wide proxy (Windows registry) ──────────
            if profile.get("mode") == "system":
                http_port = int(profile.get("http_proxy_port", HTTP_PROXY_PORT))
                if not set_windows_proxy(True, PROXY_HOST, port, bypass,
                                         http_port=http_port):
                    self._cleanup()
                    return False, t("err_proxy")

            self._profile  = profile
            self.connected = True
            mode_label     = "System VPN" if profile.get("mode") == "system" else "Proxy"
            http_port = int(profile.get("http_proxy_port", HTTP_PROXY_PORT))
            msg = f"Connected — {mode_label} | SOCKS5 :{port}  HTTP :{http_port}"
            logger.info(msg)
            return True, f"✓ {msg}"

        except paramiko.AuthenticationException:
            self._cleanup()
            return False, t("err_auth")
        except paramiko.SSHException as e:
            self._cleanup()
            return False, t("err_ssh") + str(e)
        except socket.timeout:
            self._cleanup()
            return False, t("err_timeout")
        except ConnectionRefusedError:
            self._cleanup()
            return False, t("err_refused")
        except Exception as e:
            self._cleanup()
            logger.exception("connect failed")
            return False, t("err_generic") + str(e)

    def _cleanup(self):
        """Teardown SSH + SOCKS + HTTP proxy without touching Windows proxy."""
        if self.http_proxy:
            try: self.http_proxy.stop()
            except Exception: pass
            self.http_proxy = None
        if self.socks:
            try: self.socks.stop()
            except Exception: pass
            self.socks = None
        if self.ssh:
            try: self.ssh.close()
            except Exception: pass
            self.ssh = None
        self.connected = False

    def disconnect(self, mode: str = "proxy"):
        if mode == "system":
            set_windows_proxy(False)
        self._cleanup()

    def is_alive(self) -> bool:
        try:
            t = self.ssh and self.ssh.get_transport()
            return bool(t and t.is_active())
        except Exception:
            return False

# ╔══════════════════════════════════════════════════════════════╗
# ║  UI helpers                                                  ║
# ╚══════════════════════════════════════════════════════════════╝
class SecLabel(ctk.CTkLabel):
    def __init__(self, parent, text, **kw):
        super().__init__(parent, text=text,
                         font=("Segoe UI", 11, "bold"),
                         text_color=C["muted"], **kw)

class Divider(ctk.CTkFrame):
    def __init__(self, parent, **kw):
        super().__init__(parent, height=1, fg_color=C["border"], **kw)

def card(parent, **kw):
    return ctk.CTkFrame(parent, fg_color=C["card"], corner_radius=12, **kw)

def icard(parent, **kw):
    return ctk.CTkFrame(parent, fg_color=C["input_bg"], corner_radius=10, **kw)

def entry(parent, **kw):
    return ctk.CTkEntry(parent, fg_color=C["input_bg"],
                        border_color=C["border"], **kw)

def ghost_btn(parent, **kw):
    kw.setdefault("hover_color", C["border"])
    kw.setdefault("border_color", C["border"])
    return ctk.CTkButton(parent, fg_color=C["card"],
                         border_width=1, corner_radius=8, **kw)

def accent_btn(parent, **kw):
    return ctk.CTkButton(parent, fg_color=C["accent"],
                         hover_color=C["accent2"],
                         corner_radius=8, **kw)

# ╔══════════════════════════════════════════════════════════════╗
# ║  Status badge                                                ║
# ╚══════════════════════════════════════════════════════════════╝
class StatusBadge(ctk.CTkFrame):
    def __init__(self, parent, **kw):
        super().__init__(parent, fg_color="transparent", **kw)
        self._dot = ctk.CTkLabel(self, text="●", font=("Segoe UI", 11),
                                 text_color=C["danger"])
        self._dot.pack(side="left", padx=(0, 5))
        self._lbl = ctk.CTkLabel(self, text="—", font=("Segoe UI", 12),
                                 text_color=C["muted"])
        self._lbl.pack(side="left")

    def update(self, state: str, text: str = ""):
        colors = {"connected": C["success"], "connecting": C["warning"],
                  "reconnecting": C["warning"], "disconnected": C["danger"]}
        self._dot.configure(text_color=colors.get(state, C["danger"]))
        self._lbl.configure(text=text, text_color=C["text"] if state == "connected" else C["muted"])

# ╔══════════════════════════════════════════════════════════════╗
# ║  Main Application                                            ║
# ╚══════════════════════════════════════════════════════════════╝
class App(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.cfg     = load_config()
        self.tunnel  = TunnelManager()
        self._running    = True
        self._tray_icon  = None
        self._reconnect_pending = False

        self._setup_window()
        self._build_ui()
        self._load_fields()
        self._start_watchdog()
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        if self.cfg.get("auto_connect"):
            self.after(800, self._on_connect)

    # ── Lang shorthand ─────────────────────────────────────────
    @property
    def L(self):
        return self.cfg.get("language", "en")

    def t(self, key):
        return T(key, self.L)

    # ── Window setup ───────────────────────────────────────────
    def _setup_window(self):
        self.title(f"{APP_NAME}  v{APP_VERSION}")
        self.geometry("860x660")
        self.minsize(820, 600)
        self.configure(fg_color=C["bg"])

    # ══════════════════════════════════════════════════════════
    #  BUILD UI
    # ══════════════════════════════════════════════════════════
    def _build_ui(self):
        self._build_header()
        Divider(self).pack(fill="x")
        self._build_tabs()

    def _build_header(self):
        hdr = ctk.CTkFrame(self, fg_color=C["surface"], corner_radius=0, height=64)
        hdr.pack(fill="x")
        hdr.pack_propagate(False)

        lft = ctk.CTkFrame(hdr, fg_color="transparent")
        lft.pack(side="left", padx=20, fill="y")
        ctk.CTkLabel(lft, text="⬡  SSH Tunnel",
                     font=("Segoe UI", 18, "bold"),
                     text_color=C["accent"]).pack(side="left", pady=18)
        ctk.CTkLabel(lft, text=f"v{APP_VERSION}",
                     font=("Segoe UI", 11),
                     text_color=C["muted"]).pack(side="left", padx=(6, 0), pady=20)

        rgt = ctk.CTkFrame(hdr, fg_color="transparent")
        rgt.pack(side="right", padx=20, fill="y")
        self._status_badge = StatusBadge(rgt)
        self._status_badge.pack(side="right", pady=20)

    def _build_tabs(self):
        self._tabs = ctk.CTkTabview(
            self, fg_color=C["bg"],
            segmented_button_fg_color=C["surface"],
            segmented_button_selected_color=C["accent"],
            segmented_button_unselected_color=C["surface"],
            segmented_button_selected_hover_color=C["accent2"],
        )
        self._tabs.pack(fill="both", expand=True, padx=16, pady=(8, 16))

        for key in ["tab_connect","tab_bypass","tab_profiles","tab_settings","tab_log"]:
            self._tabs.add(self.t(key))

        self._build_connect_tab()
        self._build_bypass_tab()
        self._build_profiles_tab()
        self._build_settings_tab()
        self._build_log_tab()

    # ── CONNECT TAB ────────────────────────────────────────────
    def _build_connect_tab(self):
        tab = self._tabs.tab(self.t("tab_connect"))
        tab.configure(fg_color="transparent")

        # Server card
        sc = card(tab)
        sc.pack(fill="x", pady=(8, 10))
        SecLabel(sc, self.t("sec_server")).pack(anchor="w", padx=16, pady=(14, 8))

        r1 = ctk.CTkFrame(sc, fg_color="transparent")
        r1.pack(fill="x", padx=16, pady=(0, 10))
        ctk.CTkLabel(r1, text=self.t("lbl_host"), font=("Segoe UI",13), text_color=C["text"]).grid(row=0,column=0,sticky="w",padx=(0,8))
        self._e_host = entry(r1, placeholder_text=self.t("ph_host"), width=280)
        self._e_host.grid(row=0, column=1, sticky="ew", padx=(0,16))
        ctk.CTkLabel(r1, text=self.t("lbl_port"), font=("Segoe UI",13), text_color=C["text"]).grid(row=0,column=2,sticky="w",padx=(0,8))
        self._e_port = entry(r1, placeholder_text="22", width=80)
        self._e_port.grid(row=0, column=3)
        r1.columnconfigure(1, weight=1)

        r2 = ctk.CTkFrame(sc, fg_color="transparent")
        r2.pack(fill="x", padx=16, pady=(0, 10))
        ctk.CTkLabel(r2, text=self.t("lbl_user"), font=("Segoe UI",13), text_color=C["text"]).grid(row=0,column=0,sticky="w",padx=(0,8))
        self._e_user = entry(r2, placeholder_text="root", width=180)
        self._e_user.grid(row=0, column=1, sticky="ew", padx=(0,16))
        ctk.CTkLabel(r2, text=self.t("lbl_pass"), font=("Segoe UI",13), text_color=C["text"]).grid(row=0,column=2,sticky="w",padx=(0,8))
        self._e_pass = entry(r2, placeholder_text=self.t("ph_pass"), show="•", width=180)
        self._e_pass.grid(row=0, column=3)
        r2.columnconfigure(1, weight=1)

        r3 = ctk.CTkFrame(sc, fg_color="transparent")
        r3.pack(fill="x", padx=16, pady=(0, 14))
        self._use_key_var = ctk.BooleanVar()
        ctk.CTkCheckBox(r3, text=self.t("lbl_use_key"),
                        variable=self._use_key_var,
                        font=("Segoe UI",13), text_color=C["text"],
                        fg_color=C["accent"], hover_color=C["accent2"],
                        command=self._toggle_auth).pack(side="left")
        self._e_key = entry(r3, placeholder_text=self.t("lbl_key_path"), width=240)
        self._e_key.pack(side="left", padx=(12,8))
        self._btn_browse = ghost_btn(r3, text=self.t("btn_browse"), width=70, height=30,
                                      command=self._browse_key)
        self._btn_browse.pack(side="left")

        # Mode card
        mc = card(tab)
        mc.pack(fill="x", pady=(0, 10))
        SecLabel(mc, self.t("sec_mode")).pack(anchor="w", padx=16, pady=(14,10))

        mi = ctk.CTkFrame(mc, fg_color="transparent")
        mi.pack(fill="x", padx=16, pady=(0,14))
        self._mode_var = ctk.StringVar(value="proxy")

        for val, name_key, desc_key in [
            ("proxy","mode_proxy","mode_proxy_desc"),
            ("system","mode_system","mode_system_desc"),
        ]:
            f = icard(mi)
            f.pack(side="left", fill="x", expand=True, padx=(0,8) if val=="proxy" else (8,0))
            ctk.CTkRadioButton(f, text=self.t(name_key),
                               variable=self._mode_var, value=val,
                               font=("Segoe UI",13,"bold"), text_color=C["text"],
                               fg_color=C["accent"], hover_color=C["accent2"],
                               ).pack(anchor="w", padx=14, pady=6)
            ctk.CTkLabel(f, text=self.t(desc_key),
                         font=("Segoe UI",11), text_color=C["muted"],
                         wraplength=200).pack(anchor="w", padx=14, pady=(0,10))

        # Action buttons
        br = ctk.CTkFrame(tab, fg_color="transparent")
        br.pack(fill="x", pady=(4,0))
        self._btn_connect = ctk.CTkButton(
            br, text=self.t("btn_connect"),
            font=("Segoe UI",15,"bold"), height=48,
            fg_color=C["accent"], hover_color=C["accent2"], corner_radius=10,
            command=self._on_connect)
        self._btn_connect.pack(side="left", fill="x", expand=True, padx=(0,8))

        self._btn_disconnect = ctk.CTkButton(
            br, text=self.t("btn_disconnect"),
            font=("Segoe UI",15,"bold"), height=48,
            fg_color=C["card"], hover_color=C["danger"],
            border_color=C["border"], border_width=1,
            corner_radius=10, state="disabled",
            command=self._on_disconnect)
        self._btn_disconnect.pack(side="left", fill="x", expand=True)

        self._status_lbl = ctk.CTkLabel(tab, text=self.t("status_ready"),
                                        font=("Segoe UI",12), text_color=C["muted"])
        self._status_lbl.pack(anchor="w", pady=(10,0))

    # ── BYPASS TAB ─────────────────────────────────────────────
    def _build_bypass_tab(self):
        tab = self._tabs.tab(self.t("tab_bypass"))
        tab.configure(fg_color="transparent")

        inf = card(tab)
        inf.pack(fill="x", pady=(8,10))
        ctk.CTkLabel(inf, text="⚠  " + self.t("bypass_info"),
                     font=("Segoe UI",12), text_color=C["warning"]).pack(anchor="w", padx=14, pady=10)

        tb = ctk.CTkFrame(tab, fg_color="transparent")
        tb.pack(fill="x", pady=(0,8))
        self._e_new_domain = entry(tb, placeholder_text=self.t("ph_new_domain"), width=260)
        self._e_new_domain.pack(side="left", padx=(0,8))
        accent_btn(tb, text=self.t("btn_add"), width=90, height=34,
                   font=("Segoe UI",12,"bold"), command=self._add_domain).pack(side="left", padx=(0,8))
        ghost_btn(tb, text=self.t("btn_remove"), width=110, height=34,
                  hover_color=C["danger"], font=("Segoe UI",12),
                  command=self._remove_domain).pack(side="left", padx=(0,8))
        ghost_btn(tb, text=self.t("btn_reset"), width=120, height=34,
                  font=("Segoe UI",12), command=self._reset_bypass).pack(side="right")

        lf = ctk.CTkScrollableFrame(tab, fg_color=C["card"], corner_radius=12)
        lf.pack(fill="both", expand=True)
        self._bypass_lb = tk.Listbox(lf, bg=C["card"], fg=C["text"],
                                     selectbackground=C["accent"],
                                     selectforeground="white",
                                     font=("Consolas",12),
                                     borderwidth=0, highlightthickness=0,
                                     activestyle="none", selectmode=tk.EXTENDED)
        self._bypass_lb.pack(fill="both", expand=True, padx=10, pady=10)
        self._refresh_bypass()

    # ── PROFILES TAB ───────────────────────────────────────────
    def _build_profiles_tab(self):
        tab = self._tabs.tab(self.t("tab_profiles"))
        tab.configure(fg_color="transparent")

        top = ctk.CTkFrame(tab, fg_color="transparent")
        top.pack(fill="x", pady=(8,10))

        ctk.CTkLabel(top, text=self.t("sec_profiles"),
                     font=("Segoe UI",14,"bold"), text_color=C["text"]).pack(side="left")

        accent_btn(top, text=self.t("btn_new_profile"), width=80, height=32,
                   font=("Segoe UI",12), command=self._new_profile).pack(side="right", padx=(8,0))
        ghost_btn(top, text=self.t("btn_del_profile"), width=80, height=32,
                  hover_color=C["danger"], font=("Segoe UI",12),
                  command=self._del_profile).pack(side="right", padx=(8,0))
        ghost_btn(top, text=self.t("btn_rename_profile"), width=100, height=32,
                  font=("Segoe UI",12), command=self._rename_profile).pack(side="right")

        # Active indicator
        ai = ctk.CTkFrame(tab, fg_color="transparent")
        ai.pack(fill="x", pady=(0,8))
        ctk.CTkLabel(ai, text=self.t("lbl_active"),
                     font=("Segoe UI",12), text_color=C["muted"]).pack(side="left")
        self._active_lbl = ctk.CTkLabel(ai, text=self.cfg["active_profile"],
                                        font=("Segoe UI",12,"bold"), text_color=C["accent"])
        self._active_lbl.pack(side="left", padx=(6,0))

        # Profile list
        pf = ctk.CTkScrollableFrame(tab, fg_color=C["card"], corner_radius=12, height=200)
        pf.pack(fill="x", pady=(0,12))
        self._profile_lb = tk.Listbox(pf, bg=C["card"], fg=C["text"],
                                       selectbackground=C["accent"],
                                       selectforeground="white",
                                       font=("Segoe UI",13),
                                       borderwidth=0, highlightthickness=0,
                                       activestyle="none")
        self._profile_lb.pack(fill="both", expand=True, padx=10, pady=10)
        self._profile_lb.bind("<<ListboxSelect>>", self._on_profile_select)
        self._refresh_profiles()

        # Load button
        accent_btn(tab, text="✓  " + ("Load profile" if self.L=="en" else "بارگذاری پروفایل"),
                   height=38, font=("Segoe UI",13,"bold"),
                   command=self._load_profile).pack(anchor="w")

    # ── SETTINGS TAB ───────────────────────────────────────────
    def _build_settings_tab(self):
        tab = self._tabs.tab(self.t("tab_settings"))
        tab.configure(fg_color="transparent")

        pc = card(tab)
        pc.pack(fill="x", pady=(8,10))
        SecLabel(pc, self.t("sec_proxy_port")).pack(anchor="w", padx=16, pady=(14,8))

        pr = ctk.CTkFrame(pc, fg_color="transparent")
        pr.pack(fill="x", padx=16, pady=(0,6))
        ctk.CTkLabel(pr, text="SOCKS5 port", font=("Segoe UI",13),
                     text_color=C["text"]).pack(side="left")
        self._e_proxy_port = entry(pr, placeholder_text="9000", width=100)
        self._e_proxy_port.pack(side="left", padx=(12,0))
        ctk.CTkLabel(pr, text="  (for Telegram, curl, etc.)",
                     font=("Segoe UI",11), text_color=C["muted"]).pack(side="left")

        pr2 = ctk.CTkFrame(pc, fg_color="transparent")
        pr2.pack(fill="x", padx=16, pady=(0,14))
        ctk.CTkLabel(pr2, text="HTTP proxy port", font=("Segoe UI",13),
                     text_color=C["text"]).pack(side="left")
        self._e_http_port = entry(pr2, placeholder_text="9001", width=100)
        self._e_http_port.pack(side="left", padx=(12,0))
        ctk.CTkLabel(pr2, text="  (for browsers in System VPN mode)",
                     font=("Segoe UI",11), text_color=C["muted"]).pack(side="left")

        oc = card(tab)
        oc.pack(fill="x", pady=(0,10))
        SecLabel(oc, self.t("sec_options")).pack(anchor="w", padx=16, pady=(14,8))

        self._auto_connect_var    = ctk.BooleanVar()
        self._auto_reconnect_var  = ctk.BooleanVar()
        self._minimize_tray_var   = ctk.BooleanVar()

        for var, key in [
            (self._auto_connect_var,   "lbl_auto_connect"),
            (self._auto_reconnect_var, "lbl_auto_reconnect"),
            (self._minimize_tray_var,  "lbl_minimize_tray"),
        ]:
            ctk.CTkCheckBox(oc, text=self.t(key), variable=var,
                            font=("Segoe UI",13), text_color=C["text"],
                            fg_color=C["accent"], hover_color=C["accent2"],
                            ).pack(anchor="w", padx=16, pady=(0,8))

        dr = ctk.CTkFrame(oc, fg_color="transparent")
        dr.pack(fill="x", padx=16, pady=(0,14))
        ctk.CTkLabel(dr, text=self.t("lbl_reconnect_delay"),
                     font=("Segoe UI",13), text_color=C["text"]).pack(side="left")
        self._e_reconnect_delay = entry(dr, placeholder_text="5", width=70)
        self._e_reconnect_delay.pack(side="left", padx=(12,0))

        # Language
        lc = card(tab)
        lc.pack(fill="x", pady=(0,10))
        SecLabel(lc, self.t("lbl_language")).pack(anchor="w", padx=16, pady=(14,8))
        lr = ctk.CTkFrame(lc, fg_color="transparent")
        lr.pack(fill="x", padx=16, pady=(0,14))
        self._lang_var = ctk.StringVar(value=self.cfg.get("language","en"))
        ctk.CTkRadioButton(lr, text="English", variable=self._lang_var, value="en",
                           font=("Segoe UI",13), text_color=C["text"],
                           fg_color=C["accent"], hover_color=C["accent2"],
                           ).pack(side="left", padx=(0,24))
        ctk.CTkRadioButton(lr, text="فارسی", variable=self._lang_var, value="fa",
                           font=("Segoe UI",13), text_color=C["text"],
                           fg_color=C["accent"], hover_color=C["accent2"],
                           ).pack(side="left")

        accent_btn(tab, text=self.t("btn_save"), font=("Segoe UI",13,"bold"),
                   height=40, command=self._save_all).pack(anchor="w", pady=(4,0))

    # ── LOG TAB ────────────────────────────────────────────────
    def _build_log_tab(self):
        tab = self._tabs.tab(self.t("tab_log"))
        tab.configure(fg_color="transparent")

        tb = ctk.CTkFrame(tab, fg_color="transparent")
        tb.pack(fill="x", pady=(8,8))
        ctk.CTkLabel(tb, text=self.t("lbl_log_title"),
                     font=("Segoe UI",12), text_color=C["muted"]).pack(side="left")
        ghost_btn(tb, text="📋 Log file", width=90, height=32,
                  font=("Segoe UI",11),
                  command=self._open_log_file).pack(side="right", padx=(8,0))
        ghost_btn(tb, text=self.t("btn_clear_log"), width=100, height=32,
                  hover_color=C["danger"], font=("Segoe UI",12),
                  command=self._clear_log).pack(side="right")

        self._log_box = ctk.CTkTextbox(tab, fg_color=C["card"],
                                       font=("Consolas",11), text_color=C["text"],
                                       corner_radius=12, wrap="none")
        self._log_box.pack(fill="both", expand=True)
        self._log_box.configure(state="disabled")

        # Load existing log entries on startup
        self._load_log_file()

    # ══════════════════════════════════════════════════════════
    #  SYSTEM TRAY
    # ══════════════════════════════════════════════════════════
    def _make_tray_icon_image(self):
        img = Image.new("RGBA", (64, 64), (0,0,0,0))
        d = ImageDraw.Draw(img)
        color = (79,110,247,255) if not self.tunnel.connected else (34,197,94,255)
        d.ellipse([8,8,56,56], fill=color)
        return img

    def _start_tray(self):
        if not HAS_TRAY:
            return
        def show(_): self.after(0, self.deiconify)
        def connect(_): self.after(0, self._on_connect)
        def disconnect(_): self.after(0, self._on_disconnect)
        def quit_app(_): self.after(0, self._on_close)

        menu = TrayMenu(
            TrayItem(self.t("tray_show"),    show, default=True),
            TrayItem(self.t("tray_connect"),    connect),
            TrayItem(self.t("tray_disconnect"), disconnect),
            TrayMenu.SEPARATOR,
            TrayItem(self.t("tray_quit"),    quit_app),
        )
        self._tray_icon = TrayIcon(APP_NAME, self._make_tray_icon_image(), APP_NAME, menu)
        threading.Thread(target=self._tray_icon.run, daemon=True).start()

    def _update_tray_icon(self):
        if self._tray_icon and HAS_TRAY:
            try:
                self._tray_icon.icon = self._make_tray_icon_image()
            except Exception:
                pass

    # ══════════════════════════════════════════════════════════
    #  DATA HELPERS
    # ══════════════════════════════════════════════════════════
    def _active_profile(self) -> dict:
        return self.cfg["profiles"].get(self.cfg["active_profile"], _default_profile())

    def _load_fields(self):
        p = self._active_profile()
        for e, key in [(self._e_host,"host"),(self._e_port,"port"),
                       (self._e_user,"username"),(self._e_pass,"password"),
                       (self._e_key,"key_path")]:
            e.delete(0,"end"); e.insert(0, str(p.get(key,"")))
        self._e_proxy_port.delete(0,"end")
        self._e_proxy_port.insert(0, str(p.get("proxy_port", PROXY_PORT)))
        self._e_http_port.delete(0,"end")
        self._e_http_port.insert(0, str(p.get("http_proxy_port", HTTP_PROXY_PORT)))
        self._mode_var.set(p.get("mode","proxy"))
        self._use_key_var.set(p.get("use_key", False))
        self._toggle_auth()

        self._auto_connect_var.set(self.cfg.get("auto_connect", False))
        self._auto_reconnect_var.set(self.cfg.get("auto_reconnect", True))
        self._minimize_tray_var.set(self.cfg.get("minimize_tray", True))
        self._e_reconnect_delay.delete(0,"end")
        self._e_reconnect_delay.insert(0, str(self.cfg.get("reconnect_delay", 5)))
        self._lang_var.set(self.cfg.get("language","en"))
        self._refresh_bypass()

    def _collect_profile(self) -> dict:
        p = self._active_profile()
        p["host"]       = self._e_host.get().strip()
        p["port"]       = self._e_port.get().strip() or "22"
        p["username"]   = self._e_user.get().strip()
        p["password"]   = self._e_pass.get()
        p["use_key"]    = self._use_key_var.get()
        p["key_path"]   = self._e_key.get().strip()
        p["mode"]       = self._mode_var.get()
        p["proxy_port"]      = int(self._e_proxy_port.get().strip() or PROXY_PORT)
        p["http_proxy_port"] = int(self._e_http_port.get().strip() or HTTP_PROXY_PORT)
        self.cfg["profiles"][self.cfg["active_profile"]] = p
        return p

    def _collect_global(self):
        self.cfg["auto_connect"]    = self._auto_connect_var.get()
        self.cfg["auto_reconnect"]  = self._auto_reconnect_var.get()
        self.cfg["minimize_tray"]   = self._minimize_tray_var.get()
        self.cfg["language"]        = self._lang_var.get()
        try:
            self.cfg["reconnect_delay"] = int(self._e_reconnect_delay.get().strip())
        except ValueError:
            self.cfg["reconnect_delay"] = 5

    def _save_all(self):
        self._collect_profile()
        self._collect_global()
        save_config(self.cfg)
        self._log(self.t("saved_ok"))

    # ── Bypass ────────────────────────────────────────────────
    def _refresh_bypass(self):
        self._bypass_lb.delete(0,"end")
        for d in self._active_profile().get("bypass_domains",[]):
            self._bypass_lb.insert("end", d)

    def _add_domain(self):
        d = self._e_new_domain.get().strip()
        if not d: return
        p = self._active_profile()
        if d not in p["bypass_domains"]:
            p["bypass_domains"].append(d)
            self._refresh_bypass()
            self._e_new_domain.delete(0,"end")
            save_config(self.cfg)

    def _remove_domain(self):
        p = self._active_profile()
        for i in reversed(self._bypass_lb.curselection()):
            d = self._bypass_lb.get(i)
            if d in p["bypass_domains"]:
                p["bypass_domains"].remove(d)
        self._refresh_bypass(); save_config(self.cfg)

    def _reset_bypass(self):
        self._active_profile()["bypass_domains"] = DEFAULT_BYPASS.copy()
        self._refresh_bypass(); save_config(self.cfg)

    # ── Profiles ──────────────────────────────────────────────
    def _refresh_profiles(self):
        self._profile_lb.delete(0,"end")
        for name in self.cfg["profiles"]:
            self._profile_lb.insert("end", "  " + name)
        # highlight active
        names = list(self.cfg["profiles"].keys())
        if self.cfg["active_profile"] in names:
            idx = names.index(self.cfg["active_profile"])
            self._profile_lb.selection_set(idx)
        self._active_lbl.configure(text=self.cfg["active_profile"])

    def _on_profile_select(self, _=None):
        sel = self._profile_lb.curselection()
        if sel:
            name = self._profile_lb.get(sel[0]).strip()
            self.cfg["active_profile"] = name
            self._active_lbl.configure(text=name)

    def _load_profile(self):
        self._on_profile_select()
        self._load_fields()
        self._log(f"Profile loaded: {self.cfg['active_profile']}")

    def _new_profile(self):
        name = simpledialog.askstring(APP_NAME, self.t("profile_name_prompt"), parent=self)
        if not name: return
        if name in self.cfg["profiles"]:
            messagebox.showwarning(APP_NAME, self.t("profile_exists")); return
        self.cfg["profiles"][name] = _default_profile()
        self.cfg["active_profile"] = name
        self._refresh_profiles()
        self._load_fields()
        save_config(self.cfg)

    def _del_profile(self):
        if len(self.cfg["profiles"]) <= 1: return
        name = self.cfg["active_profile"]
        del self.cfg["profiles"][name]
        self.cfg["active_profile"] = next(iter(self.cfg["profiles"]))
        self._refresh_profiles(); self._load_fields(); save_config(self.cfg)

    def _rename_profile(self):
        old = self.cfg["active_profile"]
        new = simpledialog.askstring(APP_NAME, self.t("profile_rename_prompt"), parent=self)
        if not new or new == old: return
        if new in self.cfg["profiles"]:
            messagebox.showwarning(APP_NAME, self.t("profile_exists")); return
        self.cfg["profiles"][new] = self.cfg["profiles"].pop(old)
        self.cfg["active_profile"] = new
        self._refresh_profiles(); save_config(self.cfg)

    # ══════════════════════════════════════════════════════════
    #  CONNECTION
    # ══════════════════════════════════════════════════════════
    def _toggle_auth(self):
        if self._use_key_var.get():
            self._e_pass.configure(state="disabled")
            self._e_key.configure(state="normal")
            self._btn_browse.configure(state="normal")
        else:
            self._e_pass.configure(state="normal")
            self._e_key.configure(state="disabled")
            self._btn_browse.configure(state="disabled")

    def _browse_key(self):
        p = filedialog.askopenfilename(
            title="SSH Key", filetypes=[("Key","*.pem *.key *.ppk *"),("All","*.*")])
        if p: self._e_key.delete(0,"end"); self._e_key.insert(0, p)

    def _on_connect(self):
        # collect from UI → write into cfg → take a snapshot for the worker thread
        p = self._collect_profile()
        if not p["host"]:
            messagebox.showwarning(APP_NAME, self.t("err_no_host")); return
        if not p["username"]:
            messagebox.showwarning(APP_NAME, self.t("err_no_user")); return
        save_config(self.cfg)
        # deep-copy the profile so the worker thread always sees current values
        import copy
        self._pending_profile = copy.deepcopy(p)
        self._log(f"Connecting to {p['host']}:{p['port']} ({p.get('mode','proxy')} mode)…")
        self._set_state("connecting")
        threading.Thread(target=self._connect_worker, daemon=True).start()

    def _connect_worker(self):
        ok, msg = self.tunnel.connect(self._pending_profile, self.L)
        self.after(0, self._on_connect_result, ok, msg)

    def _on_connect_result(self, ok, msg):
        if ok:
            self._set_state("connected", msg)
            self._update_tray_icon()
        else:
            self._set_state("disconnected")
            self._status_lbl.configure(text=msg, text_color=C["danger"])
            self._log(f"✗ {msg}")
            messagebox.showerror(self.t("win_title_err"), msg)

    def _on_disconnect(self, silent=False):
        mode = getattr(self, '_pending_profile', self._active_profile()).get("mode","proxy")
        self.tunnel.disconnect(mode)
        self._set_state("disconnected")
        self._update_tray_icon()
        if not silent: self._log(self.t("msg_disconnected"))

    # ── UI state machine ─────────────────────────────────────
    def _set_state(self, state: str, msg: str = ""):
        is_connected = state == "connected"
        is_busy      = state == "connecting"

        self._btn_connect.configure(
            state="disabled" if (is_connected or is_busy) else "normal",
            text=self.t("btn_connected") if is_connected
                 else self.t("btn_connecting") if is_busy
                 else self.t("btn_connect"))
        self._btn_disconnect.configure(
            state="normal" if is_connected else "disabled")

        badge_map = {"connected":"connected","connecting":"connecting",
                     "reconnecting":"reconnecting","disconnected":"disconnected"}
        text_map  = {"connected":   msg or self.t("status_connected"),
                     "connecting":  self.t("status_connecting"),
                     "reconnecting":self.t("status_reconnecting"),
                     "disconnected":self.t("status_disconnected")}
        lbl_color = {
            "connected":   C["success"],
            "connecting":  C["warning"],
            "reconnecting":C["warning"],
            "disconnected":C["muted"],
        }
        self._status_badge.update(badge_map.get(state,"disconnected"),
                                  text_map.get(state,""))
        self._status_lbl.configure(text=text_map.get(state,""),
                                   text_color=lbl_color.get(state, C["muted"]))
        if is_connected:
            self._log(f"✓ {msg}")

    # ══════════════════════════════════════════════════════════
    #  WATCHDOG + AUTO-RECONNECT
    # ══════════════════════════════════════════════════════════
    def _start_watchdog(self):
        threading.Thread(target=self._watchdog_loop, daemon=True).start()

    def _watchdog_loop(self):
        while self._running:
            time.sleep(4)
            if self.tunnel.connected and not self.tunnel.is_alive():
                self.after(0, self._on_conn_lost)

    def _on_conn_lost(self):
        if not self.tunnel.connected: return
        self.tunnel.connected = False
        self._set_state("disconnected")
        self._update_tray_icon()
        self._log(f"⚠  {self.t('msg_conn_lost')}")

        if self.cfg.get("auto_reconnect"):
            delay = int(self.cfg.get("reconnect_delay", 5))
            self._log(f"↺  Auto-reconnect in {delay}s…")
            threading.Thread(target=self._auto_reconnect_worker,
                             args=(delay,), daemon=True).start()
        else:
            messagebox.showwarning(self.t("win_title_lost"), self.t("msg_conn_lost"))

    def _auto_reconnect_worker(self, delay: int):
        time.sleep(delay)
        if self.tunnel.connected or not self._running: return
        self.after(0, self._set_state, "reconnecting")
        ok, msg = self.tunnel.connect(self._active_profile(), self.L)
        self.after(0, self._on_connect_result, ok, msg)

    # ══════════════════════════════════════════════════════════
    #  LOG
    # ══════════════════════════════════════════════════════════
    def _log(self, msg: str):
        ts = datetime.now().strftime("%H:%M:%S")
        line = f"[{ts}]  {msg}\n"
        self._log_box.configure(state="normal")
        self._log_box.insert("end", line)
        self._log_box.see("end")
        self._log_box.configure(state="disabled")
        logger.info(msg)

    def _clear_log(self):
        self._log_box.configure(state="normal")
        self._log_box.delete("1.0","end")
        self._log_box.configure(state="disabled")

    def _load_log_file(self):
        """Load last 80 lines from log file into the log box on startup."""
        try:
            if os.path.exists(LOG_FILE):
                lines = Path(LOG_FILE).read_text("utf-8", errors="replace").splitlines()
                recent = "\n".join(lines[-80:]) + "\n"
                self._log_box.configure(state="normal")
                self._log_box.insert("end", recent)
                self._log_box.see("end")
                self._log_box.configure(state="disabled")
        except Exception:
            pass

    def _open_log_file(self):
        """Open log file folder in Explorer."""
        try:
            import subprocess
            subprocess.Popen(["explorer", "/select,", LOG_FILE.replace("/", "\\")])
        except Exception:
            # Fallback: copy path to clipboard
            self.clipboard_clear()
            self.clipboard_append(LOG_FILE)
            messagebox.showinfo(APP_NAME, f"Log file path copied:\n{LOG_FILE}")

    # ══════════════════════════════════════════════════════════
    #  LIFECYCLE
    # ══════════════════════════════════════════════════════════
    def _on_close(self):
        if self.cfg.get("minimize_tray") and HAS_TRAY:
            if not self._tray_icon:
                self._start_tray()
            self.withdraw()
            return
        self._shutdown()

    def _shutdown(self):
        self._running = False
        if self.tunnel.connected:
            self.tunnel.disconnect(self._active_profile().get("mode","proxy"))
        if self._tray_icon:
            try: self._tray_icon.stop()
            except Exception: pass
        self.destroy()

    def _force_quit(self):
        self._shutdown()

# ── Entry point ─────────────────────────────────────────────────
if __name__ == "__main__":
    app = App()
    app.mainloop()
