\set ON_ERROR_STOP on
\if :{?spread_probe}
\set last_event 8
\else
\set last_event 4
\endif
SELECT set_config('runmate.load_verify_last_event', :'last_event', false) AS verify_setting \gset

DO $$
DECLARE
    event_id bigint;
    last_event int := current_setting('runmate.load_verify_last_event')::int;
    canceled_count bigint;
BEGIN
    IF current_database() <> 'runmate_load' OR current_user <> 'runmate_load'
        OR (SELECT count(*) FROM running_events WHERE id BETWEEN 2 AND last_event AND capacity = 602) <> last_event - 1 THEN
        RAISE EXCEPTION 'Sustained verification requires the RunMate sustained fixtures';
    END IF;

    FOR event_id IN 2..last_event LOOP
        SELECT count(*) INTO canceled_count FROM running_event_participants
        WHERE running_event_id = event_id AND status = 'CANCELED' AND member_id BETWEEN 2 AND 602;

        IF (SELECT current_participant_count FROM running_events WHERE id = event_id) <> 602
            OR (SELECT count(*) FROM running_event_participants
                WHERE running_event_id = event_id AND status = 'PARTICIPATING') <> 602
            OR EXISTS (
                SELECT 1 FROM running_event_participants p
                JOIN running_event_waitlist_entries w USING (running_event_id, member_id)
                WHERE p.running_event_id = event_id
                    AND p.status = 'PARTICIPATING' AND w.status = 'WAITING'
            ) THEN
            RAISE EXCEPTION 'Participant count or active/waiting state mismatch for event %', event_id;
        END IF;

        IF EXISTS (
            SELECT 1 FROM generate_series(1, 602) AS place(number)
            LEFT JOIN running_event_participants p
              ON p.running_event_id = event_id AND p.member_id = place.number + 602
            LEFT JOIN running_event_waitlist_entries w
              ON w.running_event_id = event_id AND w.member_id = place.number + 602
            WHERE (number <= canceled_count AND (p.status IS DISTINCT FROM 'PARTICIPATING' OR w.id IS NOT NULL))
               OR (number > canceled_count AND (p.id IS NOT NULL OR w.status IS DISTINCT FROM 'WAITING'
                   OR w.queue_number IS DISTINCT FROM number::bigint))
        ) THEN
            RAISE EXCEPTION 'Promotion order mismatch for event %', event_id;
        END IF;

        IF EXISTS (
            SELECT 1 FROM (
                SELECT member_id, queue_number, status,
                    row_number() OVER (ORDER BY queue_number) AS new_position
                FROM running_event_waitlist_entries
                WHERE running_event_id = event_id AND (queue_number > 602 OR member_id > 1204)
            ) AS entries
            WHERE member_id NOT BETWEEN 1205 AND 1805 OR status <> 'WAITING'
                OR queue_number <> 602 + new_position
        ) THEN
            RAISE EXCEPTION 'New applicants did not join the back of the waitlist for event %', event_id;
        END IF;
    END LOOP;
END $$;

SELECT e.id AS event_id, e.current_participant_count AS stored,
    (SELECT count(*) FROM running_event_participants p
     WHERE p.running_event_id = e.id AND p.status = 'PARTICIPATING') AS active,
    (SELECT count(*) FROM running_event_participants p
     WHERE p.running_event_id = e.id AND p.status = 'CANCELED') AS canceled,
    (SELECT count(*) FROM running_event_participants p
     WHERE p.running_event_id = e.id AND p.member_id BETWEEN 603 AND 1204
         AND p.status = 'PARTICIPATING') AS promoted,
    (SELECT count(*) FROM running_event_waitlist_entries w
     WHERE w.running_event_id = e.id AND w.member_id BETWEEN 1205 AND 1805
         AND w.status = 'WAITING') AS new_waiting
FROM running_events e WHERE e.id BETWEEN 2 AND :last_event ORDER BY e.id;
