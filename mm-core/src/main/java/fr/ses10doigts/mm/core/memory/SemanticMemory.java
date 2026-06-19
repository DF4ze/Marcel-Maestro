package fr.ses10doigts.mm.core.memory;

import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.List;

/**
 * Port de mémoire procédurale / sémantique — <strong>couture laissée VIDE</strong>.
 *
 * <p>Aucune implémentation n'est prévue en V1 (apprentissage différé). Définie ici
 * uniquement pour figer le point de branchement futur (error-triggered retrieval,
 * ADR-011, interrogée seulement à la transition {@code TROUBLE}).</p>
 */
public interface SemanticMemory {

    void store(SemanticEntry entry);

    List<SemanticEntry> search(String query, int topK, AgentContext ctx);

    /** Renforce le score de fraîcheur d'une entrée — reporté après MVP. */
    void reinforce(String entryId);
}
