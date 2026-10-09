package com.pitsch.backend.integration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundEventRepository extends JpaRepository<InboundEvent, Long> {

    boolean existsBySourceAndEventId(String source, String eventId);
}
