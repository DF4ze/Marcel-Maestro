package fr.ses10doigts.mm.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.Map;

/**
 * Contrat d'un outil enfichable (ADR-004).
 *
 * <p>Une opération distincte et séparément autorisable. L'hôte implémente cette
 * interface ; le moteur l'adapte vers {@code FunctionCallback} de Spring AI (étape 6).
 * Aucun code dynamique construit par le LLM ne doit être exécuté : les paramètres sont
 * des données, pas des instructions (PB-10).</p>
 */
public interface AgentTool {

    /** Identifiant unique, snake_case. */
    String name();

    /** Description lue par le LLM pour décider d'appeler l'outil. */
    String description();

    /** JSON Schema des paramètres — consommé par Spring AI. */
    JsonNode inputSchema();

    /** Niveau de risque — pilote le HITL. */
    RiskLevel riskLevel();

    /**
     * Exécute l'outil de façon synchrone.
     *
     * @param params paramètres validés par rapport au {@link #inputSchema()}
     * @param ctx    contexte d'exécution courant
     * @return résultat de l'exécution
     * @throws ToolException en cas d'échec d'exécution
     */
    ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException;

    /**
     * Durée maximale d'exécution de l'outil en millisecondes.
     *
     * <p>Au-delà, le {@code ToolExecutionGuard} interrompt l'exécution et retourne
     * un {@link ToolResult#fail(String)}. Valeur par défaut : 30 secondes.</p>
     *
     * @return timeout en millisecondes
     */
    default long maxExecutionTimeMs() {
        return 30_000L;
    }
}
