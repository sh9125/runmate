\set ON_ERROR_STOP on

\if :{?sample_count}
\else
\set sample_count 92
\endif
SELECT set_config('runmate.sample_count', :'sample_count', false) AS sample_count_setting \gset

CREATE TEMP TABLE samples (sampled_at timestamptz, connections int, active int, locks int);

DO $$
BEGIN
    -- 기본값은 250ms 간격으로 약 23초 동안 관찰한다.
    FOR sample IN 1..current_setting('runmate.sample_count')::integer LOOP
        PERFORM pg_stat_clear_snapshot();
        INSERT INTO samples
        SELECT clock_timestamp(), count(*), count(*) FILTER (WHERE state = 'active'),
               count(*) FILTER (WHERE wait_event_type = 'Lock')
        FROM pg_stat_activity
        WHERE datname = current_database() AND backend_type = 'client backend'
          AND application_name <> 'psql';
        PERFORM pg_sleep(0.25);
    END LOOP;
END $$;

SELECT min(sampled_at) AS first_sample, max(sampled_at) AS last_sample,
       count(*) AS samples, max(connections) AS peak_connections,
       max(active) AS peak_active, max(locks) AS peak_lock_waiters,
       count(*) FILTER (WHERE locks > 0) AS samples_with_lock_wait
FROM samples;
