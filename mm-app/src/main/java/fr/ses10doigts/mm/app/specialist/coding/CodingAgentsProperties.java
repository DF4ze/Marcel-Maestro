package fr.ses10doigts.mm.app.specialist.coding;

import java.util.EnumMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés des agents spécialistes CLI de Marcel.
 *
 * <p>Préfixe : {@code mm.agents}.</p>
 */
@ConfigurationProperties(prefix = "mm.agents")
@Getter
@Setter
public class CodingAgentsProperties {

    private CliAgentProperties claude = new CliAgentProperties();
    private CliAgentProperties codex = new CliAgentProperties();
    private Map<TaskCategory, String> routing = defaultRouting();

    private Map<TaskCategory, String> defaultRouting() {
        EnumMap<TaskCategory, String> defaults = new EnumMap<>(TaskCategory.class);
        defaults.put(TaskCategory.CODING, "claude");
        defaults.put(TaskCategory.ANALYSIS, "claude");
        defaults.put(TaskCategory.BUILD, "codex");
        return defaults;
    }

    /**
     * Paramètres d'un binaire CLI spécialiste.
     *
     * <p>Les champs d'autorisation pré-positionnent le CLI en mode non-interactif : sans eux,
     * Claude ({@code --print}) et Codex ({@code exec}) retombent sur leur politique d'approbation
     * par défaut et se bloquent (aucun humain pour répondre au prompt). Les valeurs par défaut
     * accordent l'autonomie sur les workspaces déclarés du projet.</p>
     */
    @Getter
    @Setter
    public static class CliAgentProperties {

        private String binary;
        private int timeoutMinutes;

        /**
         * Claude Code : mode de permission ({@code --permission-mode}).
         * Valeurs : {@code default}, {@code acceptEdits}, {@code plan}, {@code auto},
         * {@code dontAsk}, {@code bypassPermissions}. Défaut {@code acceptEdits} (édite sans
         * prompt) ; passer {@code bypassPermissions} pour autoriser aussi les commandes shell.
         * Laisser vide pour ne pas passer le flag.
         */
        private String permissionMode = "acceptEdits";

        /**
         * Codex : mode de sandbox ({@code --sandbox}).
         * Valeurs : {@code read-only}, {@code workspace-write}, {@code danger-full-access}.
         * Défaut {@code workspace-write}. Laisser vide pour ne pas passer le flag.
         */
        private String sandbox = "workspace-write";

        /**
         * Codex : politique d'approbation ({@code --ask-for-approval}).
         * Valeurs : {@code untrusted}, {@code on-request}, {@code never}.
         * Défaut {@code never} (indispensable en non-interactif). Laisser vide pour ne pas
         * passer le flag.
         */
        private String approvalPolicy = "never";
    }
}
