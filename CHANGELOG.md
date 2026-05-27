# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.0] - 2025-01-01

### Added
- Multi-profile support — save and switch between multiple SSH server configurations
- System tray support — minimize to tray, color-coded icon (blue=disconnected, green=connected)
- Auto-reconnect — configurable delay, reconnects automatically on connection drop
- HTTP CONNECT proxy (port 9001) — enables full browser support with remote DNS resolution
- Bilingual UI — English (default) and Persian (فارسی), switchable from Settings
- Connection watchdog — polls SSH transport health every 4 seconds
- SSH keep-alive — sends keep-alive packets every 30 seconds
- Profile rename and delete
- Log viewer tab with startup log loading and file browser button
- `_verify_forwarding()` pre-flight check — validates `AllowTcpForwarding` before reporting success

### Changed
- Windows registry proxy now sets `http=`, `https=`, and `socks=` entries separately for full browser compatibility
- SOCKS5 relay uses two threads instead of `select()` for Windows compatibility with paramiko channels
- `_recvall()` helper ensures exact byte counts are read (prevents partial-read bugs)
- Profile data is deep-copied before passing to the worker thread
- System proxy cleanup on both normal disconnect and app exit

### Fixed
- `ghost_btn()` `hover_color` conflict when callers override the default
- `PyInstaller` PATH issue — build script now uses `python -m PyInstaller`
- Build script now uses CRLF line endings and pure ASCII characters

## [2.0.0] - 2024-12-01

### Added
- SOCKS5 proxy server with SSH `direct-tcpip` channel backend
- Windows registry proxy setter with bypass list
- Dark-themed CustomTkinter UI with four tabs
- JSON config persistence
- SSH key file authentication

## [1.0.0] - 2024-11-01

### Added
- Initial release
- Basic SSH connection with password authentication
- SOCKS5 proxy mode
