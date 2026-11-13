\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() <> 'runmate_load' OR current_user <> 'runmate_load'
        OR NOT EXISTS (
            SELECT 1 FROM running_events WHERE id = 3 AND capacity = 91
                AND current_participant_count = 91 AND status = 'RECRUITING'
        ) THEN
        RAISE EXCEPTION 'Mixed verification requires the RunMate mixed fixtures';
    END IF;

    IF (SELECT count(*) FROM running_event_participants WHERE running_event_id = 3
            AND status = 'PARTICIPATING' AND member_id BETWEEN 1 AND 91) <> 91
        OR (SELECT count(*) FROM running_event_waitlist_entries WHERE running_event_id = 3
            AND status = 'WAITING' AND member_id = 91 + queue_number
            AND queue_number BETWEEN 1 AND 90) <> 90 THEN
        RAISE EXCEPTION 'Separate read event changed';
    END IF;

    -- 신규 신청자는 기존 대기자 뒤에 연속된 번호로 들어가야 한다.
    IF EXISTS (
        SELECT 1 FROM (
            SELECT member_id, status, queue_number,
                   row_number() OVER (ORDER BY queue_number) AS new_position
            FROM running_event_waitlist_entries
            WHERE running_event_id = 2 AND (queue_number > 90 OR member_id > 181)
        ) AS new_entries
        WHERE member_id NOT BETWEEN 182 AND 222 OR status <> 'WAITING'
            OR queue_number <> 90 + new_position
    ) THEN
        RAISE EXCEPTION 'New applicants did not join the back of the waitlist';
    END IF;
END $$;

SELECT count(*) AS committed_applications
FROM running_event_waitlist_entries
WHERE running_event_id = 2 AND member_id BETWEEN 182 AND 222 AND status = 'WAITING';
