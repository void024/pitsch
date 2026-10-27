package com.pitsch.backend.integration;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboundEventService {

    private final InboundEventRepository repo;
    private final Clock clock;

    public InboundEventService(InboundEventRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * Records the event in the CALLER's transaction, so the marker commits only if processing succeeds (a failed
     * processing attempt is retried by the sender). A concurrent duplicate violates the unique key and fails its own
     * transaction; the sender's retry then sees the marker and is acknowledged without reprocessing.
     *
     * @return true the first time this event is seen; false for a redelivery
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean firstDelivery(String source, String eventId) {
        if (repo.existsBySourceAndEventId(source, eventId)) {
            return false;
        }
        repo.saveAndFlush(new InboundEvent(source, eventId, clock.instant()));
        return true;
    }
}
