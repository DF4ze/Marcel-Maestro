package fr.ses10doigts.mm.starter.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de la file de tâches in-memory (étape 7, livrable 1).
 */
class InMemoryTaskQueueTest {

    private final InMemoryTaskQueue queue = new InMemoryTaskQueue();

    private TaskMessage task(String id) {
        return new TaskMessage(id, TaskType.USER_REQUEST, "cortex", "contenu",
                AgentContext.of("default", "p1", "c1", id));
    }

    @Test
    void submitEtPoll() throws InterruptedException {
        queue.submit(task("t1"));
        assertEquals(1, queue.size());

        TaskMessage polled = queue.poll(1, TimeUnit.SECONDS);
        assertEquals("t1", polled.taskId());
        assertEquals(0, queue.size());
    }

    @Test
    void pollTimeoutRetourneNull() throws InterruptedException {
        TaskMessage polled = queue.poll(50, TimeUnit.MILLISECONDS);
        assertNull(polled);
    }

    @Test
    void removeRetireLesBonnesEntrees() throws InterruptedException {
        queue.submit(task("t1"));
        queue.submit(task("t2"));
        queue.submit(task("t1")); // doublon t1

        assertTrue(queue.remove("t1"));
        assertEquals(1, queue.size());

        TaskMessage remaining = queue.poll(1, TimeUnit.SECONDS);
        assertEquals("t2", remaining.taskId());
    }

    @Test
    void removeRetourneFalsiSiAbsent() {
        assertFalse(queue.remove("inexistant"));
    }

    @Test
    void submitNullLeveException() {
        assertThrows(IllegalArgumentException.class, () -> queue.submit(null));
    }

    @Test
    void ordreFifo() throws InterruptedException {
        queue.submit(task("t1"));
        queue.submit(task("t2"));
        queue.submit(task("t3"));

        assertEquals("t1", queue.poll(1, TimeUnit.SECONDS).taskId());
        assertEquals("t2", queue.poll(1, TimeUnit.SECONDS).taskId());
        assertEquals("t3", queue.poll(1, TimeUnit.SECONDS).taskId());
    }
}
