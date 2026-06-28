package fr.ses10doigts.mm.app.config;

import fr.ses10doigts.mm.app.specialist.CortexFactory;
import fr.ses10doigts.mm.app.specialist.EchoSpecialist;
import fr.ses10doigts.mm.app.specialist.coding.ClaudeAgentFactoryAdapter;
import fr.ses10doigts.mm.app.specialist.coding.ClaudeCodeAgent;
import fr.ses10doigts.mm.app.specialist.coding.CodexAgent;
import fr.ses10doigts.mm.app.specialist.coding.CodexAgentFactoryAdapter;
import fr.ses10doigts.mm.app.specialist.coding.CodingAgentOutcomeMapper;
import fr.ses10doigts.mm.app.specialist.coding.CodingAgentsProperties;
import fr.ses10doigts.mm.app.specialist.coding.CodingRoutingPromptExtension;
import fr.ses10doigts.mm.app.specialist.coding.TaskMessageCodingMissionMapper;
import fr.ses10doigts.mm.core.engine.AgentLoop;
import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.starter.MmCoreAutoConfiguration;
import fr.ses10doigts.mm.starter.hitl.CompositeHumanInteraction;
import fr.ses10doigts.mm.starter.orchestration.DispatcherAutoConfiguration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * Autoconfiguration des agents de mm-app.
 *
 * <p>Enregistre le {@link CortexFactory} (orchestrateur principal), l'{@link EchoSpecialist}
 * (spécialiste de démo) et les adapters Claude/Codex branchés sur le Dispatcher historique
 * comme beans {@code AgentFactory}. Déclarée après {@link MmCoreAutoConfiguration} via
 * {@link AutoConfigureAfter} pour garantir que l'{@link AgentLoop} est disponible au moment
 * de l'évaluation des conditions.</p>
 *
 * <p><strong>Pourquoi une {@code @AutoConfiguration} et non un {@code @Configuration}
 * ordinaire ?</strong><br>
 * Une {@code @Configuration} ordinaire est parsée <em>avant</em> les auto-configurations.
 * {@code @ConditionalOnBean(ChatClient.class)} évalue donc toujours {@code false} si le
 * bean vient de Spring AI. En passant par {@code @AutoConfiguration}, la condition est
 * évaluée après que tous les beans auto-configurés sont enregistrés.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(MmCoreAutoConfiguration.class)
@AutoConfigureBefore(DispatcherAutoConfiguration.class)
@ConditionalOnBean(AgentLoop.class)
@Slf4j
public class AppAgentsAutoConfiguration {

    private final ObjectProvider<Dispatcher> dispatcherProvider;
    private final ObjectProvider<AgentLoop> agentLoopProvider;
    private final ObjectProvider<CompositeHumanInteraction> compositeProvider;

    /**
     * Construit l'autoconfiguration avec les providers de diagnostic.
     *
     * @param dispatcherProvider provider du Dispatcher
     * @param agentLoopProvider provider de l'AgentLoop
     * @param compositeProvider provider du CompositeHumanInteraction
     */
    public AppAgentsAutoConfiguration(ObjectProvider<Dispatcher> dispatcherProvider,
                                      ObjectProvider<AgentLoop> agentLoopProvider,
                                      ObjectProvider<CompositeHumanInteraction> compositeProvider) {
        this.dispatcherProvider = dispatcherProvider;
        this.agentLoopProvider = agentLoopProvider;
        this.compositeProvider = compositeProvider;
    }

    /**
     * Émet un rapport de câblage synthétique une fois l'application prête.
     *
     * @param event événement de démarrage Spring Boot
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("=== Marcel-Maestro - rapport de cablage ===");
        log.info("  AgentLoop   : {}", agentLoopProvider.getIfAvailable() != null ? "present" : "ABSENT");
        log.info("  Dispatcher  : {}", dispatcherProvider.getIfAvailable() != null ? "present" : "ABSENT");
        CompositeHumanInteraction composite = compositeProvider.getIfAvailable();
        if (composite != null) {
            List<HumanInteraction> channels = composite.getChannels();
            log.info("  HITL canaux : {} canal/aux enregistre/s", channels.size());
            channels.forEach(ch -> log.info("    -> {}", ch.getClass().getSimpleName()));
        } else {
            log.warn("  HITL canaux : CompositeHumanInteraction ABSENT");
        }
        log.info("========================================");
    }

    /**
     * Enregistre le Cortex comme agent principal de Marcel Maestro.
     *
     * @param agentLoop boucle agentique configurée par le starter
     * @return factory du cortex
     */
    @Bean
    public CortexFactory cortexFactory(AgentLoop agentLoop) {
        log.info("CortexFactory enregistre - agent 'cortex' disponible");
        return new CortexFactory(agentLoop);
    }

    /**
     * Enregistre l'EchoSpecialist comme spécialiste de démo.
     *
     * @param chatClient client LLM fourni par Spring AI
     * @param parser parser de réponse structurée
     * @param stateMachine machine à états pour le routage
     * @param journal journal d'audit optionnel
     * @return spécialiste echo
     */
    @Bean
    public EchoSpecialist echoSpecialist(ChatClient chatClient,
                                         AgentResponseParser parser,
                                         AgentStateMachine stateMachine,
                                         ObjectProvider<Journal> journal) {
        log.info("EchoSpecialist enregistre - specialiste de demo disponible");
        return new EchoSpecialist(chatClient, parser, stateMachine, journal.getIfAvailable());
    }

    /**
     * Enregistre l'adapter Dispatcher du spécialiste Claude.
     *
     * @param claudeCodeAgent spécialiste CLI Claude existant
     * @param missionMapper convertisseur moteur -> mission coding
     * @param outcomeMapper convertisseur rapport -> outcome moteur
     * @return adapter prêt pour l'assignee {@code claude}
     */
    @Bean
    public ClaudeAgentFactoryAdapter claudeAgentFactoryAdapter(ClaudeCodeAgent claudeCodeAgent,
                                                               TaskMessageCodingMissionMapper missionMapper,
                                                               CodingAgentOutcomeMapper outcomeMapper) {
        log.info("ClaudeAgentFactoryAdapter enregistre - specialiste 'claude' disponible via Dispatcher");
        return new ClaudeAgentFactoryAdapter(claudeCodeAgent, missionMapper, outcomeMapper);
    }

    /**
     * Enregistre l'adapter Dispatcher du spécialiste Codex.
     *
     * @param codexAgent spécialiste CLI Codex existant
     * @param missionMapper convertisseur moteur -> mission coding
     * @param outcomeMapper convertisseur rapport -> outcome moteur
     * @return adapter prêt pour l'assignee {@code codex}
     */
    @Bean
    public CodexAgentFactoryAdapter codexAgentFactoryAdapter(CodexAgent codexAgent,
                                                             TaskMessageCodingMissionMapper missionMapper,
                                                             CodingAgentOutcomeMapper outcomeMapper) {
        log.info("CodexAgentFactoryAdapter enregistre - specialiste 'codex' disponible via Dispatcher");
        return new CodexAgentFactoryAdapter(codexAgent, missionMapper, outcomeMapper);
    }

    /**
     * Injecte les règles de délégation Claude/Codex dans le prompt système du Cortex.
     *
     * @param properties configuration applicative des spécialistes coding
     * @return extension de prompt dédiée au routage coding
     */
    @Bean
    public SystemPromptExtension codingRoutingPromptExtension(CodingAgentsProperties properties) {
        return new CodingRoutingPromptExtension(properties);
    }
}
