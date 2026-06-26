package fr.ses10doigts.mm.starter.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.ToolExecutionGuard;
import fr.ses10doigts.mm.core.tool.ToolResult;
import fr.ses10doigts.mm.core.tool.WorkspaceRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Guard d'exécution d'outil qui propage l'{@link AgentContext} vers le
 * {@link AgentContextHolder} avant chaque appel (E2-M4).
 *
 * <p>Étend {@link ToolExecutionGuard} (mm-core) sans le modifier. Surcharge
 * {@link #execute} pour :</p>
 * <ol>
 *   <li>Lier le contexte courant au thread via {@link AgentContextHolder#bind} — permet
 *       à {@link PersistentConsentCache#record} de valider le {@code projectId}.</li>
 *   <li>Déclencher le rechargement lazy des consentements persistés pour le projet
 *       courant ({ PersistentConsentCache#loadForProjectIfNeeded}) — couvre les
 *       reprises de conversation après redémarrage.</li>
 *   <li>Libérer le contexte en {@code finally}.</li>
 * </ol>
 *
 * <p>Instancié par {@code MmCoreAutoConfiguration} à la place du guard de base.</p>
 */
@Slf4j
public class ContextPropagatingToolExecutionGuard extends ToolExecutionGuard {

    private final AgentContextHolder contextHolder;
    private final PersistentConsentCache consentCache;

    /** Projets dont les consentements ont déjà été rechargés dans cette JVM. */
    private final Set<String> loadedProjects = ConcurrentHashMap.newKeySet();

    /**
     * Constructeur complet.
     *
     * <p>Note : {@code @RequiredArgsConstructor} ne peut pas être utilisé ici car le
     * constructeur doit déléguer explicitement à
     * {@code super(HitlGuard, PathValidator, WorkspaceRegistry)} — Lombok ne sait pas
     * générer d'appel {@code super()} avec arguments.</p>
     *
     * @param hitlGuard         garde HITL ; peut être {@code null}
     * @param pathValidator     validateur de chemins ; peut être {@code null}
     * @param workspaceRegistry registre des dossiers externes ; peut être {@code null}
     * @param contextHolder     propagateur de contexte par thread
     * @param consentCache      cache de consentements persistés
     */
    public ContextPropagatingToolExecutionGuard(HitlGuard hitlGuard,
                                                PathValidator pathValidator,
                                                WorkspaceRegistry workspaceRegistry,
                                                AgentContextHolder contextHolder,
                                                PersistentConsentCache consentCache) {
        super(hitlGuard, pathValidator, workspaceRegistry);
        this.contextHolder = contextHolder;
        this.consentCache = consentCache;
    }

    /**
     * Exécute l'outil après avoir lié le contexte et rechargé les consentements si besoin.
     *
     * @param tool   outil à exécuter
     * @param params paramètres désérialisés
     * @param ctx    contexte d'exécution courant
     * @return résultat de l'exécution
     */
    @Override
    public ToolResult execute(AgentTool tool, Map<String, Object> params, AgentContext ctx) {
        contextHolder.bind(ctx);
        log.debug("ContextPropagatingToolExecutionGuard — contexte lié, projectId={}", ctx.projectId());

        loadForProjectIfNeeded(ctx.projectId());

        try {
            return super.execute(tool, params, ctx);
        } finally {
            contextHolder.clear();
        }
    }

    /**
     * Charge les consentements du projet depuis la DB la première fois qu'un outil
     * s'exécute pour ce projet dans la JVM courante.
     *
     * <p>Idempotent : un projet déjà chargé n'est pas rechargé. Couvre les reprises
     * de conversation après redémarrage.</p>
     *
     * @param projectId identifiant du projet ; ignoré si {@code null}
     */
    private void loadForProjectIfNeeded(String projectId) {
        if (projectId != null && !loadedProjects.contains(projectId)) {
            consentCache.loadFromStore(projectId);
            loadedProjects.add(projectId);
            log.info("Consentements rechargés pour le projet '{}' (démarrage de conversation)",
                    projectId);
        }
    }
}
