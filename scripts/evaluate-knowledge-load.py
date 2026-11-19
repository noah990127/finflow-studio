#!/usr/bin/env python3
"""Exercise authenticated workspace persistence on a dedicated running FinFlow deployment."""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
from threading import Barrier, Lock
from time import perf_counter
from urllib.parse import urlsplit
from uuid import uuid4

import httpx


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def session(base, username, password, timeout):
    client = httpx.Client(base_url=base, timeout=timeout, follow_redirects=False)
    try:
        result = client.post("/api/auth/login", json={"username": username, "password": password})
        check(result.status_code == 200, f"Login failed (HTTP {result.status_code})")
        return client
    except BaseException:
        client.close()
        raise


def summary(durations):
    rows = sorted(durations)
    def percentile(fraction):
        return round(1000 * rows[math.ceil(len(rows) * fraction) - 1], 2)
    return {"count": len(rows), "p50_ms": percentile(.5), "p95_ms": percentile(.95),
            "p99_ms": percentile(.99), "max_ms": round(1000 * rows[-1], 2)}


def benchmark(args):
    parts = urlsplit(args.base_url)
    check(parts.scheme in {"http", "https"} and parts.hostname and not parts.username
          and not parts.password and not parts.query and not parts.fragment, "Supply a service URL")
    check(parts.scheme == "https" or parts.hostname in {"127.0.0.1", "::1", "localhost"},
          "Use HTTPS for remote login")
    check(args.concurrency >= 2 and args.rounds >= 1 and args.timeout > 0,
          "Use at least two workers, one round and a positive timeout")
    password = os.environ.get("FINFLOW_EVAL_PASSWORD")
    check(password, "Set FINFLOW_EVAL_PASSWORD")
    if args.other_user:
        check(args.other_user != args.user and os.environ.get("FINFLOW_EVAL_OTHER_PASSWORD"),
              "Supply distinct secondary user and FINFLOW_EVAL_OTHER_PASSWORD")
    run = uuid4().hex[:12]
    clients = []
    projects = []
    cleanup_errors = []
    lock = Lock()
    timings = defaultdict(list)
    statuses = Counter()

    def call(client, op, method, path, expected, body=None):
        start = perf_counter()
        response = client.request(method, path, json=body) if body else client.request(method, path)
        elapsed = perf_counter() - start
        if op:
            with lock:
                timings[op].append(elapsed)
                statuses[response.status_code] += 1
        check(response.status_code in expected, f"{op or method} unexpected HTTP {response.status_code}")
        return response

    def note(revision, title):
        return {"action": "add_note", "expected_revision": revision, "request_id": str(uuid4()),
                "payload": {"title": title, "text": title}}

    try:
        for i in range(args.concurrency):
            clients.append(session(args.base_url, args.user, password, args.timeout))
            response = call(clients[-1], None, "POST", "/api/projects", [201],
                            {"name": f"knowledge-load-{run}-{i}"})
            projects.append("/api/projects/" + response.json()["id"])
        third_party = None
        if args.other_user:
            third_party = session(args.base_url, args.other_user,
                                  os.environ["FINFLOW_EVAL_OTHER_PASSWORD"], args.timeout)
            clients.append(third_party)

        def check_project(i):
            client = clients[i]
            path = projects[i] + "/tacit"
            commands = path + "/commands"
            before = call(client, "read", "GET", path, [200]).json()
            rev = before["revision"]
            titles = set()
            for k in range(args.rounds):
                title = f"{run}:{i}:{k}"
                request = note(rev, title)
                written = call(client, "write", "POST", commands, [200], request).json()
                replay = call(client, "replay", "POST", commands, [200], request).json()
                check(written["workspace"]["revision"] == rev + 1 and
                      replay["workspace"]["revision"] == rev + 1 and written["result"] == replay["result"],
                      "Replay changed revision or result")
                titles.add(title)
                state = call(client, "read", "GET", path, [200]).json()
                check(state["revision"] == rev + 1 and
                      sorted(r["title"] for r in state["spec"]["sources"]) == sorted(titles),
                      "Committed note missing, duplicated or visible in another project")
                rev = state["revision"]
            if third_party:
                call(third_party, "foreign_read", "GET", path, [400, 403, 404])
                call(third_party, "foreign_write", "POST", commands, [400, 403, 404], note(rev, "foreign"))
                check(call(client, "read", "GET", path, [200]).json() == state,
                      "Rejected request changed the owner's workspace")
            return rev

        with ThreadPoolExecutor(args.concurrency) as pool:
            started = perf_counter()
            revisions = list(pool.map(check_project, range(args.concurrency)))
            path = projects[0] + "/tacit"
            barrier = Barrier(args.concurrency, timeout=args.timeout)
            def compete(i):
                request = note(revisions[0], f"race:{run}:{i}")
                barrier.wait()
                return call(clients[i], "same_revision_write", "POST", path + "/commands", [200, 409],
                            request).status_code
            results = Counter(pool.map(compete, range(args.concurrency)))
            check(results == {200: 1, 409: args.concurrency - 1}, "Same revision accepted multiple writers")
            after = call(clients[0], "read", "GET", path, [200]).json()
            check(after["revision"] == revisions[0] + 1 and
                  sum(s["title"].startswith("race:") for s in after["spec"]["sources"]) == 1,
                  "Persisted result does not match the winning write")
            elapsed = perf_counter() - started
        return {"deployment_label": args.label, "model_calls": 0, "run_id": run,
                "projects": len(projects), "concurrency": args.concurrency, "rounds": args.rounds,
                "distinct_owner_denial_checked": third_party is not None,
                "expected_conflicts": results[409], "timed_requests": sum(statuses.values()),
                "timed_wall_seconds": round(elapsed, 3), "requests_per_second": round(sum(statuses.values())/elapsed, 2),
                "http_statuses": statuses, "per_operation": {k: summary(v) for k, v in timings.items()}}
    finally:
        for client, project in zip(clients, projects):
            try:
                call(client, None, "DELETE", project, [204])
            except Exception:
                cleanup_errors.append(project)
        for client in clients:
            client.close()
        if cleanup_errors:
            raise RuntimeError(f"Could not archive generated projects: {', '.join(cleanup_errors)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--label", required=True, help="Deployment/database label supplied by operator")
    parser.add_argument("--user", default="default")
    parser.add_argument("--other-user", help="Exercise authorization using a second authenticated owner")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(benchmark(args), indent=2, ensure_ascii=False)
    if args.output:
        args.output.write_text(result + "\n", encoding="utf-8")
    print(result)
