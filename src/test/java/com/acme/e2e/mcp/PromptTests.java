package com.acme.e2e.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PromptTests {
    private PromptExecutor executor;

    @BeforeAll
    void setup() {
        // Load secrets.properties into system properties once for all tests
        try {
            String envFile = System.getProperty("envfile", "secrets.properties");
            Path envPath = Paths.get(envFile);
            if (Files.exists(envPath)) {
                Properties props = new Properties();
                try (var in = Files.newInputStream(envPath)) {
                    props.load(in);
                }
                props.forEach((k, v) -> System.setProperty(String.valueOf(k), String.valueOf(v)));
            }
        } catch (Exception ignored) {
            // proceed without secrets if not available
        }
        
        // Setup MCP executor (MCP server manages its own browser)
        executor = new PromptExecutorMcp();
    }

    @ParameterizedTest(name = "prompt: {0}")
    @MethodSource("prompts")
    void executesPromptAndReports(String prompt) {
        long budget = Long.getLong("latency.budget.ms", 300_000L); // 5 minutes for complex workflows
        var result = executor.runPrompt(prompt, Duration.ofMillis(budget));
        assertAll(
            () -> assertTrue(result.success, () -> "prompt failed: " + prompt + ", elapsedMs=" + result.elapsedMs),
            () -> assertNotNull(result.text, "no text returned"),
            () -> assertFalse(result.text.isBlank(), "blank result text"),
            () -> assertTrue(result.elapsedMs <= budget, () -> "elapsed=" + result.elapsedMs + " > budget=" + budget)
        );
    }

    static Stream<String> prompts() {
        try {
            Path path = Paths.get("src", "test", "resources", "prompts.csv");
            var lines = Files.readAllLines(path)
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
            if (lines.isEmpty()) {
                return Stream.empty();
            }
            String joined = String.join("\n", lines);
            return Stream.of(joined);
        } catch (Exception e) {
            return Stream.of(
                    "Summarize page",
                    "List headings"
            );
        }
    }
}


