# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 3.x     | ✅ Yes    |
| < 3.0   | ❌ No     |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, report them privately via GitHub's
[Security Advisories](https://github.com/YOUR_USERNAME/ssh-tunnel/security/advisories/new).

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

You will receive a response within 72 hours.

## Security Notes

- **No telemetry** — the application makes no outbound connections except the SSH tunnel you configure
- **Credentials** are stored in plain JSON at `%USERPROFILE%\.ssh_vpn_config.json` — protect this file
- **SSH host keys** are auto-accepted on first connect (TOFU) — verify the host fingerprint manually if operating in a high-security environment
- The application does **not** run as a service and requires no elevated privileges except for System-wide VPN mode (Windows registry proxy settings)
