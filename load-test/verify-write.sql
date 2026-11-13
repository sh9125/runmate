\set ON_ERROR_STOP on

DO $$
DECLARE
    canceled_count bigint;
BEGIN
    IF current_database() <> 'runmate_load' OR current_user <> 'runmate_load'
        OR NOT EXISTS (SELECT 1 FROM running_events WHERE id = 2 AND capacity = 91) THEN
        RAISE EXCEPTION 'Write verification requires the RunMate write fixtures';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM running_events e
        WHERE e.id IN (1, 2)
          AND (e.current_participant_count > e.capacity OR e.current_participant_count <> (
              SELECT count(*) FROM running_event_participants p
              WHERE p.running_event_id = e.id AND p.status = 'PARTICIPATING'
          ))
    ) OR EXISTS (
        SELECT 1 FROM running_event_participants p
        JOIN running_event_waitlist_entries w USING (running_event_id, member_id)
        WHERE p.running_event_id IN (1, 2)
          AND p.status = 'PARTICIPATING' AND w.status = 'WAITING'
    ) THEN
        RAISE EXCEPTION 'Participant count, capacity, or active/waiting state mismatch';
    END IF;

    SELECT count(*) INTO canceled_count
    FROM running_event_participants
    WHERE running_event_id = 2 AND status = 'CANCELED';

    -- 앞의 N명만 승급하고, 나머지는 기존 접수 번호로 대기해야 한다.
    IF EXISTS (
        SELECT 1 FROM generate_series(1, 90) AS place(number)
        LEFT JOIN running_event_participants p
          ON p.running_event_id = 2 AND p.member_id = place.number + 91
        LEFT JOIN running_event_waitlist_entries w
          ON w.running_event_id = 2 AND w.member_id = place.number + 91
        WHERE (number <= canceled_count AND (p.status IS DISTINCT FROM 'PARTICIPATING' OR w.id IS NOT NULL))
           OR (number > canceled_count AND (p.id IS NOT NULL OR w.status IS DISTINCT FROM 'WAITING'
               OR w.queue_number IS DISTINCT FROM number::bigint))
    ) OR (SELECT current_participant_count FROM running_events WHERE id = 2) <> 91 THEN
        RAISE EXCEPTION 'Waitlist promotion order or participant count mismatch';
    END IF;
END $$;

SELECT e.id, e.capacity, e.current_participant_count,
       (SELECT count(*) FROM running_event_participants p
        WHERE p.running_event_id = e.id AND p.status = 'CANCELED') AS canceled,
       (SELECT count(*) FROM running_event_participants p
        WHERE p.running_event_id = e.id AND p.member_id > 91 AND p.status = 'PARTICIPATING') AS promoted,
       (SELECT count(*) FROM running_event_waitlist_entries w
        WHERE w.running_event_id = e.id AND w.status = 'WAITING') AS waiting
FROM running_events e
WHERE e.id IN (1, 2)
ORDER BY e.id;
