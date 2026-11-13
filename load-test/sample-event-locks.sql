SELECT round(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS epoch_ms,
    count(*) FILTER (WHERE wait_event_type = 'Lock') AS waiting_sessions
FROM pg_stat_activity
WHERE datname = current_database() AND pid <> pg_backend_pid() AND state = 'active'
    AND query ILIKE '%for no key update%';
\watch 1
