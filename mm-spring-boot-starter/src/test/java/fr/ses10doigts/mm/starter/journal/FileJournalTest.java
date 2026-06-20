package fr.ses10doigts.mm.starter.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.journal.JournalEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires pour {@link FileJournal}.
 */
class FileJournalTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private FileJournal journal;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        journal = new FileJournal(objectMapper, tempDir);
    }

    @Test
    void appendCreatesFileAndWritesJsonl() throws Exception {
        Instant now = Instant.parse("2025-03-15T10:30:00Z");
        JournalEntry entry = new JournalEntry(
                now, "agent-1", "task-42", "decision",
                Map.of("action", "deploy", "target", "prod"));

        journal.append(entry);

        Path expectedFile = tempDir.resolve("agent-1/2025-03-15.jsonl");
        assertThat(expectedFile).exists();

        String line = Files.readString(expectedFile).trim();
        assertThat(line).isNotEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(line, Map.class);
        assertThat(parsed).containsEntry("agentId", "agent-1");
        assertThat(parsed).containsEntry("taskId", "task-42");
        assertThat(parsed).containsEntry("category", "decision");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        assertThat(data).containsEntry("action", "deploy");
        assertThat(data).containsEntry("target", "prod");
    }

    @Test
    void appendMultipleEntriesSameFile() throws Exception {
        Instant now = Instant.parse("2025-03-15T10:30:00Z");
        JournalEntry entry1 = new JournalEntry(
                now, "agent-1", "task-1", "decision", Map.of("step", 1));
        JournalEntry entry2 = new JournalEntry(
                now.plusSeconds(60), "agent-1", "task-1", "tool_call", Map.of("step", 2));

        journal.append(entry1);
        journal.append(entry2);

        Path file = tempDir.resolve("agent-1/2025-03-15.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
    }

    @Test
    void appendSanitizesSecrets() throws Exception {
        Instant now = Instant.parse("2025-03-15T10:30:00Z");
        JournalEntry entry = new JournalEntry(
                now, "agent-1", "task-1", "config",
                Map.of("token", "abc123", "password", "s3cret", "apiKey", "key-xyz", "safe", "ok"));

        journal.append(entry);

        String content = Files.readString(tempDir.resolve("agent-1/2025-03-15.jsonl")).trim();
        assertThat(content).contains("***REDACTED***");
        assertThat(content).doesNotContain("abc123");
        assertThat(content).doesNotContain("s3cret");
        assertThat(content).doesNotContain("key-xyz");
        assertThat(content).contains("\"safe\":\"ok\"");
    }

    @Test
    void appendSanitizesNestedSecrets() throws Exception {
        Instant now = Instant.parse("2025-03-15T10:30:00Z");
        Map<String, Object> nestedData = Map.of(
                "config", Map.of(
                        "host", "localhost",
                        "private_key", "-----BEGIN RSA-----",
                        "credential", "mysecret"),
                "visible", "hello");

        JournalEntry entry = new JournalEntry(now, "agent-1", "task-1", "config", nestedData);

        journal.append(entry);

        String content = Files.readString(tempDir.resolve("agent-1/2025-03-15.jsonl")).trim();
        assertThat(content).doesNotContain("-----BEGIN RSA-----");
        assertThat(content).doesNotContain("mysecret");
        assertThat(content).contains("\"visible\":\"hello\"");
        assertThat(content).contains("\"host\":\"localhost\"");
    }

    @Test
    void appendDifferentAgentsSeparateFiles() throws Exception {
        Instant now = Instant.parse("2025-03-15T10:30:00Z");
        JournalEntry e1 = new JournalEntry(now, "alpha", "t1", "cat", Map.of("k", "v1"));
        JournalEntry e2 = new JournalEntry(now, "beta", "t2", "cat", Map.of("k", "v2"));

        journal.append(e1);
        journal.append(e2);

        Path fileAlpha = tempDir.resolve("alpha/2025-03-15.jsonl");
        Path fileBeta = tempDir.resolve("beta/2025-03-15.jsonl");
        assertThat(fileAlpha).exists();
        assertThat(fileBeta).exists();

        assertThat(Files.readAllLines(fileAlpha)).hasSize(1);
        assertThat(Files.readAllLines(fileBeta)).hasSize(1);
    }

    @Test
    void concurrentAppendsSafe() throws Exception {
        int threadCount = 10;
        int entriesPerThread = 100;
        Instant now = Instant.parse("2025-03-15T10:30:00Z");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadIdx = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < entriesPerThread; i++) {
                        JournalEntry entry = new JournalEntry(
                                now, "concurrent-agent", "task-" + threadIdx,
                                "cat", Map.of("thread", threadIdx, "idx", i));
                        journal.append(entry);
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(errors).isEmpty();

        Path file = tempDir.resolve("concurrent-agent/2025-03-15.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(threadCount * entriesPerThread);
    }
}
