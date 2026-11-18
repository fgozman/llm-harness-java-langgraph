package com.example.organizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Setup helper, not part of the agent: writes the demo files into a folder
 * so the agent has something to organize. Existing files are never overwritten.
 */
@Component
public class DemoFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(DemoFileGenerator.class);

    /**
     * Name and content of each demo file, covering several domains
     * (finance, health, travel, technology, cooking, education).
     * File names are deliberately neutral, so the category must come from the content.
     */
    private static final List<Map.Entry<String, String>> DEMO_FILES = List.of(
            Map.entry("document_01.txt", """
                    Invoice #2024-117
                    Amount due: 1,250.00 EUR
                    Please transfer the payment to our bank account before the end of the month.
                    VAT and tax details are listed below.
                    """),
            Map.entry("notes_march.txt", """
                    Monthly budget review:
                    - Salary deposited into savings account
                    - Credit card expenses were higher than planned
                    - Increase investment in the index fund
                    """),
            Map.entry("report.md", """
                    # Blood test results
                    Cholesterol is slightly elevated. The doctor recommends more exercise,
                    a balanced diet and a follow-up appointment at the clinic in three months.
                    """),
            Map.entry("weekly_plan.txt", """
                    Workout schedule: Monday running 5 km, Wednesday yoga,
                    Friday strength training. Drink enough water and sleep 8 hours.
                    Track heart rate and fitness progress.
                    """),
            Map.entry("trip.txt", """
                    Flight to Lisbon departs at 07:40, boarding at gate 12.
                    Hotel reservation confirmed for 4 nights. Remember the passport
                    and check the luggage allowance before the vacation starts.
                    """),
            Map.entry("ideas.txt", """
                    Itinerary for the summer holiday: visit the museum, take the train
                    along the coast, book a tour and rent a car for the last two days.
                    """),
            Map.entry("snippet.java", """
                    public class Server {
                        public static void main(String[] args) {
                            // start the HTTP server and connect to the database
                            System.out.println("API is running");
                        }
                    }
                    """),
            Map.entry("setup_notes.md", """
                    Deployment checklist:
                    1. Build the docker image
                    2. Push to the registry
                    3. Deploy to the kubernetes cluster
                    4. Check the server logs for bugs
                    """),
            Map.entry("sunday.txt", """
                    Pancake recipe:
                    Ingredients: 2 eggs, 200 g flour, 300 ml milk, a pinch of salt.
                    Mix everything, let it rest, then bake in a hot pan with butter.
                    """),
            Map.entry("dinner.txt", """
                    Dinner menu for the weekend: tomato soup, roasted vegetables in the oven
                    and a chocolate dessert. Buy ingredients for the sauce and cook rice.
                    """),
            Map.entry("homework.txt", """
                    Mathematics assignment for the course: solve the exercises from chapter 4.
                    The exam is next week, the teacher will publish the lecture slides
                    for students on the university portal.
                    """),
            Map.entry("misc.txt", """
                    Remember to call grandma on Sunday and water the plants.
                    """)
    );

    /**
     * @return the names of the files that were created (existing files are skipped)
     */
    public List<String> generate(Path folder) {
        List<String> created = new ArrayList<>();
        try {
            // The target folder may not exist yet on a first run.
            Files.createDirectories(folder);
            for (Map.Entry<String, String> demoFile : DEMO_FILES) {
                String name = demoFile.getKey();
                String content = demoFile.getValue();
                Path file = folder.resolve(name);
                // Never overwrite user data: running the setup twice is harmless.
                if (Files.exists(file)) {
                    log.info("Demo file '{}' already exists, skipping", name);
                    continue;
                }
                Files.writeString(file, content);
                log.debug("Created demo file {}", file);
                created.add(name);
            }
        } catch (IOException exception) {
            // Setup problems (e.g. no write permission) stop the application with a clear message.
            throw new UncheckedIOException("Could not generate demo files in " + folder, exception);
        }
        log.info("Generated {} demo files in {}", created.size(), folder);
        return created;
    }
}
