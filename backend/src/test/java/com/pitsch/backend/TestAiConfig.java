package com.pitsch.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the HTTP AI client with recorded agent responses. Shared so Spring can cache one test context. */
@TestConfiguration
public class TestAiConfig {

    @Bean
    @Primary
    FakeAiClient fakeAiClient(ObjectMapper om) {
        return new FakeAiClient(om);
    }
}
