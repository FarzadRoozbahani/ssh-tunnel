# Architecture

## Overview

SSH Tunnel is a single-file Python application with four main layers:

```
┌─────────────────────────────────────────┐
│           CustomTkinter UI              │  ← App class
├─────────────────────────────────────────┤
│           TunnelManager                 │  ← Orchestrates connect/disconnect
├──────────────────┬──────────────────────┤
│  SOCKSHandler    │  HTTPProxyHandler    │  ← Two local proxy servers
│  (port 9000)     │  (port 9001)         │
├──────────────────┴──────────────────────┤
│           Paramiko SSH Transport        │  ← Encrypted SSH connection
└─────────────────────────────────────────┘
```

## Components

### SOCKSHandler

A minimal SOCKS5 proxy server (RFC 1928) that runs on `127.0.0.1:9000`.

- Supports CONNECT command only (atype 1=IPv4, 3=domain, 4=IPv6)
- Uses `_recvall()` for reliable byte reading
- Opens a `direct-tcpip` SSH channel per connection
- Two-thread relay (`pump`) instead of `select()` — required because paramiko
  Channel objects do not implement the `fileno()` method needed by `select()` on Windows

### HTTPProxyHandler

An HTTP/1.x CONNECT proxy on `127.0.0.1:9001`.

- Handles `CONNECT host:port HTTP/1.x` for HTTPS tunneling
- Handles plain `GET http://...` requests by forwarding to upstream
- Internally connects to the local SOCKS5 server (port 9000)
- This two-hop design keeps the code clean — HTTP proxy delegates all SSH logic to the SOCKS5 layer

### TunnelManager

Orchestrates the full lifecycle:

1. `connect()` — SSH connect → verify forwarding → start SOCKS5 → start HTTP proxy → optionally set Windows registry proxy
2. `_verify_forwarding()` — opens a test `direct-tcpip` channel before reporting success
3. `disconnect()` — stops HTTP proxy → stops SOCKS5 → closes SSH → optionally clears Windows registry proxy
4. `is_alive()` — checks `transport.is_active()` for watchdog

### Windows Proxy (System-wide VPN mode)

Sets three entries in `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`:

```
ProxyEnable  = 1
ProxyServer  = http=127.0.0.1:9001;https=127.0.0.1:9001;socks=127.0.0.1:9000
ProxyOverride = <bypass list>;<local>
```

Calls `InternetSetOptionW` with options 39 (SETTINGS_CHANGED) and 37 (REFRESH) to notify running applications.

### Watchdog

A background thread polls `is_alive()` every 4 seconds. On drop:
- If `auto_reconnect` is enabled → waits `reconnect_delay` seconds → reconnects
- Otherwise → shows a warning dialog

### i18n

All user-visible strings live in the `STRINGS` dict keyed by language code (`en`, `fa`). The `T(key, lang)` function resolves with English fallback. Language preference is stored in config and applied on restart (a full UI rebuild would require re-instantiating all widgets).

## Data Flow — SOCKS5

```
Browser/App
    │  SOCKS5 handshake (greeting + CONNECT request)
    ▼
SOCKSHandler._handle()
    │  paramiko transport.open_channel("direct-tcpip", (dest_host, dest_port), ...)
    ▼
SSH Transport (encrypted)
    │
    ▼
Remote SSH Server
    │  TCP connection to dest_host:dest_port
    ▼
Destination Server
```

## Data Flow — HTTP CONNECT (browsers)

```
Browser
    │  CONNECT example.com:443 HTTP/1.1
    ▼
HTTPProxyHandler._handle()
    │  SOCKS5 CONNECT to 127.0.0.1:9000 → example.com:443
    ▼
SOCKSHandler (local)
    │  SSH direct-tcpip channel
    ▼
Remote SSH Server → example.com:443
```

## Configuration Schema

```json
{
  "language": "en",
  "auto_connect": false,
  "auto_reconnect": true,
  "reconnect_delay": 5,
  "minimize_tray": true,
  "active_profile": "Default",
  "profiles": {
    "Default": {
      "host": "",
      "port": "22",
      "username": "",
      "password": "",
      "use_key": false,
      "key_path": "",
      "mode": "proxy",
      "proxy_port": 9000,
      "http_proxy_port": 9001,
      "bypass_domains": []
    }
  }
}
```

## Thread Model

| Thread | Purpose |
|--------|---------|
| Main (tkinter) | UI event loop |
| `socks-accept` | Accept loop for SOCKS5 server |
| `socks-{port}` | Per-connection SOCKS5 handler |
| `http-proxy-accept` | Accept loop for HTTP proxy |
| `http-{port}` | Per-connection HTTP handler |
| `pump` (×2 per conn) | Bidirectional byte relay |
| `watchdog` | Connection health monitor |
| `connect-worker` | SSH connect (off main thread) |
| `tray` | pystray event loop |
