package fr.ses10doigts.mm.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Câblage du {@link ChatClient} de la boucle Cortex.
 *
 * <p>Spring AI n'autoconfigure que {@link ChatClient.Builder}, pas un {@link ChatClient}
 * prêt à l'emploi. Or la boucle agentique ({@code AgentLoop}) et les spécialistes sont
 * conditionnés par la présence d'un bean {@link ChatClient}
 * ({@code @ConditionalOnBean(ChatClient.class)}). On expose donc ce bean à partir du
 * builder, mais UNIQUEMENT si un provider LLM est configuré (présence de
 * {@link ChatClient.Builder}). Sans provider, aucun {@link ChatClient} n'est créé : la
 * boucle reste non câblée et le démarrage de l'application reste vert.</p>
 */
@Configuration
@Slf4j
public class LlmConfiguration {

    /**
     * Construit le {@link ChatClient} à partir du builder autoconfiguré par le provider
     * LLM (OpenAI / OpenRouter).
     *
     * @param builder builder fourni par l'autoconfiguration Spring AI
     * @return le {@link ChatClient} injecté dans la boucle Cortex
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatClient.Builder.class)
    public ChatClient chatClient(ChatClient.Builder builder) {
        log.info("Câblage du ChatClient Cortex à partir du provider LLM configuré");
        return builder.build();
    }
}
