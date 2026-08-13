# Installation and updates

[Back to README](../README.md) · [繁體中文](../README.zh-TW.md)

The board server and agent plugin are separate components:

```text
server: local Spring Boot process + H2 data + browser UI
plugin: MCP endpoint declaration + role shells + claim-tasks skill
```

Install and start the server first. Then connect Claude Code or Codex. Updating
one component does not update the other.

## Supported paths

| Platform | Server path | Java requirement |
|---|---|---|
| Windows x64 | Published ZIP or source checkout | ZIP includes Java; source requires JDK 21 |
| macOS arm64/x64 | Published JAR installer or source checkout | Matching JDK 21 |
| Linux x64 | Published JAR installer or source checkout | x64 JDK 21 |

Windows and Linux arm64 are not supported. The server is designed for local use
and has no validated cloud deployment path.

## Option 1: start from source

This is the shortest common path on every supported platform. Maven is included
through the wrapper; the first start downloads dependencies and builds the JAR.

macOS or Linux:

```bash
git clone https://github.com/hpigu/AIProjectDashboard.git
cd AIProjectDashboard
./bin/board start
```

Windows PowerShell:

```powershell
git clone https://github.com/hpigu/AIProjectDashboard.git
cd AIProjectDashboard
.\bin\board.ps1 start
```

Open <http://127.0.0.1:8080> when the launcher reports readiness.

Useful lifecycle commands:

| Action | macOS/Linux | Windows |
|---|---|---|
| Status | `bin/board status` | `.\bin\board.ps1 status` |
| Stop | `bin/board stop` | `.\bin\board.ps1 stop` |
| Restart | `bin/board restart` | `.\bin\board.ps1 restart` |
| Logs | `bin/board logs` | `.\bin\board.ps1 logs -Lines 200` |

`stop` waits for the shutdown backup. It does not escalate to a forced kill
unless you explicitly pass `--force` or `-Force`.

## Option 2: install a stable release

Use assets from one immutable release tag and verify them against that release's
`ai-project-board-backend-V-SHA256SUMS.txt`.

### Windows x64

Download and extract:

```text
ai-project-board-backend-windows-x64-V.zip
```

Start the bundled launcher:

```powershell
<extract-dir>\ai-project-board-backend-windows-x64-V\bin\board.ps1 start
```

The ZIP contains its own JDK 21 runtime and ignores `JAVA_HOME` and the system
`PATH`. Application data is stored under `%USERPROFILE%\.ai-project-board` by
default, not inside the extracted program directory.

### macOS or Linux

The published JARs require a matching JDK 21. The installer is currently kept in
the source tree, so obtain a source checkout before running it:

```bash
./install/install.sh \
  --release-url "https://github.com/hpigu/AIProjectDashboard/releases/download/v3.2.1" \
  --version 3.2.1

~/.ai-project-board/bin/board start
```

The installer validates the platform, JDK architecture, asset name, checksum
manifest, and SHA-256 before publishing the installation under
`~/.ai-project-board`. It does not use a mutable `latest` URL.

## Connect Codex

Install the Git marketplace and plugin:

```bash
codex plugin marketplace add hpigu/AIProjectDashboard --ref main
codex plugin add ai-project-board@ai-board
```

Enable the `board` connector when prompted, then open a new Codex task. Plugin
updates are explicit. In Codex Desktop, open the plugin page and upgrade the
`ai-board` marketplace. The CLI equivalent is:

```bash
codex plugin marketplace upgrade ai-board
```

The installed plugin uses the refreshed marketplace snapshot, so do not run
`plugin add` again. Open a new task after the upgrade to load the updated agents
and skills.

If `ai-board` already points to a local checkout, remove that marketplace source
before adding the Git source:

```bash
codex plugin marketplace remove ai-board
codex plugin marketplace add hpigu/AIProjectDashboard --ref main
codex plugin add ai-project-board@ai-board
```

Removing a marketplace source does not remove the installed plugin or delete the
source checkout.

## Connect Claude Code

The verified Claude Code path uses a local marketplace checkout:

```bash
claude plugin marketplace add /path/to/AIProjectDashboard
claude plugin install ai-project-board@ai-board
```

Reload plugins or restart Claude Code, then install the declared `board`
connector when prompted.

In Claude Desktop, switch to **Code**, then open **Customize → Plugins → AI
Project Board** and click **Update** when it is available. If the desktop page
has not discovered the new marketplace version yet, or when working entirely
from the CLI, refresh the marketplace and update the plugin explicitly:

```bash
claude plugin marketplace update ai-board
claude plugin update ai-project-board@ai-board
```

Restart Claude Code or open a new session after the update.

Plugin updates only replace the client integration. They do not update or
restart the board server; use the server update procedure below for that.

Old role files in `~/.claude/agents/` can override the plugin copies. Remove
manually installed `backend-dev.md`, `frontend-dev.md`, `qa.md`, `infra.md`, and
`docs.md` if they came from an older installation of this project.

## MCP-only connection

This exposes the tools without the role shells or `claim-tasks` workflow.

Claude Code:

```bash
claude mcp add --transport http board http://127.0.0.1:8080/mcp --scope project
```

Codex, in `~/.codex/config.toml`:

```toml
[mcp_servers.board]
url = "http://127.0.0.1:8080/mcp"
```

## Update the server

Server updates are never implied by plugin or marketplace updates. Select an
exact stable version.

macOS/Linux source or installed server:

```bash
bin/board update --version 3.2.1 \
  --release-url "https://github.com/hpigu/AIProjectDashboard/releases/download/v3.2.1" \
  --check

bin/board update --version 3.2.1 \
  --release-url "https://github.com/hpigu/AIProjectDashboard/releases/download/v3.2.1"
```

Use `~/.ai-project-board/bin/board` instead when installed under the default
stable root. `--check` validates the target without stopping or changing the
server.

Windows updates use a downloaded ZIP and checksum list:

```powershell
<install-root>\bin\board.ps1 update -Version 3.2.1 `
  -ReleaseZip C:\path\ai-project-board-backend-windows-x64-3.2.1.zip `
  -Checksums C:\path\ai-project-board-backend-3.2.1-SHA256SUMS.txt -Check
```

Remove `-Check` to apply the update. The updater verifies the artifact before
stopping the service, takes a database snapshot, validates the new process, and
restores the previous activation and database if the update fails. See
[`release-contract.md`](release-contract.md) for artifact rules and
[`operations.md`](operations.md) for recovery procedures.

## Data locations

| Installation | Default data root |
|---|---|
| Existing source checkout with `data/board.mv.db` | `<repo>/data` for backward compatibility |
| New source checkout | `~/.ai-project-board` |
| macOS/Linux stable install | `~/.ai-project-board` or `--home DIR` |
| Windows stable ZIP | `%USERPROFILE%\.ai-project-board` |

Override the root with `BOARD_HOME_DIR`. Do not place data in a plugin cache or
release directory that may be replaced during an update.

To migrate an existing source-checkout database into a new macOS/Linux stable
root:

```bash
./install/install.sh \
  --jar /path/to/platform-V.jar \
  --checksums /path/to/ai-project-board-backend-V-SHA256SUMS.txt \
  --migrate-from /path/to/old-repo/data
```

Migration is allowed only for a new installation root. It copies and verifies
the source database; it does not move or overwrite the original.

## Configuration

Common variables:

| Variable | Default | Purpose |
|---|---|---|
| `BOARD_HOST` | `127.0.0.1` | Bind address |
| `BOARD_PORT` | `8080` | HTTP port |
| `BOARD_HOME_DIR` | `~/.ai-project-board` | Data, backups, logs, and PID root |
| `BOARD_DB_URL` | derived from data root | H2 JDBC URL |
| `BOARD_ALLOWED_HOSTS` | empty | Additional Host/Origin values; does not add authentication |

Do not expose `/mcp` directly beyond the local machine. It has no server-side
authentication. Read [`SECURITY.md`](../SECURITY.md) before changing the bind or
proxy configuration.

## Verify an installation

```bash
curl -s http://127.0.0.1:8080/api/health
curl -s http://127.0.0.1:8080/api/health/ready
```

The health response identifies the running version and build commit. A source
archive built without `.git` may report `commit: "unknown"`.

## Current limitations

- Plugin updates have been validated in the Codex and Claude Desktop plugin
  pages. Clean first-time desktop GUI installation still needs an independent
  external-user test; the CLI paths above remain the fallback.
- Role customizations made through `upsert_role` live in the local H2 database;
  they do not travel with the thin plugin.
- macOS/Linux stable installation still requires JDK 21 and a source checkout to
  obtain `install/install.sh`.
