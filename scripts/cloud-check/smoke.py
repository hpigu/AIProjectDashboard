#!/usr/bin/env python3
"""Isolated cloud-route/MCP/SSE/persistence smoke test; no Cloudflare resources.

Usage: python3 scripts/cloud-check/smoke.py --jar /absolute/path/to/board.jar
Only the exact Java child process is terminated. All writable application paths
are temporary; MCP responses and claim tokens are never printed or persisted.
"""

import argparse
import concurrent.futures
import http.client
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time


WEB_HOST = "board.example.com"
MCP_HOST = "mcp.example.com"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def free_port():
    while True:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        if port != 8080:
            return port


def connect(port, host, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    request_headers = {"Host": host, **(headers or {})}
    try:
        connection.request(method, path, body, request_headers)
        return connection, connection.getresponse()
    except BaseException:
        connection.close()
        raise


def status(port, host, method, path, body=None, headers=None):
    connection, response = connect(port, host, method, path, body, headers)
    try:
        return response.status
    finally:
        response.close()
        connection.close()


def event(response):
    """Read one SSE frame, allowing comments and multiline data."""
    name, data = "message", []
    while True:
        line = response.readline()
        require(bool(line), "SSE stream ended unexpectedly")
        line = line.decode("utf-8").rstrip("\r\n")
        if not line:
            if data:
                return name, "\n".join(data)
            name = "message"
        elif line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip(" "))


class McpClient:
    def __init__(self, port):
        self.port = port
        self.session = None
        self.request_id = 0
        self.rpc("initialize", {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "cloud-smoke", "version": "1"},
        })
        require(bool(self.session), "MCP initialization did not issue a session")
        self.rpc("notifications/initialized", {}, notification=True)

    def rpc(self, method, params, notification=False):
        self.request_id += 1
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            message["id"] = self.request_id
        headers = {"Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
            headers["MCP-Protocol-Version"] = "2025-03-26"
        connection, response = connect(
            self.port, MCP_HOST, "POST", "/mcp", json.dumps(message), headers)
        try:
            require(200 <= response.status < 300, "MCP HTTP request failed")
            self.session = response.getheader("Mcp-Session-Id") or self.session
            if notification:
                return None
            if "text/event-stream" in response.getheader("Content-Type", ""):
                while True:
                    _, data = event(response)
                    result = json.loads(data)
                    if result.get("id") == self.request_id:
                        break
            else:
                result = json.loads(response.read())
            require("error" not in result, "MCP returned a JSON-RPC error")
            return result["result"]
        finally:
            response.close()
            connection.close()

    def tool(self, tool_name, **arguments):
        result = self.rpc("tools/call", {"name": tool_name, "arguments": arguments})
        require(not result.get("isError", False), "MCP tool failed: " + tool_name)
        return "\n".join(item["text"] for item in result.get("content", [])
                         if item.get("type") == "text")


class Application:
    def __init__(self, jar, root):
        self.jar, self.root, self.port = jar, root, free_port()
        self.process = None

    def start(self):
        # Do not inherit application settings, JVM injection, or debug logging.
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("BOARD_", "SPRING_", "LOGGING_"))
               and key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        env.update({
            "BOARD_PORT": str(self.port), "BOARD_HOST": "127.0.0.1",
            "BOARD_CLOUD_ENABLED": "true", "BOARD_CLOUD_WEB_HOST": WEB_HOST,
            "BOARD_CLOUD_MCP_HOST": MCP_HOST,
            "BOARD_ALLOWED_HOSTS": WEB_HOST + "," + MCP_HOST,
            "BOARD_DB_URL": "jdbc:h2:file:" + str(self.root / "db")
                            + ";DB_CLOSE_ON_EXIT=FALSE",
            "BOARD_DB_USER": "sa", "BOARD_DB_PASSWORD": "",
            "BOARD_LOG_FILE": str(self.root / "application.log"),
            "BOARD_BACKUP_DIR": str(self.root / "backups"),
            "BOARD_BACKUP_SCHEDULE_ENABLED": "false",
        })
        self.process = subprocess.Popen(
            ["java", "-Duser.home=" + str(self.root), "-jar", str(self.jar),
             "--server.port=" + str(self.port), "--server.address=127.0.0.1"],
            cwd=self.root, env=env, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            require(self.process.poll() is None, "Java application exited during startup")
            try:
                if status(self.port, "127.0.0.1", "GET", "/api/health/ready") == 200:
                    return
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(0.2)
        raise AssertionError("Java application readiness timed out")

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()  # exact child PID only
                self.process.wait(timeout=10)
                raise AssertionError("Java application did not stop gracefully")


def exercise(app):
    port = app.port
    require(status(port, WEB_HOST, "GET", "/") == 200, "Web route unavailable")
    for host, method, path in [
            (WEB_HOST, "POST", "/mcp"), (WEB_HOST, "GET", "/mcp"),
            (MCP_HOST, "GET", "/api/projects"), (MCP_HOST, "GET", "/"),
            ("unknown.example.com", "GET", "/api/projects")]:
        require(status(port, host, method, path) == 403, "Cloud host route leaked")
    require(status(port, MCP_HOST, "POST", "/mcp", "{}", {
        "Content-Type": "application/json", "Origin": "https://" + WEB_HOST,
    }) == 403, "Browser Origin reached MCP")
    missing_session = json.dumps({"jsonrpc": "2.0", "id": 1,
                                  "method": "tools/list", "params": {}})
    require(status(port, MCP_HOST, "POST", "/mcp", missing_session, {
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
    }) >= 400, "MCP accepted a request without session")

    first, second = McpClient(port), McpClient(port)
    names = {tool["name"] for tool in first.rpc("tools/list", {})["tools"]}
    require({"create_project", "create_tasks", "list_tasks", "claim_next_task"} <= names,
            "Required MCP tools missing")

    connection, stream = connect(port, WEB_HOST, "GET", "/api/events",
                                 headers={"Accept": "text/event-stream"})
    try:
        require(stream.status == 200, "SSE route unavailable")
        require(event(stream)[0] == "heartbeat", "SSE initial heartbeat missing")
        created = first.tool("create_project", name="cloud-smoke", description="Isolated smoke")
        match = re.search(r"#(\d+)", created)
        require(match is not None, "Project creation did not return an ID")
        project_id = int(match.group(1))
        first.tool("create_tasks", projectId=project_id, tasks=[
            {"title": "prerequisite", "description": "Smoke prerequisite", "category": "BACKEND",
             "dependsOnIndexes": [], "dependsOnTaskIds": []},
            {"title": "dependent", "description": "Must remain TODO", "category": "BACKEND",
             "dependsOnIndexes": [1], "dependsOnTaskIds": []},
        ])
        barrier = threading.Barrier(2)

        def claim(client, assignee):
            barrier.wait(timeout=10)
            return client.tool("claim_next_task", projectName="cloud-smoke",
                               category="BACKEND", assignee=assignee)

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            futures = [workers.submit(claim, first, "smoke-first"),
                       workers.submit(claim, second, "smoke-second")]
            results = [future.result(timeout=30) for future in futures]
        require(sum("claimToken：" in result for result in results) == 1,
                "Concurrent claims did not have exactly one winner")
        require(not any("「dependent」" in result and "claimToken：" in result
                        for result in results), "Unfinished dependency was claimed")
        blocked = first.tool("claim_next_task", projectName="cloud-smoke",
                             category="BACKEND", assignee="smoke-check")
        require("全部都在等前置任務完成" in blocked,
                "Dependency-blocked tasks were not reported correctly")
        observed = set()
        for _ in range(10):
            observed.add(event(stream)[0])
            if {"project.created", "tasks.created", "task.status_changed"} <= observed:
                break
        require({"project.created", "tasks.created", "task.status_changed"} <= observed,
                "SSE mutation events missing")
        before = first.tool("list_tasks", projectName="cloud-smoke", includeDescription=True)
        require("IN_PROGRESS (1)" in before and "TODO (1)" in before,
                "Unexpected claim/dependency state before restart")
    finally:
        stream.close()
        connection.close()

    app.stop()
    app.start()
    restarted = McpClient(port)
    after = restarted.tool("list_tasks", projectName="cloud-smoke", includeDescription=True)
    require(before == after, "Project/task/assignee state changed after restart")
    blocked = restarted.tool("claim_next_task", projectName="cloud-smoke",
                             category="BACKEND", assignee="smoke-restarted")
    require("全部都在等前置任務完成" in blocked,
            "Dependency state was lost after restart")
    print("PASS: cloud host isolation, MCP wire/read/write, concurrent claim, dependency, SSE, restart persistence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()
    require(args.jar.is_absolute() and args.jar.is_file(), "--jar must be an existing absolute path")
    with tempfile.TemporaryDirectory(prefix="ai-board-cloud-smoke-") as temporary:
        app = Application(args.jar, Path(temporary))
        try:
            app.start()
            exercise(app)
        finally:
            app.stop()


if __name__ == "__main__":
    main()
