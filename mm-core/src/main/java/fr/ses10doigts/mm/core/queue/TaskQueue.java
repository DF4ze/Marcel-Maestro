package fr.ses10doigts.mm.core.queue;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import java.util.concurrent.TimeUnit;

/**
 * Port de la file de tâches inter-agents (ADR-008, ADR-015).
 *
 * <p>Toute communication entre agents transite par cette file sous forme de
 * {@link TaskMessage} typés : zéro dialogue LLM↔LLM direct. Le {@code Dispatcher}
 * consomme la file et route chaque message vers l'agent assigné.</p>
 *
 * <p>L'implémentation par défaut ({@code InMemoryTaskQueue}, starter) est
 * <strong>non-durable</strong> : les tâches en attente sont perdues si le process
 * crash. C'est acceptable pour le MVP mono-utilisateur (ADR-015). Une implémentation
 * durable (RabbitMQ, JDBC…) peut remplacer le bean via {@code @ConditionalOnMissingBean}.</p>
 */
public interface TaskQueue {

    /**
     * Soumet une tâche dans la file.
     *
     * @param task message à envoyer, jamais {@code null}
     */
    void submit(TaskMessage task);

    /**
     * Dépile le prochain message, en attendant au maximum {@code timeout}.
     *
     * @param timeout durée d'attente maximale
     * @param unit    unité de temps
     * @return le prochain {@link TaskMessage}, ou {@code null} si le délai expire
     * @throws InterruptedException si le thread est interrompu pendant l'attente
     */
    TaskMessage poll(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Retire de la file toutes les tâches correspondant à l'identifiant donné.
     * Utilisé lors d'un STOP pour nettoyer les tâches pas encore démarrées.
     *
     * @param taskId identifiant de la tâche à retirer
     * @return {@code true} si au moins un message a été retiré
     */
    boolean remove(String taskId);

    /**
     * Nombre de messages actuellement en attente dans la file.
     *
     * @return taille de la file
     */
    int size();
}
