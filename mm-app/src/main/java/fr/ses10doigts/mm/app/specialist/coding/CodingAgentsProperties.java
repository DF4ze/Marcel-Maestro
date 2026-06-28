package fr.ses10doigts.mm.app.specialist.coding;

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

    /**
     * Paramètres d'un binaire CLI spécialiste.
     */
    @Getter
    @Setter
    public static class CliAgentProperties {

        private String binary;
        private int timeoutMinutes;
    }
}
