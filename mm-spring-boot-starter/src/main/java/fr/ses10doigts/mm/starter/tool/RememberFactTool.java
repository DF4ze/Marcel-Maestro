package fr.ses10doigts.mm.starter.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.core.tool.ToolResult;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outil « retiens ceci » — capture explicite de faits par l'agent (étape 5, livrable 4).
 *
 * <p>Permet au LLM de persister un fait dans le {@link MemoryStore} de façon explicite :
 * l'agent décide consciemment de retenir une information utile pour le futur. Pas de
 * capture implicite ni de bus d'événements (différé).</p>
 *
 * <p>Paramètres JSON :</p>
 * <ul>
 *   <li>{@code key} (requis) — clé du fait, unique par tenant</li>
 *   <li>{@code value} (requis) — valeur textuelle du fait</li>
 *   <li>{@code scope} (optionnel, défaut {@code "global"}) — portée du fait</li>
 * </ul>
 *
 * <p>{@code riskLevel = LOW} : retenir un fait est une opération bénigne, pas de
 * consentement requis.</p>
 *
 * <p>Prêt pour le registre d'outils de l'étape 6 — non encore câblé dans la boucle.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RememberFactTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemoryStore memoryStore;

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "remember_fact";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Retiens un fait important pour le futur. Utilise cet outil quand une "
                + "information mérite d'être conservée au-delà de la conversation courante "
                + "(préférence utilisateur, décision projet, résultat d'analyse…).";
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode key = properties.putObject("key");
        key.put("type", "string");
        key.put("description", "Clé unique du fait (ex: 'user:preferred_language')");

        ObjectNode value = properties.putObject("value");
        value.put("type", "string");
        value.put("description", "Valeur textuelle du fait à retenir");

        ObjectNode scope = properties.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "Portée : 'global', 'project:<id>', 'session:<id>'");
        scope.put("default", "global");

        schema.putArray("required").add("key").add("value");

        return schema;
    }

    /** {@inheritDoc} */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    /**
     * Persiste le fait dans le MemoryStore.
     *
     * @param params paramètres validés ({@code key}, {@code value}, {@code scope} optionnel)
     * @param ctx    contexte d'exécution courant
     * @return résultat de succès avec la clé persistée
     * @throws ToolException si les paramètres requis sont absents
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String key = (String) params.get("key");
        String value = (String) params.get("value");
        String scope = (String) params.getOrDefault("scope", "global");

        if (key == null || key.isBlank()) {
            throw new ToolException("Paramètre 'key' requis et non vide");
        }
        if (value == null || value.isBlank()) {
            throw new ToolException("Paramètre 'value' requis et non vide");
        }

        log.info("remember_fact : clé='{}', scope='{}', tenant='{}'", key, scope, ctx.tenant());

        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry(key, value, scope, ctx.tenant(), now, now);
        memoryStore.put(entry);

        log.debug("Fait retenu avec succès : clé='{}'", key);
        return ToolResult.ok("Fait retenu : " + key);
    }
}
