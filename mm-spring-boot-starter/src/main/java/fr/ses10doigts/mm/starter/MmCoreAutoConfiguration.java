package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.engine.AgentLoop;
import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.LoopConfig;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration du noyau Marcel Maestro.
 *
 * <p>Étape 3 : câble les composants <em>purs et déterministes</em> de la boucle agentique
 * ({@link SystemPromptComposer}, {@link AgentResponseParser}, {@link AgentStateMachine},
 * {@link LoopConfig}). L'{@link AgentLoop} lui-même n'est créé que si un
 * {@link ChatClient} est présent dans le contexte ({@code @ConditionalOnBean}) : sans
 * provider LLM configuré (cas du smoke test étape 1), la boucle n'est pas câblée et le
 * démarrage reste vert.</p>
 *
 * <p>Aucun bean concret de LLM n'est créé ici : le {@link ChatClient} est fourni par le
 * starter de provider Spring AI choisi par l'hôte (OpenAI/OpenRouter, Ollama…).</p>
 */
@AutoConfiguration
public class MmCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CoreLoadedBanner coreLoadedBanner() {
        return new CoreLoadedBanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemPromptComposer systemPromptComposer(ObjectProvider<SystemPromptExtension> extensions) {
        List<SystemPromptExtension> ordered = extensions.orderedStream().toList();
        return new SystemPromptComposer(ordered);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentResponseParser agentResponseParser() {
        return new AgentResponseParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentStateMachine agentStateMachine() {
        return new AgentStateMachine();
    }

    @Bean
    @ConditionalOnMissingBean
    public LoopConfig loopConfig() {
        return LoopConfig.defaults();
    }

    /**
     * Câblé uniquement si un {@link ChatClient} existe (provider LLM configuré). Le
     * {@link Journal} est optionnel (implémentation {@code FileJournal} à l'étape 8).
     */
    @Bean
    @ConditionalOnBean(ChatClient.class)
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(ChatClient chatClient,
                               SystemPromptComposer promptComposer,
                               AgentResponseParser parser,
                               AgentStateMachine stateMachine,
                               LoopConfig loopConfig,
                               ObjectProvider<Journal> journal) {
        return new AgentLoop(chatClient, promptComposer, parser, stateMachine,
                loopConfig, journal.getIfAvailable());
    }
}
