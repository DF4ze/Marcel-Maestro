package fr.ses10doigts.mm.core.agent;

/**
 * Contexte d'exécution propagé à travers tout le moteur.
 *
 * <p>Immuable. Porte l'identité multi-tenant dès J1 (ADR-013) sans en implémenter
 * l'isolation : {@code tenant} vaut {@code "default"} en MVP.</p>
 *
 * <p><strong>Étape 6</strong> : ajout de {@code idempotencyKey} (PB-07) pour le
 * dédoublonnage des exécutions d'outils. {@code null} quand hors contexte d'exécution
 * d'outil.</p>
 *
 * @param tenant          identifiant artisan ; {@code "default"} en MVP
 * @param projectId       identifiant du projet
 * @param conversationId  UUID de la conversation courante
 * @param taskId          UUID de la tâche en cours
 * @param idempotencyKey  clé d'idempotence pour le dédoublonnage d'exécution d'outil ;
 *                        {@code null} quand hors contexte d'exécution d'outil
 */
public record AgentContext(
        String tenant,
        String projectId,
        String conversationId,
        String taskId,
        String idempotencyKey) {

    /**
     * Fabrique rétro-compatible sans clé d'idempotence.
     *
     * @param tenant         identifiant artisan
     * @param projectId      identifiant du projet
     * @param conversationId UUID de la conversation
     * @param taskId         UUID de la tâche
     * @return un contexte avec {@code idempotencyKey} à {@code null}
     */
    public static AgentContext of(String tenant, String projectId,
                                  String conversationId, String taskId) {
        return new AgentContext(tenant, projectId, conversationId, taskId, null);
    }
}
