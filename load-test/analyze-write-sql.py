import csv
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


def label(sql):
    verb = sql.split()[0]
    table = re.search(r'\b(?:from|into|update) (\w+)', sql).group(1)
    if verb == 'select' and 'max(' in sql:
        return f'{table} select max'
    if verb == 'select' and 'count(' in sql:
        return f'{table} select count'
    if verb == 'select' and 'order by' in sql:
        return f'{table} select first'
    return f'{table} {verb}'


def p95(values):
    return sorted(values)[math.ceil(.95 * len(values)) - 1]


def analyze(folder):
    log = (folder / 'request-trace.log').read_text(encoding='utf-8-sig')
    started = re.search(r'msg="apply_20 starts at (\S+)"', log)[1]
    stage_start = datetime.fromisoformat(started).timestamp() * 1000
    values = json.loads((folder / 'write-sql-jfr.json').read_text(encoding='utf-8-sig'))['recording']['events']
    with (folder / 'request-trace-joined.csv').open(encoding='utf-8', newline='') as file:
        traces = {row['trace_id']: row for row in csv.DictReader(file)}
    samples = defaultdict(list)
    with (folder / 'sql-wait-samples.csv').open(encoding='utf-8-sig', newline='') as file:
        for at, pid, state, wait_type, wait_event, query_start in csv.reader(file):
            samples[int(pid)].append((int(at), state, wait_type, wait_event, int(query_start)))

    def active_waits(pid, start, end):
        return Counter(wait_type + '/' + wait_event if wait_type else 'active/no wait event'
                       for at, state, wait_type, wait_event, query_start in samples[pid]
                       if start <= at <= end and state == 'active' and start - 15 <= query_start <= at)

    rows = []
    counts = Counter()
    for event in values:
        trace = event['values']
        trace_id = trace['traceId']
        phase = trace['phase']
        sql = trace['sql']
        pid = trace['backendPid']
        start = trace['startedEpochMillis']
        elapsed = trace['elapsedNanos'] / 1_000_000
        end = start + elapsed
        if str(pid) != traces[trace_id]['backend_pid'] or elapsed <= 0:
            raise ValueError(f'Invalid SQL trace for {trace_id}')
        counts[trace_id, phase] += 1
        waits = active_waits(pid, start, end)
        idle = sum(state == 'idle in transaction' for at, state, *_ in samples[pid] if start <= at <= end)
        rows.append({'trace_id': trace_id, 'phase': phase, 'label': label(sql), 'elapsed_ms': round(elapsed, 3),
                     'started_s': round((start - stage_start) / 1000, 3), 'active_samples': dict(waits),
                     'idle_in_transaction_samples': idle, 'backend_pid': pid, 'sql': sql})
    for trace_id, trace in traces.items():
        if (counts[trace_id, 'work'] != int(trace['post_lock_sql_statements'])
                or counts[trace_id, 'prepare'] != int(trace['commit_prepare_sql_statements'])):
            raise ValueError(f'JDBC and Hibernate SQL counts differ for {trace_id}')
    with (folder / 'sql-by-statement.csv').open('w', encoding='utf-8', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    print('requests:', len(traces), 'SQL executions:', len(rows), 'sample rows:', sum(map(len, samples.values())))
    lock_waits = Counter()
    requests = json.loads((folder / 'request-trace-jfr.json').read_text(encoding='utf-8-sig'))['recording']['events']
    for event in requests:
        trace = event['values']
        lock_waits.update(active_waits(trace['backendPid'], trace['lockStartedEpochMillis'], trace['lockFinishedEpochMillis']))
    print('matching lock SELECT samples:', dict(lock_waits))
    print('matching post-lock SQL samples:', dict(sum((Counter(row['active_samples']) for row in rows), Counter())))
    for first, last in ((0, 3), (3, 6), (6, 30), (30, math.inf)):
        phase_rows = [row for row in rows if first <= row['started_s'] < last]
        groups = defaultdict(list)
        for row in phase_rows:
            groups[row['label']].append(row['elapsed_ms'])
        print(f'{first}-{last}s:', 'SQL executions:', len(phase_rows))
        for name, durations in sorted(groups.items(), key=lambda item: sum(item[1]), reverse=True):
            print(name, 'count:', len(durations), 'sum ms:', round(sum(durations), 1),
                  'p95 ms:', round(p95(durations), 1), 'max ms:', round(max(durations), 1))
        for row in sorted(phase_rows, key=lambda value: value['elapsed_ms'], reverse=True)[:5]:
            print({key: value for key, value in row.items() if key != 'sql'})


if __name__ == '__main__':
    analyze(Path(sys.argv[1] if len(sys.argv) > 1 else 'build/reports/load-test/sql-run-2'))
