package com.pitsch.backend.config;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Agent pipelines run in the background so HTTP requests return immediately (the UI polls). */
    @Bean(name = "workflowExecutor", destroyMethod = "shutdown")
    public ExecutorService workflowExecutor(@Value("${pitsch.workflow.threads:4}") int threads) {
        return Executors.newFixedThreadPool(threads);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Thin wrapper so tests can swap in a synchronous executor. */
    @Bean
    public WorkflowRunner workflowRunner(ExecutorService workflowExecutor) {
        Executor executor = workflowExecutor;
        return executor::execute;
    }

    @FunctionalInterface
    public interface WorkflowRunner {
        void submit(Runnable task);
    }
}
