package fr.ses10doigts.mm.core.tool;

import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

/**
 * Registre central des outils declares (etape 6).
 *
 * <p>Collecte les {@link AgentTool} enregistres par l'hote et les resout en
 * {@link ToolCallback} Spring AI a la demande du moteur. Chaque resolution
 * capture le {@link AgentContext} et le {@link ToolExecutionGuard} pour que
 * le callback soit pret a etre passe au {@code ChatClient}.</p>
 *
 * <p>Immuable apres construction. Thread-safe.</p>
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    /**
     * Construit le registre a partir d'une liste d'outils.
     *
     * <p>Les doublons de nom sont rejetes avec une {@link IllegalArgumentException}.</p>
     *
     * @param tools outils a enregistrer
     * @throws IllegalArgumentException si deux outils portent le meme nom
     */
    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            AgentTool previous = map.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "doublon de nom d'outil : '" + tool.name() + "'");
            }
        }
        this.tools = Collections.unmodifiableMap(map);
        log.info("ToolRegistry initialise avec {} outils : {}", this.tools.size(), this.tools.keySet());
    }

    /**
     * Resout une liste d'outils autorises en {@link ToolCallback} Spring AI.
     *
     * <p>Les noms inconnus sont ignores avec un avertissement de log.</p>
     *
     * @param allowedToolNames noms des outils autorises pour cet agent
     * @param ctx              contexte d'execution capture dans les callbacks
     * @param guard            garde d'execution transverse
     * @return liste de {@link ToolCallback} prets a etre passes au {@code ChatClient}
     */
    public List<ToolCallback> resolve(List<String> allowedToolNames, AgentContext ctx,
                                      ToolExecutionGuard guard) {
        log.info("Resolution de {} outils pour le contexte {}", allowedToolNames.size(), ctx.taskId());
        log.debug("Outils demandes : {}", allowedToolNames);

        List<ToolCallback> resolved = allowedToolNames.stream()
                .map(name -> {
                    AgentTool tool = tools.get(name);
                    if (tool == null) {
                        log.warn("Outil demande '{}' introuvable dans le registre", name);
                        return null;
                    }
                    return AgentToolConverter.toCallback(tool, ctx, guard);
                })
                .filter(cb -> cb != null)
                .collect(Collectors.toList());

        log.info("{} outils resolus sur {} demandes", resolved.size(), allowedToolNames.size());
        return resolved;
    }

    /**
     * Recupere un outil par son nom (pour execution directe dans la boucle).
     *
     * @param name nom de l'outil
     * @return l'outil s'il existe
     */
    public Optional<AgentTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Retourne le nombre d'outils enregistres.
     *
     * @return nombre d'outils
     */
    public int size() {
        return tools.size();
    }
}
