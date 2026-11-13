import argparse
import csv
import json
import math
import re
from collections import Counter
from datetime import datetime
from pathlib import Path

PHASES = (
    'lock_to_commit_ms', 'post_lock_work_ms', 'post_lock_sql_ms',
    'commit_prepare_ms', 'commit_prepare_sql_ms', 'jdbc_commit_ms',
)


def summarize(rows):
    def percentile(field, rank):
        values = sorted(float(row[field]) for row in rows)
        return round(values[math.ceil(rank * len(values)) - 1], 2)

    return {field: [percentile(field, .50), percentile(field, .95)] for field in PHASES}


def analyze(folder, allow_partial):
    with (folder / 'request-trace-joined.csv').open(encoding='utf-8', newline='') as file:
        rows = list(csv.DictReader(file))
    summary = json.loads((folder / 'request-trace.json').read_text(encoding='utf-8-sig'))['results']
    start_match = re.search(r'msg="apply_20 starts at (\S+)"', (folder / 'request-trace.log').read_text(encoding='utf-8-sig'))
    start = datetime.fromisoformat(start_match[1]).timestamp() * 1000
    commit_buckets = Counter()
    for row in rows:
        work = float(row['post_lock_work_ms'])
        prepare = float(row['commit_prepare_ms'])
        commit = float(row['jdbc_commit_ms'])
        total = float(row['lock_to_commit_ms'])
        if (row['jdbc_commits'] != '1' or min(work, prepare, commit) <= 0
                or work + prepare + commit > total + .003
                or float(row['post_lock_sql_ms']) > work + .003
                or float(row['commit_prepare_sql_ms']) > prepare + .003):
            raise ValueError(f"Invalid commit phases for {row['trace_id']}")
        elapsed = (float(row['lock_returned_epoch_ms']) - start) / 1000
        row['lock_returned_elapsed_seconds'] = elapsed
        commit_buckets[math.floor((elapsed + total / 1000) / 3)] += 1
    sent = sum(item['completed'] for item in summary if item['operation'] != 'detail')
    if len(rows) != sent and not allow_partial:
        raise ValueError('Not all write requests matched to JFR; use --allow-partial to inspect the captured subset')

    print(folder.name, 'matched writes:', len(rows), 'of', sent)
    for item in summary:
        print(item['operation'], 'ok:', item['ok'], 'timeout:', item['timeout'], 'dropped:', item['dropped'],
              'successful HTTP p95:', item['p95Ms'])
    if len(rows) == sent:
        print('phase p50/p95 in ms:', summarize(rows))
    for lower in range(0, 30, 3):
        window = [row for row in rows if lower <= row['lock_returned_elapsed_seconds'] < lower + 3]
        if window:
            print(f'{lower}-{lower + 3}s:', 'lock returns:', len(window), 'commits:', commit_buckets[lower // 3],
                  'phase p50/p95:', summarize(window))
    for row in sorted(rows, key=lambda value: float(value['lock_to_commit_ms']), reverse=True)[:5]:
        print('same request:', row['trace_id'], 'lock returned at s:', round(row['lock_returned_elapsed_seconds'], 3),
              {field: row[field] for field in PHASES})


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folders', nargs='+', type=Path)
    parser.add_argument('--allow-partial', action='store_true')
    options = parser.parse_args()
    for folder in options.folders:
        analyze(folder, options.allow_partial)
