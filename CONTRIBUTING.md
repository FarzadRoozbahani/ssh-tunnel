# Contributing

Thank you for considering contributing to SSH Tunnel!

## Getting Started

1. Fork the repository and clone your fork
2. Install dependencies: `pip install -r requirements.txt`
3. Run from source: `python ssh_tunnel.py`

## Development Setup

```bash
git clone https://github.com/YOUR_USERNAME/ssh-tunnel.git
cd ssh-tunnel
pip install -r requirements.txt
python ssh_tunnel.py
```

## Coding Guidelines

- Python 3.10+ syntax is fine (`match`, `X | Y` type hints, etc.)
- All user-visible strings must be added to **both** `en` and `fa` sections of the `STRINGS` dict
- New UI widgets should use the helper functions (`card()`, `entry()`, `accent_btn()`, `ghost_btn()`) and the `C` color palette
- Background work (network I/O, file I/O) must run in daemon threads — never block the tkinter main thread
- Catch and log exceptions; never let a background thread crash silently

## Submitting Changes

1. Create a branch: `git checkout -b feature/your-feature`
2. Make your changes
3. Verify syntax: `python -c "import ast; ast.parse(open('ssh_tunnel.py').read()); print('OK')"`
4. Commit with a clear message following [Conventional Commits](https://www.conventionalcommits.org/)
5. Push and open a Pull Request

## Reporting Bugs

Please use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.md) template and include:
- Windows version
- Python version
- Relevant lines from `%USERPROFILE%\.ssh_vpn.log`
- Steps to reproduce

## Feature Requests

Open a [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md) issue describing the use case.
