package fr.ses10doigts.mm.app.config;

import fr.ses10doigts.mm.starter.MmCoreAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

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
 *
 * <p><strong>Pourquoi une {@code @AutoConfiguration} et non un {@code @Configuration}
 * ordinaire ?</strong><br>
 * Une {@code @Configuration} scannée est évaluée <em>avant</em> les auto-configurations.
 * {@code @ConditionalOnBean(ChatClient.Builder.class)} renverrait donc toujours
 * {@code false}, car le {@link ChatClient.Builder} de Spring AI n'est pas encore
 * enregistré au moment de l'évaluation — et toute la chaîne (AgentLoop → CortexFactory →
 * Dispatcher) s'effondrerait. En passant par {@code @AutoConfiguration} ordonnée
 * <em>après</em> {@code ChatClientAutoConfiguration} (Spring AI) et <em>avant</em>
 * {@link MmCoreAutoConfiguration}, la condition est évaluée une fois le builder présent.
 * (Même correctif que {@code AppAgentsAutoConfiguration}.)</p>
 */
@AutoConfiguration(
        afterName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
        before = MmCoreAutoConfiguration.class)
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
