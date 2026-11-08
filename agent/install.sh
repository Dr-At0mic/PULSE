#!/usr/bin/env bash
# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later
# PULSE agent installer for Ubuntu/Debian.
#   sudo ./install.sh                         # interactive
#   sudo ./install.sh ws://192.168.1.10:8765 <token> srv2 CORE-02 "#B6FF00"
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/pulse-agent

HUB=${1:-}; TOKEN=${2:-}; ID=${3:-}; NAME=${4:-}; ACCENT=${5:-}
if [[ ! -f "$DEST/config.yaml" ]]; then
  [[ -n $HUB ]]    || read -rp "Hub URL (e.g. ws://192.168.1.10:8765): " HUB
  [[ -n $TOKEN ]]  || read -rsp "Agent token: " TOKEN && echo
  [[ -n $ID ]]     || read -rp "Device id [$(hostname | tr 'A-Z' 'a-z')]: " ID
  [[ -n $NAME ]]   || read -rp "Display name [$(hostname | tr 'a-z' 'A-Z')]: " NAME
  [[ -n $ACCENT ]] || read -rp "Accent colour [#B6FF00]: " ACCENT
  ID=${ID:-$(hostname | tr 'A-Z' 'a-z')}; NAME=${NAME:-$(hostname | tr 'a-z' 'A-Z')}; ACCENT=${ACCENT:-#B6FF00}
fi

apt-get install -y python3-venv >/dev/null
id pulse-agent &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin pulse-agent
mkdir -p "$DEST"
install -m 644 "$SRC/agent.py" "$SRC/requirements.txt" "$SRC/requirements-gpu.txt" "$DEST/"
[[ -d "$DEST/venv" ]] || python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install -q --no-cache-dir --upgrade pip
"$DEST/venv/bin/pip" install -q --no-cache-dir -r "$DEST/requirements.txt"

if [[ ! -f "$DEST/config.yaml" ]]; then
  cat > "$DEST/config.yaml" <<CFG
hub_url: $HUB
token: $TOKEN
id: $ID
name: $NAME
accent: "$ACCENT"
gpu: smi
log_level: INFO
CFG
fi
chown -R root:pulse-agent "$DEST"; chmod 640 "$DEST/config.yaml"
install -m 644 "$SRC/pulse-agent.service" /etc/systemd/system/pulse-agent.service
systemctl daemon-reload
systemctl enable --now pulse-agent
sleep 2
systemctl --no-pager status pulse-agent | head -5
journalctl -u pulse-agent -n 5 --no-pager
