#!/usr/bin/env bash
# systemd-only cloud pre-start checks and cold backup. No local defaults.
set -euo pipefail

fail() { printf '[cloud-preflight] %s\n' "$1" >&2; exit 1; }

[[ "${BOARD_CLOUD_ENABLED:-}" == true ]] || fail 'Cloud mode must be enabled.'
[[ "${BOARD_HOST:-}" == 127.0.0.1 ]] || fail 'Only loopback binding is allowed.'
[[ "${BOARD_PORT:-}" == 18081 ]] || fail 'Cloud port must be 18081.'
[[ "${BOARD_HOME_DIR:-}" == /var/lib/ai-project-board ]] || fail 'Unexpected cloud home.'
[[ "${BOARD_DB_URL:-}" == 'jdbc:h2:file:/var/lib/ai-project-board/data/board;DB_CLOSE_ON_EXIT=FALSE' ]] || fail 'Unexpected cloud database.'
[[ "${BOARD_BACKUP_DIR:-}" == /var/lib/ai-project-board/backups ]] || fail 'Unexpected cloud backup directory.'
[[ "${BOARD_LOG_FILE:-}" == /var/log/ai-project-board/board.log ]] || fail 'Unexpected cloud log.'
[[ -n "${BOARD_CLOUD_WEB_HOST:-}" && -n "${BOARD_CLOUD_MCP_HOST:-}" ]] || fail 'Both cloud hostnames are required.'
[[ "$BOARD_CLOUD_WEB_HOST" != "$BOARD_CLOUD_MCP_HOST" ]] || fail 'Cloud hostnames must differ.'
command -v lsof >/dev/null || fail 'Install lsof before starting the service.'

db=/var/lib/ai-project-board/data/board.mv.db
mkdir -p /var/lib/ai-project-board/data
if [[ -f "$db" ]]; then
  # lsof returns 1 when no process has the file open. Any PID means unsafe copy.
  if lsof -t -- "$db" >/dev/null 2>&1; then
    fail 'Database is already open; refusing cold backup or startup.'
  fi
fi
release_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
/bin/bash "$release_dir/bin/backup-db.sh" "$db" "$BOARD_BACKUP_DIR"
