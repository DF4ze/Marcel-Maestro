package fr.ses10doigts.mm.app.specialist;

import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.journal.Journal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
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
     * Spécialiste de démo — prouve le cycle complet de l'orchestrateur.
     * Câblé uniquement si un {@link ChatClient} est présent.
     */
    @Bean
    @ConditionalOnBean(ChatClient.class)
    public EchoSpecialist echoSpecialist(ChatClient chatClient,
                                          AgentResponseParser parser,
                                          AgentStateMachine stateMachine,
                                          ObjectProvider<Journal> journal) {
        log.info("EchoSpecialist enregistré comme spécialiste de démo");
        return new EchoSpecialist(chatClient, parser, stateMachine, journal.getIfAvailable());
    }
}
