package com.pitsch.backend.jobs;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.observability.PitschMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the job table, atomically claims runnable jobs and executes them on a bounded thread pool.
 * Failures are retried with exponential backoff (5 s, 10 s, 20 s ... capped at 10 min) up to the type's
 * maxAttempts, then dead-lettered (status DEAD, handler.onGiveUp). Jobs whose worker died are re-queued after
 * the lock timeout. Safe with several backend instances.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final JobRepository jobs;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final List<JobHandler> handlerBeans;
    private final PitschProperties.Jobs config;
    private final TransactionTemplate tx;
    private final PitschMetrics metrics;
    private final Json json;
    private final Clock clock;
    private final ExecutorService pool;
    private final ExecutorService inlinePool = Executors.newSingleThreadExecutor(r -> new Thread(r, "job-inline"));
    private final Semaphore capacity;
    private final ThreadLocal<Deque<Long>> inlinePending = new ThreadLocal<>();
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + Integer.toHexString(System.identityHashCode(this));

    public JobRunner(JobRepository jobs, @Lazy List<JobHandler> handlerBeans, PitschProperties props,
                     PlatformTransactionManager txManager, PitschMetrics metrics, Json json, Clock clock) {
        this.jobs = jobs;
        this.handlerBeans = handlerBeans;
        this.config = props.getJobs();
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int threads = Math.max(1, config.getThreads());
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "job-worker");
            t.setDaemon(true);
            return t;
        });
        this.capacity = new Semaphore(threads);
    }

    public boolean isInline() {
        return config.isInline();
    }

    /**
     * Inline mode (tests): run the job now on a clean thread (no transaction context leaks) and wait for it. Jobs
     * enqueued by an inline job are drained on the same thread before the caller is released.
     */
    public void runInlineNow(Long jobId) {
        Deque<Long> pending = inlinePending.get();
        if (pending != null) {
            pending.add(jobId);
            return;
        }
        Future<?> f = inlinePool.submit(() -> {
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(jobId);
            inlinePending.set(queue);
            try {
                while (!queue.isEmpty()) {
                    Long next = queue.poll();
                    if (claim(next)) {
                        execute(next);
                    }
                }
            } finally {
                inlinePending.remove();
            }
        });
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("Inline job {} crashed", jobId, e.getCause());
        }
    }

    @Scheduled(fixedDelayString = "${pitsch.jobs.poll-interval-ms:1000}", initialDelay = 5000)
    public void poll() {
        if (!config.isEnabled() || config.isInline()) {
            return;
        }
        try {
            metrics.setQueueDepth(jobs.countByStatus(Job.Status.QUEUED.name()));
            int free = capacity.availablePermits();
            if (free == 0) {
                return;
            }
            for (Long id : jobs.findRunnable(clock.instant(), PageRequest.of(0, free))) {
                if (!capacity.tryAcquire()) {
                    break;
                }
                if (!claim(id)) {
                    capacity.release();
                    continue;
                }
                pool.submit(() -> {
                    try {
                        execute(id);
                    } finally {
                        capacity.release();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("Job polling failed: {}", e.getMessage());
        }
    }

    /** Re-queues jobs whose worker disappeared (crash, deploy) — they were RUNNING longer than the lock timeout. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverStale() {
        if (!config.isEnabled()) {
            return;
        }
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(config.getLockTimeoutMinutes()));
        for (Job stale : jobs.findStale(cutoff)) {
            tx.executeWithoutResult(s -> jobs.findById(stale.getId()).ifPresent(j -> {
                if (!Job.Status.RUNNING.name().equals(j.getStatus())) {
                    return;
                }
                log.warn("Recovering stale job {} ({}) locked by {}", j.getId(), j.getType(), j.getLockedBy());
                j.setStatus(j.getAttempts() >= j.getMaxAttempts() ? Job.Status.DEAD.name() : Job.Status.QUEUED.name());
                j.setLastError("Worker lock expired");
                j.setLockedBy(null);
                j.setLockedAt(null);
                j.setUpdatedAt(clock.instant());
                jobs.save(j);
            }));
        }
    }

    @Scheduled(cron = "0 23 4 * * *")
    public void purgeFinished() {
        tx.executeWithoutResult(s -> jobs.deleteFinishedBefore(clock.instant().minus(Duration.ofDays(14))));
    }

    private boolean claim(Long id) {
        Integer claimed = tx.execute(s -> jobs.claim(id, workerId, clock.instant()));
        return claimed != null && claimed == 1;
    }

    private void execute(Long id) {
        Job job = tx.execute(s -> jobs.findById(id).orElse(null));
        if (job == null) {
            return;
        }
        JobHandler handler = handler(job.getType());
        JsonNode payload = json.read(job.getPayloadJson());
        MDC.put("jobId", String.valueOf(id));
        MDC.put("jobType", job.getType());
        try {
            if (handler == null) {
                throw new IllegalArgumentException("No handler for job type " + job.getType());
            }
            handler.handle(job, payload);
            finish(id, Job.Status.SUCCEEDED, null, null);
            metrics.jobFinished(job.getType(), "succeeded");
        } catch (Exception e) {
            boolean retry = handler != null && handler.isRetryable(e) && job.getAttempts() < job.getMaxAttempts();
            String message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            if (retry) {
                long seconds = Math.min(MAX_BACKOFF.toSeconds(), 5L * (1L << Math.min(10, job.getAttempts() - 1)));
                log.warn("Job {} ({}) attempt {} failed, retrying in {}s: {}", id, job.getType(), job.getAttempts(), seconds, message);
                finish(id, Job.Status.QUEUED, message, clock.instant().plusSeconds(seconds));
                metrics.jobFinished(job.getType(), "retry");
            } else {
                log.error("Job {} ({}) gave up after {} attempts: {}", id, job.getType(), job.getAttempts(), message, e);
                finish(id, Job.Status.DEAD, message, null);
                metrics.jobFinished(job.getType(), "dead");
                if (handler != null) {
                    try {
                        handler.onGiveUp(job, payload, e);
                    } catch (RuntimeException inner) {
                        log.error("onGiveUp failed for job {}", id, inner);
                    }
                }
            }
        } finally {
            MDC.remove("jobId");
            MDC.remove("jobType");
        }
    }

    private void finish(Long id, Job.Status status, String error, Instant retryAt) {
        tx.executeWithoutResult(s -> jobs.findById(id).ifPresent(j -> {
            j.setStatus(status.name());
            j.setLastError(error == null ? null : Json.truncate(error, 2000));
            j.setLockedBy(null);
            j.setLockedAt(null);
            j.setUpdatedAt(clock.instant());
            if (retryAt != null) {
                j.setRunAt(retryAt);
            }
            if (status == Job.Status.SUCCEEDED || status == Job.Status.DEAD) {
                j.setCompletedAt(clock.instant());
            }
            jobs.save(j);
        }));
    }

    private JobHandler handler(String type) {
        if (handlers.isEmpty()) {
            synchronized (handlers) {
                if (handlers.isEmpty()) {
                    for (JobHandler h : handlerBeans) {
                        handlers.put(h.type(), h);
                    }
                }
            }
        }
        try {
            return handlers.get(JobType.valueOf(type));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        inlinePool.shutdown();
    }
}
