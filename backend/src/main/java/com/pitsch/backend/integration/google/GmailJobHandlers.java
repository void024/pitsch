package com.pitsch.backend.integration.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobType;
import org.springframework.stereotype.Component;

public final class GmailJobHandlers {

    private GmailJobHandlers() { }

    @Component
    public static class Sync implements JobHandler {
        private final GmailSyncService sync;

        public Sync(GmailSyncService sync) {
            this.sync = sync;
        }

        @Override
        public JobType type() {
            return JobType.GMAIL_SYNC;
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            sync.sync(payload.path("connectionId").asLong());
        }
    }

    @Component
    public static class IngestMessage implements JobHandler {
        private final GmailSyncService sync;

        public IngestMessage(GmailSyncService sync) {
            this.sync = sync;
        }

        @Override
        public JobType type() {
            return JobType.GMAIL_INGEST_MESSAGE;
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            sync.ingest(payload.path("connectionId").asLong(), payload.path("messageId").asText());
        }
    }

    @Component
    public static class Watch implements JobHandler {
        private final GmailSyncService sync;

        public Watch(GmailSyncService sync) {
            this.sync = sync;
        }

        @Override
        public JobType type() {
            return JobType.GMAIL_WATCH;
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            sync.renewWatch(payload.path("connectionId").asLong());
        }
    }
}
