package fr.ses10doigts.mm.core.orchestration;

import fr.ses10doigts.mm.core.engine.StopSignal;

/**
 * Poignée d'un agent en cours d'exécution, maintenue par le {@link Dispatcher}.
 *
 * <p>Associe un identifiant de tâche à son {@link StopSignal} pour permettre
 * l'arrêt coopératif de bout en bout.</p>
 *
 * @param taskId identifiant de la tâche
 * @param stop   signal d'arrêt coopératif
 */
public record DispatcherHandle(String taskId, StopSignal stop) {
}
