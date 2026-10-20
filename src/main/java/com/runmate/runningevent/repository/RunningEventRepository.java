package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RunningEventRepository extends JpaRepository<RunningEvent, Long> {
}
