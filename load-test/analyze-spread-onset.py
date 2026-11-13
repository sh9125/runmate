import csv
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


def time_ms(iso_time):
    return datetime.fromisoformat(iso_time).timestamp() * 1000


def read_request_times(folder):
    requests = defaultdict(dict)
    with (folder / 'request-trace-k6.jsonl').open(encoding='utf-8-sig') as file:
        for line in file:
            point = json.loads(line)
            if point.get('type') != 'Point' or point.get('metric') not in ('http_req_duration', 'http_req_blocked'):
                continue
            data = point['data']
            trace_id = data['tags'].get('trace_id')
            if trace_id:
                requests[trace_id][point['metric']] = data['value']
                requests[trace_id]['end_ms'] = time_ms(data['time'])
    return requests


def percentile(values, rank=.95):
    if values:
        return round(sorted(values)[math.ceil(len(values) * rank) - 1], 3)
    return None


def analyze(folder):
    requests = read_request_times(folder)
    raw = json.loads((folder / 'request-trace-jfr.json').read_text(encoding='utf-8-sig'))['recording']['events']
    starts = {int(stage): time_ms(at) for stage, at in re.findall(
        r'msg="apply_(\d+) starts at (\S+)"', (folder / 'request-trace.log').read_text(encoding='utf-8-sig'))}
    samples = [tuple(map(int, line.split(','))) for line in (folder / 'lock-samples.csv').read_text(
        encoding='utf-8-sig').splitlines() if line]
    stages = defaultdict(list)
    for event in raw:
        value = event['values']
        trace_id = value['traceId']
        if trace_id not in requests or not value['lockToCommitNanos']:
            raise ValueError(f'Missing matching k6 request or commit for {trace_id}')
        stage = int(trace_id.split('-')[0].split('_')[-1])
        request = requests[trace_id]
        client_ms = request['end_ms'] - request['http_req_duration'] - request['http_req_blocked']
        lock_ms = value['lockFinishedEpochMillis']
        hold_ms = value['lockToCommitNanos'] / 1_000_000
        stages[stage].append((trace_id, client_ms, lock_ms, hold_ms, value['poolAcquireNanos'] / 1_000_000))

    if sum(map(len, stages.values())) != len(requests):
        raise ValueError('k6 and JFR request counts differ')
    for stage, rows in sorted(stages.items()):
        # 추정한 첫 전송 시각을 기준으로 삼아 동시에 시작한 요청이 앞 구간에 들어가지 않게 한다.
        start = min(row[1] for row in rows)

        def second(at):
            return math.floor((at - start) / 1000)

        sends = Counter(second(sent) for _, sent, _, _, _ in rows)
        commits = Counter(second(locked + hold) for _, _, locked, hold, _ in rows)
        locks = defaultdict(list)
        pools = defaultdict(list)
        for _, sent, locked, hold, pool in rows:
            locks[second(locked)].append(hold)
            pools[second(sent)].append(pool)
        wait_samples = {second(at): count for at, count in samples if 0 <= second(at) < 30}
        totals = []
        pending = 0
        for interval in range(max(max(sends), max(commits)) + 1):
            pending += sends[interval] - commits[interval]
            totals.append({
                'second': interval, 'sent': sends[interval], 'committed': commits[interval],
                'sent_minus_committed': pending, 'held_p50_ms': percentile(locks[interval], .50),
                'held_p95_ms': percentile(locks[interval]), 'pool_p95_ms': percentile(pools[interval]),
                'sessions_waiting_for_lock': wait_samples.get(interval),
            })
        with (folder / f'onset-stage-{stage}.csv').open('w', encoding='utf-8', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=list(totals[0]))
            writer.writeheader()
            writer.writerows(totals)
        print('stage', stage, 'first send relative to logged start ms', round(start - starts[stage], 1),
              'writes', len(rows), 'peak pending', max(row['sent_minus_committed'] for row in totals))
        for first, last in ((0, 3), (3, 6), (6, 9)):
            window = range(first, last)
            print(f'{first}-{last}s:', 'sent', sum(sends[i] for i in window),
                  'committed', sum(commits[i] for i in window),
                  'held p95 ms', percentile([hold for i in window for hold in locks[i]]))


if __name__ == '__main__':
    analyze(Path(sys.argv[1] if len(sys.argv) > 1 else 'build/reports/load-test/final-spread'))
