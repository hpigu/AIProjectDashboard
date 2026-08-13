# Operations

[Back to README](../README.md) · [Installation](installation.md)

## Service lifecycle

Use the supplied launcher. It tracks the exact process, waits for the shutdown
backup, and keeps runtime paths consistent.

macOS/Linux:

```bash
bin/board start
bin/board status
bin/board stop
bin/board restart
bin/board logs -n 200
```

Windows:

```powershell
.\bin\board.ps1 start
.\bin\board.ps1 status
.\bin\board.ps1 stop
.\bin\board.ps1 restart
.\bin\board.ps1 logs -Lines 200
```

For a stable macOS/Linux installation, use the absolute installed entry point:

```bash
~/.ai-project-board/bin/board status
```

`bin/board stop` sends SIGTERM and waits for Spring shutdown hooks. A normal stop
creates a consistent H2 shutdown snapshot. `stop --force` or Windows `-Force`
skips that guarantee and should be used only after confirming the exact target.

Never stop board processes with `pkill -f`, `killall`, or a JAR-name match. Use
the recorded PID or the PID listening on the development port you started.

### Windows release bundle

The Windows x64 ZIP always launches its bundled
`runtime\bin\java.exe` and `app\ai-project-board-backend-V.jar`. It does not fall
back to `PATH`, `JAVA_HOME`, or a network download. Runtime data remains under
`%USERPROFILE%\.ai-project-board` or `BOARD_HOME_DIR`, not the extracted program
directory.

Windows uses a console control event for graceful shutdown because
`Stop-Process` does not run JVM shutdown hooks. If the board was started manually
with `java -jar` in a visible console, stop it with Ctrl+C in that console. For
startup errors before logging is initialized, run:

```powershell
.\bin\board.ps1 start -Foreground
```

Use the [Windows Sandbox checklist](windows-sandbox-clean-install-checklist.md)
to validate the human-visible first-run experience. Release CI covers archive
layout, lifecycle, checksums, update rollback, and unusual paths.

## Backups

All backup phases share `BOARD_BACKUP_DIR`, which defaults to
`~/.ai-project-board/backups`.

| Pattern | When | Method |
|---|---|---|
| `board-startup-<UTC>-<TZ>.mv.db` | Before startup and Flyway migration | File copy plus H2 header validation; failure stops startup |
| `board-shutdown-<UTC>-<TZ>.zip` | Graceful SIGTERM or Ctrl+C | H2 `BACKUP TO`; failure is logged |
| `board-scheduled-<UTC>-<TZ>.zip` | Every six hours by default | H2 `BACKUP TO`; failure does not stop later runs |

Files newer than 30 days are retained. Older files are deleted only while at
least seven remain in that phase. Startup, shutdown, and scheduled files have
independent quotas.

| Variable | Default | Purpose |
|---|---|---|
| `BOARD_BACKUP_INTERVAL` | `6h` | Fixed delay; accepts values such as `90m` or `PT6H` |
| `BOARD_BACKUP_SCHEDULE_ENABLED` | `true` | Set to `false` to disable scheduled backups |

The first scheduled run waits one complete interval after startup. SIGKILL, JVM
crashes, and power loss cannot create a shutdown snapshot; recovery then uses the
latest scheduled or startup backup.

## Restore

List available snapshots before selecting one:

```bash
bin/restore-db.sh --list
```

Stop the board before restoring:

```bash
bin/board stop
bin/restore-db.sh latest
bin/board start
```

Windows uses:

```powershell
.\bin\restore-db.ps1 -List
.\bin\restore-db.ps1 latest
```

The restore script:

1. refuses to continue while the board or database file is active;
2. extracts ZIP backups when necessary and validates the H2 header;
3. renames the current database to `board.mv.db.pre-restore-<UTC>`;
4. writes and validates a temporary file before an atomic rename;
5. removes stale trace and lock files.

Verify the restored state:

```bash
curl -s http://127.0.0.1:8080/api/health/ready
curl -s http://127.0.0.1:8080/api/projects
```

For development drills, use an isolated `BOARD_PORT` and `BOARD_HOME_DIR`. Never
operate on the production board's port, database, log, or process.

## Autostart

For macOS, create a user LaunchAgent whose program arguments call the absolute
installed `bin/board start` path. For Linux, a user systemd service can use:

```ini
[Unit]
Description=AI Project Board
After=network.target

[Service]
Type=forking
ExecStart=%h/.ai-project-board/bin/board start
ExecStop=%h/.ai-project-board/bin/board stop
TimeoutStopSec=120
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now ai-project-board
```

Use `loginctl enable-linger "$USER"` only when the board must remain active while
the user is logged out.

## Troubleshooting

| Symptom | Action |
|---|---|
| JDK 21 not found | Run `java -version` in the same shell. The Windows stable ZIP should use its bundled runtime instead. |
| `MVStoreException` on startup | Another process still holds the H2 file. Check `bin/board status` and the exact PID printed by the launcher. |
| Blank page with HTTP 403 | Use `localhost` or `127.0.0.1`. A non-loopback Host or Origin is rejected unless explicitly listed in `BOARD_ALLOWED_HOSTS`. |
| Board remains active after stop | Check the PID file and the process listening on the configured port. Do not match processes by JAR name. |
| Startup fails before the main log is written | Read `<BOARD_LOG_FILE>.console`; on Windows, retry with `start -Foreground`. |
| Empty board | This is normal on a new database. REST and the UI are read-only; create the first project through MCP. |
| `/api/events` returns 503 | Close unused tabs or investigate a client leaking connections. The default limit is 32. |
| A tab reloads its board state | Its SSE queue filled, so the server disconnected it and the UI resynchronized. Frequent occurrences may justify increasing `BOARD_SSE_CLIENT_QUEUE_CAPACITY`. |
| PowerShell blocks `board.ps1` | Run it with `powershell -ExecutionPolicy Bypass -File .\bin\board.ps1 start`, or set an appropriate current-user execution policy. |

After changing Windows lifecycle scripts, run:

```powershell
pwsh -NoProfile -File scripts\windows-check\check.ps1
```
