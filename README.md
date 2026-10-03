# ServerWatch Android

A production-oriented Android server monitoring app with a lightweight Linux agent.

## Features

- Multi-server dashboard
- CPU, RAM, swap, disk and inode monitoring
- Network RX/TX rates, errors and drops
- Docker container state monitoring
- Configurable systemd service checks
- Background monitoring foreground service
- Android alert notifications for warning/critical/down/recovery transitions
- Token-authenticated agent API
- Release APK built by GitHub Actions

## Repository layout

- `android/` — Kotlin + Jetpack Compose Android application
- `agent/` — Go Linux monitoring agent
- `.github/workflows/build.yml` — Android and agent CI

## Run the agent

The agent requires Go and can be built with:

```bash
cd agent
go build -trimpath -ldflags="-s -w" -o serverwatch-agent .
sudo install -m 0755 serverwatch-agent /usr/local/bin/serverwatch-agent
sudo install -m 0644 serverwatch-agent.service /etc/systemd/system/serverwatch-agent.service
sudo tee /etc/serverwatch-agent.env >/dev/null <<'EOF'
SERVERWATCH_ADDR=0.0.0.0:8787
SERVERWATCH_TOKEN=replace-with-a-long-random-token
SERVERWATCH_SERVICES=nginx,redis,mariadb
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now serverwatch-agent
```

Then add `http://SERVER-IP:8787` and the same token in the Android app.

For Internet exposure, use HTTPS and/or a private VPN such as WireGuard/Tailscale. Do not expose the agent port directly to the public Internet.

## Build APK

GitHub Actions builds `app-release.apk` from the `android/` project on every push to `main` and on version tags.