package fr.ses10doigts.mm.core.agent;

/**
 * Contexte d'exécution propagé à travers tout le moteur.
 *
 * <p>Immuable. Porte l'identité multi-tenant dès J1 (ADR-013) sans en implémenter
 * l'isolation : {@code tenant} vaut {@code "default"} en MVP.</p>
 *
 * <p><strong>Report étape 3</strong> : la couture {@code idempotencyKey} (PB-07)
 * sera ajoutée quand la boucle agentique existera. Tenu volontairement à 4 champs
 * (architecture cible §2.6).</p>
 *
 * @param tenant         identifiant artisan ; {@code "default"} en MVP
 * @param projectId      identifiant du projet
 * @param conversationId UUID de la conversation courante
 * @param taskId         UUID de la tâche en cours
 */
public record AgentContext(
        String tenant,
        String projectId,
        String conversationId,
        String taskId) {
}
