package com.runmate.global.config;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Profile("load-test")
@ConditionalOnProperty(name = "runmate.load.gate-writes", havingValue = "true")
public class RunningEventWriteGate {
    private final ConcurrentHashMap<Long, Turn> turns = new ConcurrentHashMap<>();
    private final int permits;

    public RunningEventWriteGate(@Value("${runmate.load.gate-write-permits:1}") int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("runmate.load.gate-write-permits must be positive");
        }
        this.permits = permits;
    }

    public <T> T execute(long eventId, Supplier<T> write) {
        long started = System.nanoTime();
        Turn turn = turns.compute(eventId, (id, current) -> {
            Turn entry = current == null ? new Turn(permits) : current;
            entry.users++;
            return entry;
        });

        try {
            boolean acquired = turn.slots.tryAcquire(5, TimeUnit.SECONDS);
            WriteTraceConfig.recordGateWait(System.nanoTime() - started);
            if (!acquired) {
                throw new BusinessException(ErrorCode.EVENT_WRITE_BUSY);
            }
            try {
                WriteTraceConfig.recordGateOccupancy(permits - turn.slots.availablePermits());
                return write.get();
            } finally {
                turn.slots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            WriteTraceConfig.recordGateWait(System.nanoTime() - started);
            throw new BusinessException(ErrorCode.EVENT_WRITE_BUSY);
        } finally {
            turns.computeIfPresent(eventId, (id, current) -> --current.users == 0 ? null : current);
        }
    }

    private static final class Turn {
        private final Semaphore slots;
        // 같은 모임의 map compute 안에서만 수정한다. 입장 대기자도 참조를 갖는다.
        private int users;

        private Turn(int permits) {
            slots = new Semaphore(permits, true);
        }
    }
}
