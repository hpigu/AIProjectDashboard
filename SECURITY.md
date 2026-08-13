# Security policy

## Threat model

AI Project Board is designed for a trusted user on one machine. The Spring Boot
server binds to `127.0.0.1:8080` by default and exposes MCP, read-only REST/SSE,
and an offline web UI.

`/mcp` has no server-side authentication or caller identity. Any process that can
reach it can invoke every MCP tool, including archive and role-management tools.
Claude Code and Codex worker allowlists are client-side guardrails, not server
authorization.

The following are known consequences of this model:

- another process on the same machine can call all MCP tools;
- binding the service to a public interface exposes all MCP tools to anyone who
  can connect;
- `/api/diagnostics` is unauthenticated and reports version, migration, tool,
  project/task, backup, and disk-capacity information.

Do not expose the server directly to a LAN or the public internet. Deployments
outside the local trust boundary require an authentication layer such as an
authenticated reverse proxy or mTLS. This repository does not provide or validate
a cloud deployment path.

## Implemented protections

- `LocalOriginGuardFilter` rejects non-loopback Host and Origin values unless they
  are explicitly allowed with `BOARD_ALLOWED_HOSTS`.
- `SecurityHeadersFilter` sets a CSP without `unsafe-inline`, denies framing,
  enables `nosniff`, and sets `no-referrer`.
- Vue and font assets are vendored; the web UI does not load external resources.
- Claim tokens are returned once and stored only as SHA-256 hashes. Comparison is
  constant-time.
- Hikari and Flyway logging is reduced to avoid printing full JDBC connection
  details.
- `/api/health/ready` reports check status without returning raw exceptions.

## Report a vulnerability

Do not open a public issue. Use GitHub Private vulnerability reporting from the
repository Security tab, or contact the maintainer directly.

Include the affected version, reproduction steps, actual read/write impact, and
whether local machine access is required.

## Supported versions

Only the latest version on `main` receives security fixes. There are no LTS
branches.
