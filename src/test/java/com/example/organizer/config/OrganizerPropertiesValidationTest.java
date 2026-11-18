package com.example.organizer.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Invalid settings stop the application at startup with a clear message.
 */
class OrganizerPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(OrganizerProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void acceptsTheDefaults() {
        context.run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void rejectsNonPositiveLimits() {
        for (String setting : new String[] {
                "organizer.max-files=0",
                "organizer.max-content-characters=-1",
                "organizer.agent.max-model-calls=0",
                "organizer.agent.max-tokens-per-run=0",
                "organizer.llm.max-retries=-1"}) {
            context.withPropertyValues(setting).run(started -> assertThat(started)
                    .as(setting)
                    .hasFailed()
                    .getFailure().rootCause().hasMessageContaining("must be"));
        }
    }
}
