package fr.ses10doigts.mm.core.memory;

import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.List;
import java.util.Optional;

/**
 * Port de mémoire factuelle (ADR-009).
 *
 * <p>Nom retenu : {@code MemoryStore} (plus large que « FactStore », alias conceptuel).
 * Implémenté à l'étape 5 ({@code JpaMemoryStore} → SQLite). Sert aussi à persister les
 * consentements HITL {@code ALLOW_PROJECT}/{@code ALLOW_ALWAYS}.</p>
 */
public interface MemoryStore {

    void put(MemoryEntry entry);

    Optional<MemoryEntry> get(String key, AgentContext ctx);

    List<MemoryEntry> findByScope(String scope, AgentContext ctx);

    void delete(String key, AgentContext ctx);
}
