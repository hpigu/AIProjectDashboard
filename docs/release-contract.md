# Stable release contract

This document defines the version, artifacts, checksums, and thin-plugin rules for
a stable GitHub release. `v3.2.1` was published under this contract.

The [release workflow](../.github/workflows/release.yml) builds and validates on
four native platform runners. One publish job assembles the artifacts and creates
the checksum manifest only after every platform succeeds.

## Version and provenance

Let `V` be the product version without the `v` prefix, such as `3.2.1`.
`pom.xml` `project.version` is the source of truth.

| Component | Required value |
|---|---|
| Server | `project.version = V` |
| Claude Code plugin | `plugin/.claude-plugin/plugin.json` version is `V` |
| Claude marketplace | `.claude-plugin/marketplace.json` version is `V` |
| Codex plugin | `plugins/ai-project-board/.codex-plugin/plugin.json` version is `V` |
| Codex marketplace | Points to `./plugins/ai-project-board` |
| Git tag and release | Exactly `vV` |
| Artifact filenames | Contain exactly `V` |

All components must come from the same peeled tag commit. Preflight rejects a
tag that does not match `pom.xml`, runs `scripts/check-versions.sh` and
`scripts/check-plugin-parity.sh`, and records the tag, version, and commit used by
every downstream job.

The Codex marketplace file does not duplicate a version field; the selected
plugin manifest supplies it.

## Product assets

A stable `vV` release publishes exactly five product assets. GitHub-generated
source archives are outside this list and checksum scope.

| Platform | Filename |
|---|---|
| Windows x64 | `ai-project-board-backend-windows-x64-V.zip` |
| Linux x64 | `ai-project-board-backend-linux-x64-V.jar` |
| macOS arm64 | `ai-project-board-backend-macos-arm64-V.jar` |
| macOS x64 | `ai-project-board-backend-macos-x64-V.jar` |
| Integrity manifest | `ai-project-board-backend-V-SHA256SUMS.txt` |

The platform suffix is contractual even when multiple JARs contain equivalent
bytecode.

### Windows ZIP

The archive has one top-level directory:

```text
ai-project-board-backend-windows-x64-V/
├── app/
│   └── ai-project-board-backend-V.jar
├── bin/
│   └── board.ps1
└── runtime/
    └── bin/java.exe
```

The runtime is a Windows x64 Java 21 jlink image. `bin\board.ps1` must use that
runtime and must not fall back to system Java, `JAVA_HOME`, or a download.

Linux and macOS assets are executable JARs without a launcher or runtime. They
require a Java 21 installation matching the named CPU architecture.

## Server and plugin compatibility

Both plugins are thin clients. They contain manifests, the MCP endpoint, role
shells, and the leader skill. They must not contain a server JAR, server source,
runtime image, launcher, or extracted server cache.

| Server | Installed client plugin | Support status |
|---|---|---|
| `V` | `V` from the same stable tag | Supported |
| `V` | Any other version | Unsupported; align both to the same stable tag |
| Missing, branch-only, or untraceable | Any | Unsupported |

The server does not currently enforce this matrix at runtime. It is a release and
support contract. Users select updates explicitly; there is no background update
channel or mutable `latest` asset name.

## SHA-256 manifest

`ai-project-board-backend-V-SHA256SUMS.txt` must be:

- UTF-8 without BOM;
- LF-terminated, including the last line;
- exactly four lines, sorted by asset basename with `LC_ALL=C`;
- one entry for each platform asset and no other file;
- formatted as 64 lowercase hexadecimal characters, two ASCII spaces, then the
  basename.

For `V=3.2.1`:

```text
<64 lowercase hex>  ai-project-board-backend-linux-x64-3.2.1.jar
<64 lowercase hex>  ai-project-board-backend-macos-arm64-3.2.1.jar
<64 lowercase hex>  ai-project-board-backend-macos-x64-3.2.1.jar
<64 lowercase hex>  ai-project-board-backend-windows-x64-3.2.1.zip
```

Installers and updaters validate the manifest structure, complete expected file
set, target basename, version, and computed digest before extracting or replacing
anything. A mismatch fails closed and preserves the active installation. A digest
is an integrity check, not a signature or identity guarantee.

## Platform validation

Linux and macOS jobs test, package, validate the platform filename, and run
`scripts/release/posix-release-smoke.sh` with the actual release JAR.

Windows packages the jlink archive and runs:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\package-windows-x64.ps1 `
  -ServerJar target\ai-project-board-backend-V.jar -JdkHome $env:JAVA_HOME `
  -Version V -OutputDirectory target\release
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\package-fixture.ps1 `
  -ServerJar target\ai-project-board-backend-V.jar -Version V
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\release-check.ps1 `
  -ReleaseZip target\release\ai-project-board-backend-windows-x64-V.zip -Smoke
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-check\update-fixture.ps1 `
  -ReleaseZip target\release\ai-project-board-backend-windows-x64-V.zip -Version V
```

These checks cover archive layout, bundled runtime, lifecycle, checksum rejection,
paths containing spaces and non-ASCII characters, and rollback after publish,
activate, start, or readiness failure.

Test cleanup may terminate only the exact PID recorded by the scenario. It must
never match an AI Project Board process by name, JAR filename, or command-line
substring.
