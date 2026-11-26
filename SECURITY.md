# Security policy

## Threat model

The default Java backend is designed for a trusted user on one machine. Its Spring Boot
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
authenticated reverse proxy or mTLS. The optional
[cloud deployment kit](docs/cloud-deployment.md) uses two Access applications
and per-ingress JWT validation by cloudflared. Real cloud acceptance must be
completed before using it with production data.

Cloud mode (`BOARD_CLOUD_ENABLED=true`) separates read-only web and MCP
hostnames, rejects browser Origins on MCP, and refuses non-loopback binding.
The application filter is routing protection, not a JWT validator: cloudflared
must enforce `access.required` and the correct audience on **both** ingress
rules. Merely supplying an assertion header does not authenticate a caller.
Local processes on the VM remain trusted and can impersonate Host headers.
Do not run untrusted workloads on the VM or expose its origin port.

Access machine credentials grant all MCP tools across all projects; there is
no backend leader/worker RBAC. Access audit identity is not the task assignee.
Use separate credentials per device, revoke lost credentials, and never use an
Access Bypass policy to solve a client authentication incompatibility.

## Implemented protections

### Optional personal Workers backend

The separate `workers/` implementation authenticates at the HTTP boundary.
Web requests require an Access JWT signed by the configured team, with the
configured audience, validity period, and owner email. Static assets pass through
the same authentication check. MCP uses per-device Bearer credentials, matched
against SHA-256 hashes in a Worker Secret; missing configuration fails closed.
Machine credentials grant all 16 tools and all projects, not worker-only access.
Browser credentials cannot invoke MCP, and machine credentials cannot read web
routes. The two Workers share D1 transactions and a durable event outbox.

Do not record credentials, request bodies, claim tokens, or exported board data
in logs or version control. Do not enable preview deployments, public asset
bypasses, remote development bindings, or paid resources to troubleshoot auth.
The [Workers operations guide](docs/workers-deployment.md) distinguishes local
tests from real Access/client acceptance and explains rotation and recovery.

### Local and VM backend

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
