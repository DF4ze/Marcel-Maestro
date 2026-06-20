package fr.ses10doigts.mm.starter.queue;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Implémentation in-memory de la file de tâches (ADR-015).
 *
 * <p>Basée sur {@link LinkedBlockingQueue} : thread-safe, non-bornée, <strong>non-durable</strong>.
 * Les tâches en attente sont perdues si le process crash — acceptable pour le MVP
 * mono-utilisateur. Une implémentation durable (RabbitMQ, JDBC…) remplacera ce bean
 * quand des tâches à valeur légale (facturation) entreront dans le scope.</p>
 */
@Slf4j
public class InMemoryTaskQueue implements TaskQueue {

    private final LinkedBlockingQueue<TaskMessage> queue = new LinkedBlockingQueue<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void submit(TaskMessage task) {
        if (task == null) {
            throw new IllegalArgumentException("task ne peut pas être null");
        }
        queue.add(task);
        log.info("Tâche soumise dans la file — taskId={}, type={}, assignee={}",
                task.taskId(), task.type(), task.assignee());
        log.debug("Contenu TaskMessage soumis : {}", task);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        TaskMessage task = queue.poll(timeout, unit);
        if (task != null) {
            log.info("Tâche dépilée — taskId={}, type={}, assignee={}",
                    task.taskId(), task.type(), task.assignee());
        }
        return task;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(String taskId) {
        boolean removed = queue.removeIf(t -> taskId.equals(t.taskId()));
        if (removed) {
            log.info("Tâche(s) retirée(s) de la file — taskId={}", taskId);
        }
        return removed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return queue.size();
    }
}
