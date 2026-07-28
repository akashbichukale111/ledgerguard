package dev.ledgerguard.query.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryServiceConfig {

    /** All time comes from here, so tests drive a fixed clock rather than sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
