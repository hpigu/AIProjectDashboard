# Windows Sandbox clean-install checklist

Use this checklist to validate the Windows x64 release on a machine without a
preinstalled JDK or developer tooling. Release CI already verifies archive layout,
start/status/stop, checksums, rollback, and paths containing spaces or non-ASCII
characters. This checklist covers the human-visible first-run experience.

## Prepare

1. Download these two assets from the same stable release:
   - `ai-project-board-backend-windows-x64-V.zip`
   - `ai-project-board-backend-V-SHA256SUMS.txt`
2. Start a fresh Windows Sandbox instance.
3. Copy both files into the Sandbox.

## Validate

- [ ] `java -version` is unavailable before installation.
- [ ] The current user can complete every step without administrator privileges.
- [ ] `certutil -hashfile <zip> SHA256` matches the ZIP entry in
      `SHA256SUMS.txt`.
- [ ] The ZIP extracts successfully to a path containing spaces and non-ASCII
      characters, such as `Desktop\Board 測試`.
- [ ] `bin\board.ps1 start` succeeds without `JAVA_HOME` or a system JDK.
- [ ] The reported version matches the downloaded release.
- [ ] Windows does not request elevation. Record any SmartScreen or Defender prompt
      and the exact action required to continue.
- [ ] `http://127.0.0.1:8080` loads in Edge without console errors.
- [ ] `bin\board.ps1 status` reports the board as running.
- [ ] `bin\board.ps1 stop` stops the process, and a second status check reports it
      as stopped.
- [ ] Data, backups, logs, PID, and configuration are under
      `%USERPROFILE%\.ai-project-board`, not the extracted release directory.

## Record

Add one row per manual run:

| Date | Release | Windows version | Result | SmartScreen/Defender notes |
|---|---|---|---|---|
| — | — | — | Not yet run | — |

If a step fails, include the command, visible error, and whether retrying in a new
Sandbox changes the result.
