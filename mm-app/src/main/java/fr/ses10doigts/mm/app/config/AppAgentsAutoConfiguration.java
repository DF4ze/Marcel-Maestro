package fr.ses10doigts.mm.app.config;

import fr.ses10doigts.mm.app.specialist.CortexFactory;
import fr.ses10doigts.mm.app.specialist.EchoSpecialist;
import fr.ses10doigts.mm.core.engine.AgentLoop;
import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import java.util.List;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.starter.MmCoreAutoConfiguration;
import fr.ses10doigts.mm.starter.hitl.CompositeHumanInteraction;
import fr.ses10doigts.mm.starter.orchestration.DispatcherAutoConfiguration;
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
 * <p>Enregistre le {@link CortexFactory} (orchestrateur principal) et
 * l'{@link EchoSpecialist} (spécialiste de démo) comme beans {@code AgentFactory}.
 * Déclarée après {@link MmCoreAutoConfiguration} via {@link AutoConfigureAfter} pour
 * garantir que l'{@link AgentLoop} est disponible au moment de l'évaluation des
 * conditions.</p>
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
     * @param dispatcherProvider  provider du Dispatcher (pour vérification au démarrage)
     * @param agentLoopProvider   provider de l'AgentLoop (pour vérification au démarrage)
     * @param compositeProvider   provider du CompositeHumanInteraction (pour lister les canaux)
     */
    public AppAgentsAutoConfiguration(ObjectProvider<Dispatcher> dispatcherProvider,
                                       ObjectProvider<AgentLoop> agentLoopProvider,
                                       ObjectProvider<CompositeHumanInteraction> compositeProvider) {
        this.dispatcherProvider = dispatcherProvider;
        this.agentLoopProvider = agentLoopProvider;
        this.compositeProvider = compositeProvider;
    }

    /**
     * Rapport de câblage émis au démarrage complet de l'application.
     * Permet de vérifier en un coup d'œil que toute la chaîne est opérationnelle.
     *
     * @param event événement de démarrage Spring Boot
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("=== Marcel-Maestro — rapport de câblage ===");
        log.info("  AgentLoop   : {}", agentLoopProvider.getIfAvailable() != null ? "✅ présent" : "❌ ABSENT");
        log.info("  Dispatcher  : {}", dispatcherProvider.getIfAvailable() != null ? "✅ présent" : "❌ ABSENT");
        CompositeHumanInteraction composite = compositeProvider.getIfAvailable();
        if (composite != null) {
            List<HumanInteraction> channels = composite.getChannels();
            log.info("  HITL canaux : {} canal/aux enregistré/s", channels.size());
            channels.forEach(ch -> log.info("    → {}", ch.getClass().getSimpleName()));
        } else {
            log.warn("  HITL canaux : ❌ CompositeHumanInteraction ABSENT");
        }
        log.info("===========================================");
    }

    /**
     * Enregistre le Cortex comme agent principal de Marcel Maestro.
     *
     * <p>Le {@link CortexFactory} réutilise l'{@link AgentLoop} du starter — déjà
     * câblé avec le LLM, les outils, le HITL et la mémoire.</p>
     *
     * @param agentLoop boucle agentique configurée par le starter
     * @return factory du cortex prête à l'emploi
     */
    @Bean
    public CortexFactory cortexFactory(AgentLoop agentLoop) {
        log.info("CortexFactory enregistré — agent 'cortex' disponible");
        return new CortexFactory(agentLoop);
    }

    /**
     * Enregistre l'EchoSpecialist comme spécialiste de démo.
     *
     * <p>L'EchoSpecialist crée sa propre boucle agentique interne avec un prompt
     * spécifique (ADR-007). Il a donc besoin du {@link ChatClient} brut, distinct
     * de la boucle du Cortex. La présence du {@link ChatClient} est garantie par
     * la condition de classe {@code @ConditionalOnBean(AgentLoop.class)}.</p>
     *
     * @param chatClient   client LLM fourni par Spring AI
     * @param parser       parser de réponse structurée
     * @param stateMachine machine à états pour le routage
     * @param journal      journal d'audit (optionnel)
     * @return spécialiste echo prêt à l'emploi
     */
    @Bean
    public EchoSpecialist echoSpecialist(ChatClient chatClient,
                                          AgentResponseParser parser,
                                          AgentStateMachine stateMachine,
                                          ObjectProvider<Journal> journal) {
        log.info("EchoSpecialist enregistré — spécialiste de démo disponible");
        return new EchoSpecialist(chatClient, parser, stateMachine, journal.getIfAvailable());
    }
}
