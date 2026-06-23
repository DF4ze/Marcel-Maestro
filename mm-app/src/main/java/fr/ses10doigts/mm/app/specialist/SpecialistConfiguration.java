package fr.ses10doigts.mm.app.specialist;

import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.journal.Journal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/**
 * Déclaration des spécialistes disponibles dans mm-app (étape 7).
 *
 * <p>Chaque spécialiste est un bean {@link fr.ses10doigts.mm.core.orchestration.AgentFactory}
 * découvert par le Dispatcher via injection Spring.</p>
 */
@Configuration
@Slf4j
public class SpecialistConfiguration {

    /**
     * EchoSpecialist déplacé dans {@code AppAgentsAutoConfiguration} pour corriger
     * le problème d'ordre d'évaluation de {@code @ConditionalOnBean} avec les
     * auto-configurations Spring AI (ChatClient non encore enregistré au moment
     * du parsing des {@code @Configuration} ordinaires).
     *
     * <p>Cette méthode est conservée en tant que documentation de la migration.</p>
     *
     * @deprecated Remplacé par {@code AppAgentsAutoConfiguration.echoSpecialist()}
     */
    @Deprecated(since = "étape-8", forRemoval = true)
    // @Bean retiré intentionnellement — voir AppAgentsAutoConfiguration
    public EchoSpecialist echoSpecialist_deprecated(ChatClient chatClient,
                                                     AgentResponseParser parser,
                                                     AgentStateMachine stateMachine,
                                                     ObjectProvider<Journal> journal) {
        return new EchoSpecialist(chatClient, parser, stateMachine, journal.getIfAvailable());
    }
}
