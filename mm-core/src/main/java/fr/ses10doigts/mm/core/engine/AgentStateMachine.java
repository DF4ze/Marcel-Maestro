package fr.ses10doigts.mm.core.engine;

import fr.ses10doigts.mm.core.agent.AgentStatus;

/**
 * Machine à états : route un {@link AgentStatus} vers un {@link Routing} (étape 3,
 * livrable 4 ; ADR-006). « La sortie JSON structurée EST la machine à états. »
 *
 * <p>Le {@code switch} est une <strong>expression exhaustive</strong> sur l'enum : le
 * compilateur garantit qu'aucun statut n'est oublié (pas de {@code default}). Ajouter un
 * statut à {@link AgentStatus} cassera la compilation tant que ce routage n'est pas mis
 * à jour — c'est voulu.</p>
 *
 * <p>Choix V1 (roadmap §3 étape 3) :</p>
 * <ul>
 *   <li>{@code trouble} = retry simple sur prompt renforcé. La recherche en mémoire sur
 *       erreur (error-triggered retrieval, ADR-011) reste une couture ouverte, non
 *       implémentée ici.</li>
 *   <li>{@code blocked} = attente humaine ; le HITL est l'étape 4. La boucle s'arrête
 *       proprement.</li>
 *   <li>{@code pending} = état initial ; s'il revient du LLM, on le traite comme une
 *       continuation (défensif).</li>
 * </ul>
 *
 * <p>Pur et sans état : sûr à partager comme singleton.</p>
 */
public final class AgentStateMachine {

    /**
     * @param status statut renvoyé par le LLM (jamais {@code null})
     * @return le verdict de routage correspondant
     * @throws NullPointerException si {@code status} est {@code null}
     */
    public Routing route(AgentStatus status) {
        if (status == null) {
            throw new NullPointerException("status");
        }
        return switch (status) {
            case RUNNING, PENDING -> Routing.CONTINUE;
            case DONE -> Routing.TERMINATE_DONE;
            case BLOCKED -> Routing.AWAIT_HUMAN;
            case TROUBLE -> Routing.RETRY_REINFORCED;
            case KO -> Routing.TERMINATE_KO;
        };
    }
}
