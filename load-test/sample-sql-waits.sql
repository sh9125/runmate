WITH sample AS MATERIALIZED (
    SELECT round(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS epoch_ms
)
SELECT sample.epoch_ms, pid, state, coalesce(wait_event_type, ''), coalesce(wait_event, ''),
    round(extract(epoch FROM query_start) * 1000)::bigint AS query_started_ms
FROM pg_stat_activity CROSS JOIN sample
WHERE datname = current_database() AND pid <> pg_backend_pid()
    AND backend_type = 'client backend' AND state IN ('active', 'idle in transaction');
\watch 0.1
