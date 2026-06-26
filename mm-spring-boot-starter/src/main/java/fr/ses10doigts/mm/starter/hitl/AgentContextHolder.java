package fr.ses10doigts.mm.starter.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Propagateur de contexte d'exécution par thread (E2-M4).
 *
 * <p>Stocke l'{@link AgentContext} courant dans un {@link ThreadLocal} afin de le rendre
 * accessible à {@link PersistentConsentCache#record} sans modifier la signature héritée
 * de {@code ConsentCache} (mm-core immuable, ADR-003).</p>
 *
 * <p>Cycle de vie d'un outil :</p>
 * <ol>
 *   <li>{@link ContextPropagatingToolExecutionGuard#execute} appelle {@link #bind}</li>
 *   <li>La chaîne {@code HitlGuard → cache.record()} s'exécute dans le même thread</li>
 *   <li>{@link ContextPropagatingToolExecutionGuard#execute} appelle {@link #clear} en finally</li>
 * </ol>
 *
 * <p>Thread-safe : chaque thread (virtual thread Java 21) possède son propre slot.</p>
 */
@Component
@Slf4j
public class AgentContextHolder {

    private static final ThreadLocal<AgentContext> HOLDER = new ThreadLocal<>();

    /**
     * Lie l'{@link AgentContext} au thread courant.
     *
     * @param ctx contexte à lier ; peut être {@code null} (dépendance effacée)
     */
    public void bind(AgentContext ctx) {
        HOLDER.set(ctx);
        log.debug("AgentContextHolder.bind — projectId={}",
                ctx != null ? ctx.projectId() : "null");
    }

    /**
     * Retourne le contexte lié au thread courant.
     *
     * @return le contexte, ou {@code null} si aucun n'est lié
     */
    public AgentContext get() {
        return HOLDER.get();
    }

    /**
     * Retourne le {@code projectId} du contexte courant.
     *
     * @return le projectId, ou {@code null} si aucun contexte n'est lié
     */
    public String projectId() {
        AgentContext ctx = HOLDER.get();
        return ctx != null ? ctx.projectId() : null;
    }

    /**
     * Libère le contexte lié au thread courant. À appeler en {@code finally}.
     */
    public void clear() {
        HOLDER.remove();
        log.debug("AgentContextHolder.clear");
    }
}
