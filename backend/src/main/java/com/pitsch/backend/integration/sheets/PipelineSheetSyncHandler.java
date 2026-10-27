package com.pitsch.backend.integration.sheets;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobType;
import org.springframework.stereotype.Component;

@Component
public class PipelineSheetSyncHandler implements JobHandler {

    private final PipelineSyncService sync;

    public PipelineSheetSyncHandler(PipelineSyncService sync) {
        this.sync = sync;
    }

    @Override
    public JobType type() {
        return JobType.PIPELINE_SHEET_SYNC;
    }

    @Override
    public void handle(Job job, JsonNode payload) {
        sync.sync(job.getOrganizationId(), payload.path("pitchId").asLong(), payload.path("key").asText("sheet:" + job.getId()));
    }
}
