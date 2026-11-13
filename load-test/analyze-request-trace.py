import csv
import json
import math
import re
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
PG_LOCK = re.compile(
    r"(?m)^(\S+ \S+) KST \[(\d+)\] LOG: +process \d+ acquired [^\r\n]+ after ([\d.]+) ms\r?\n"
    r"(?:[^\r\n]+\[\2\] (?:DETAIL|CONTEXT):[^\r\n]+\r?\n)*"
    r"[^\r\n]+\[\2\] STATEMENT:[^\r\n]+for no key update"
)


def read_client_metrics(folder):
    timings = {}
    with (folder / "request-trace-k6.jsonl").open(encoding="utf-8-sig") as file:
        for line in file:
            point = json.loads(line)
            if point.get("type") != "Point" or point.get("metric") != "http_req_duration":
                continue
            data = point["data"]
            trace_id = data["tags"].get("trace_id")
            if trace_id:
                if trace_id in timings:
                    raise ValueError(f"Duplicate k6 trace ID: {trace_id}")
                timings[trace_id] = (data["value"], int(data["tags"]["status"]))
    return timings


def read_pg_waits(folder):
    waits = defaultdict(list)
    log = (folder / "request-trace-postgres.log").read_text(encoding="utf-8-sig")
    for match in PG_LOCK.finditer(log):
        timestamp, pid, duration = match.groups()
        at = datetime.fromisoformat(timestamp).replace(tzinfo=KST)
        waits[int(pid)].append((round(at.timestamp() * 1000), float(duration)))
    return waits


def percentile(rows, key, value):
    ordered = sorted(rows, key=lambda row: row[key])
    return ordered[math.ceil(len(ordered) * value) - 1]


def print_timings(rows, label):
    print(label, len(rows))
    metrics = ["client_ms", "gate_wait_ms", "pool_ms", "write_pool_ms", "lock_select_ms", "lock_to_commit_ms", "logged_pg_wait_ms"]
    if any(row.get("jdbc_commits") for row in rows):
        metrics.extend(("post_lock_work_ms", "post_lock_sql_ms", "commit_prepare_ms", "commit_prepare_sql_ms", "jdbc_commit_ms"))
    for metric in metrics:
        print(metric, "p50:", percentile(rows, metric, .50)[metric],
              "p95:", percentile(rows, metric, .95)[metric])


def analyze(folder):
    clients = read_client_metrics(folder)
    waits = read_pg_waits(folder)
    events = json.loads((folder / "request-trace-jfr.json").read_text(encoding="utf-8-sig"))
    server_ids = set()
    rows = []
    for event in events["recording"]["events"]:
        trace = event["values"]
        trace_id = trace["traceId"]
        if trace_id in server_ids:
            raise ValueError(f"Duplicate JFR trace ID: {trace_id}")
        server_ids.add(trace_id)
        if trace_id not in clients:
            continue
        client_ms, status = clients[trace_id]
        start = trace["lockStartedEpochMillis"]
        end = trace["lockFinishedEpochMillis"]
        pg_waits = [
            duration for at, duration in waits[trace["backendPid"]]
            if start - 2 <= at <= end + 2
        ]
        pool_ms = trace["poolAcquireNanos"] / 1_000_000
        lock_ms = trace["lockSelectNanos"] / 1_000_000
        hold_ms = trace["lockToCommitNanos"] / 1_000_000
        rows.append({
            "trace_id": trace_id, "status": status, "server_status": trace["httpStatus"],
            "client_ms": round(client_ms, 3),
            "gate_wait_ms": round(trace.get("gateWaitNanos", 0) / 1_000_000, 3),
            "gate_occupancy": trace.get("gateOccupancy", 0),
            "pool_ms": round(pool_ms, 3), "write_pool_ms": round(trace["writePoolAcquireNanos"] / 1_000_000, 3),
            "pool_acquisitions": trace["poolAcquisitions"], "lock_select_ms": round(lock_ms, 3),
            "logged_pg_wait_ms": round(sum(pg_waits), 3), "logged_pg_waits": len(pg_waits),
            "lock_to_commit_ms": round(hold_ms, 3),
            "backend_pid": trace["backendPid"],
        })
        if "jdbcCommits" in trace:
            rows[-1].update({
                "lock_returned_epoch_ms": end,
                "post_lock_work_ms": round(trace["lockToBeforeCommitNanos"] / 1_000_000, 3),
                "post_lock_sql_ms": round(trace["workSqlNanos"] / 1_000_000, 3),
                "commit_prepare_ms": round(trace["beforeCommitToJdbcNanos"] / 1_000_000, 3),
                "commit_prepare_sql_ms": round(trace["commitPreparationSqlNanos"] / 1_000_000, 3),
                "jdbc_commit_ms": round(trace["jdbcCommitNanos"] / 1_000_000, 3),
                "post_lock_sql_statements": trace["workSqlStatements"],
                "commit_prepare_sql_statements": trace["commitPreparationSqlStatements"],
                "jdbc_commits": trace["jdbcCommits"],
            })
    if not rows:
        raise ValueError("No k6 requests matched a JFR event")
    with (folder / "request-trace-joined.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print("k6:", len(clients), "JFR:", len(server_ids), "matched:", len(rows))
    print("PostgreSQL waits in log:", sum(map(len, waits.values())),
          "matched waits:", sum(row["logged_pg_waits"] for row in rows))
    for scenario in sorted({row["trace_id"].split("-")[0] for row in rows}):
        scenario_rows = [row for row in rows if row["trace_id"].startswith(scenario + "-")]
        successful = [row for row in scenario_rows if row["status"] in (200, 201)]
        no_response = [row for row in scenario_rows if row["status"] == 0]
        print_timings(scenario_rows, f"{scenario} all requests:")
        if successful:
            print("same successful request near client p95:", json.dumps(percentile(successful, "client_ms", .95)))
        if no_response:
            print_timings(no_response, f"{scenario} requests without HTTP response:")
            print("server commits for requests without HTTP response:",
                  sum(row["lock_to_commit_ms"] > 0 for row in no_response))


if __name__ == "__main__":
    analyze(Path(sys.argv[1] if len(sys.argv) > 1 else "build/reports/load-test"))
