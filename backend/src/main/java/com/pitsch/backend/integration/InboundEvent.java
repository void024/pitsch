package com.pitsch.backend.integration;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** De-duplication of inbound webhooks (Gmail push, Stripe): one row per (source, event ID). */
@Entity
@Table(name = "inbound_events")
public class InboundEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false, length = 200)
    private String eventId;

    @Column(nullable = false)
    private Instant receivedAt;

    protected InboundEvent() { }

    public InboundEvent(String source, String eventId, Instant receivedAt) {
        this.source = source;
        this.eventId = eventId;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getEventId() { return eventId; }
    public Instant getReceivedAt() { return receivedAt; }
}
