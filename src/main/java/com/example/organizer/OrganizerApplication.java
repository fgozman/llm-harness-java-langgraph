package com.example.organizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point. Spring creates all components (nodes, services, configuration)
 * and then starts OrganizerRunner, which runs the agent.
 */
@SpringBootApplication
// Finds @ConfigurationProperties records such as OrganizerProperties and fills them from application.yml.
@ConfigurationPropertiesScan
public class OrganizerApplication {

    /**
     * The organizer is a one-shot command line tool: once the agent is done, the application exits.
     * SpringApplication.exit closes the context and returns OrganizerRunner's exit code
     * (e.g. 2 when another run is working on the folder); System.exit hands it to the shell.
     *
     * The work itself runs on virtual threads (the graph, see OrganizerAgent; the model's HTTP client, see
     * OrganizerConfiguration). This main thread starts the run and asks the human on the console.
     */
    public static void main(String[] args) {
        var context = SpringApplication.run(OrganizerApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
