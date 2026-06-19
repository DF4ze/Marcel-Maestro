package fr.ses10doigts.mm.core.engine;

/**
 * Verdict de routage produit par {@link AgentStateMachine} pour un {@link
 * fr.ses10doigts.mm.core.agent.AgentStatus} donné (étape 3, livrable 4).
 *
 * <p>Pur : décrit <em>quoi faire</em> sans effet de bord. C'est {@link AgentLoop} qui
 * applique le verdict (terminer, itérer, relancer).</p>
 */
public enum Routing {

    /** Continuer la boucle : nouvelle itération (statut {@code running} / {@code pending}). */
    CONTINUE,
    /** Terminer en succès (statut {@code done}). */
    TERMINATE_DONE,
    /** Terminer en échec définitif (statut {@code KO}). */
    TERMINATE_KO,
    /** Relancer sur prompt renforcé (statut {@code trouble} ou échec de parsing). */
    RETRY_REINFORCED,
    /**
     * Attendre une intervention humaine (statut {@code blocked}). En V1, le HITL n'est
     * pas implémenté (étape 4) : la boucle s'arrête proprement et rapporte l'attente.
     */
    AWAIT_HUMAN
}
