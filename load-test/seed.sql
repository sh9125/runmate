\set ON_ERROR_STOP on
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'runmate_load' OR current_user <> 'runmate_load' THEN
        RAISE EXCEPTION 'RunMate load fixtures require the runmate_load database and role';
    END IF;
END $$;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

\set write_capacity 91
\set initial_waiters 90
\if :{?sustained_writes}
\set write_baseline 1
\set member_count 1805
\set write_capacity 602
\set initial_waiters 602
\set mirror_events 2
\if :{?midrange_probe}
\set mirror_events 3
\endif
\if :{?spread_probe}
\set mirror_events 6
\endif
\elif :{?mixed_baseline}
\set write_baseline 1
\set member_count 250
\set mirror_events 1
\elif :{?write_baseline}
\set member_count 200
\else
\set member_count 100
\endif

TRUNCATE auth_refresh_sessions, running_event_waitlist_entries,
    running_event_participants, running_events, members RESTART IDENTITY;

WITH credential AS MATERIALIZED (
    SELECT crypt('RunMateLoad123!', gen_salt('bf', 10)) AS password_hash
)
INSERT INTO members (
    email, password, name, nickname, region, average_pace_seconds,
    preferred_distance_km, created_at, updated_at
)
SELECT 'runner' || number || '@runmate.test', password_hash,
    'Runner ' || number, 'load-runner-' || number, 'Seoul', 360, 5.00,
    LOCALTIMESTAMP, LOCALTIMESTAMP
FROM generate_series(1, :member_count) AS runner(number)
CROSS JOIN credential
ORDER BY number;

INSERT INTO running_events (
    title, description, region, running_place, meeting_place, start_at,
    preferred_pace_seconds, target_distance_km, capacity,
    current_participant_count, status, creator_id, created_at, updated_at
)
VALUES
    ('Last available spot', 'Participation and waitlist scenario', 'Seoul',
     'Han River', 'Park entrance', LOCALTIMESTAMP + INTERVAL '7 days',
     360, 5.00, 2, 1, 'RECRUITING', 1, LOCALTIMESTAMP, LOCALTIMESTAMP),
    ('Waitlist promotion', 'Cancellation and promotion scenario', 'Seoul',
     'Han River', 'Park entrance', LOCALTIMESTAMP + INTERVAL '7 days',
     360, 5.00, 2, 2, 'RECRUITING', 1, LOCALTIMESTAMP, LOCALTIMESTAMP);

INSERT INTO running_event_participants (running_event_id, member_id, status, created_at)
VALUES
    (1, 1, 'PARTICIPATING', LOCALTIMESTAMP),
    (2, 1, 'PARTICIPATING', LOCALTIMESTAMP),
    (2, 2, 'PARTICIPATING', LOCALTIMESTAMP);

INSERT INTO running_event_waitlist_entries (
    running_event_id, member_id, queue_number, requested_at, status
)
VALUES
    (2, 3, 1, LOCALTIMESTAMP, 'WAITING'),
    (2, 4, 2, LOCALTIMESTAMP, 'WAITING');

\if :{?write_baseline}
UPDATE running_events
SET capacity = :write_capacity, current_participant_count = :write_capacity
WHERE id = 2;

INSERT INTO running_event_participants (running_event_id, member_id, status, created_at)
SELECT 2, number, 'PARTICIPATING', LOCALTIMESTAMP
FROM generate_series(3, :write_capacity) AS runner(number);

DELETE FROM running_event_waitlist_entries WHERE running_event_id = 2;
INSERT INTO running_event_waitlist_entries (
    running_event_id, member_id, queue_number, requested_at, status
)
SELECT 2, number + :write_capacity, number, LOCALTIMESTAMP, 'WAITING'
FROM generate_series(1, :initial_waiters) AS place(number);
\endif

\if :{?mirror_events}
INSERT INTO running_events (
    title, description, region, running_place, meeting_place, start_at,
    preferred_pace_seconds, target_distance_km, capacity,
    current_participant_count, status, creator_id, created_at, updated_at
)
SELECT title, description, region, running_place, meeting_place, start_at,
    preferred_pace_seconds, target_distance_km, capacity,
    current_participant_count, status, creator_id, created_at, updated_at
FROM running_events CROSS JOIN generate_series(1, :mirror_events)
WHERE id = 2;

INSERT INTO running_event_participants (running_event_id, member_id, status, created_at)
SELECT mirror_id, member_id, status, created_at
FROM running_event_participants CROSS JOIN generate_series(3, 2 + :mirror_events) AS mirror(mirror_id)
WHERE running_event_id = 2;

INSERT INTO running_event_waitlist_entries (
    running_event_id, member_id, queue_number, requested_at, status
)
SELECT mirror_id, member_id, queue_number, requested_at, status
FROM running_event_waitlist_entries CROSS JOIN generate_series(3, 2 + :mirror_events) AS mirror(mirror_id)
WHERE running_event_id = 2;
\elif :{?read_baseline}
INSERT INTO running_events (
    title, description, region, running_place, meeting_place, start_at,
    preferred_pace_seconds, target_distance_km, capacity,
    current_participant_count, status, creator_id, created_at, updated_at
)
SELECT 'Read baseline event ' || number, 'List pagination fixture', 'Seoul',
    'Han River', 'Park entrance', LOCALTIMESTAMP + INTERVAL '7 days',
    360, 5.00, 2, 1, 'RECRUITING', 1,
    LOCALTIMESTAMP - number * INTERVAL '1 second', LOCALTIMESTAMP
FROM generate_series(3, 1000) AS event(number)
ORDER BY number;

INSERT INTO running_event_participants (running_event_id, member_id, status, created_at)
SELECT id, creator_id, 'PARTICIPATING', created_at
FROM running_events
WHERE id > 2;
\endif

COMMIT;
