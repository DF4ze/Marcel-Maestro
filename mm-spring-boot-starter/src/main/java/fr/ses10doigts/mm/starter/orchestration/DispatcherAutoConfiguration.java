package fr.ses10doigts.mm.starter.orchestration;

import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import fr.ses10doigts.mm.core.orchestration.AgentFactory;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.orchestration.TaskOutcomeListener;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.queue.InMemoryTaskQueue;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration de l'orchestrateur (E2-M1 : virtual threads, ADR-020).
 *
 * <p>Câble la file de tâches ({@link InMemoryTaskQueue}) et le {@link Dispatcher}.
 * L'exécuteur d'agents est un virtual-thread-per-task executor (Java 21 Loom) :
 * une tâche ne peut jamais être rejetée faute de thread disponible.
 * Le bean {@code mmDispatcherExecutor} reste remplaçable par l'hôte
 * via {@code @ConditionalOnMissingBean}.</p>
 *
 * <p>Le Dispatcher est créé uniquement si au moins une {@link AgentFactory}
 * est déclarée dans le contexte.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(DispatcherProperties.class)
@Slf4j
public class DispatcherAutoConfiguration {

    private Dispatcher dispatcher;

    /**
     * File de tâches in-memory par défaut (ADR-015). Remplaçable par un bean
     * durable déclaré par l'hôte.
     */
    @Bean
    @ConditionalOnMissingBean(TaskQueue.class)
    public InMemoryTaskQueue inMemoryTaskQueue() {
        log.info("File de tâches in-memory initialisée (non-durable, ADR-015)");
        return new InMemoryTaskQueue();
    }

    /**
     * Exécuteur virtual-thread-per-task (ADR-020, Java 21 Loom).
     *
     * <p>Actif uniquement quand {@code spring.threads.virtual.enabled=true}.
     * Un nouveau virtual thread est créé pour chaque tâche agent ; aucune
     * tâche ne peut être rejetée faute de thread disponible.</p>
     *
     * @return exécuteur virtual-thread illimité
     */
    @Bean(name = "mmDispatcherExecutor")
    @ConditionalOnMissingBean(name = "mmDispatcherExecutor")
    @ConditionalOnProperty(name = "spring.threads.virtual.enabled", havingValue = "true")
    public Executor mmVirtualDispatcherExecutor() {
        log.info("Exécuteur d'agents : virtual-thread-per-task (ADR-020, Java 21 Loom) — aucun rejet possible");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Exécuteur pool borné de secours — actif uniquement quand virtual threads
     * sont absents ou explicitement désactivés. La taille est configurable via
     * {@code mm.dispatcher.pool-size} (défaut : 4).
     *
     * @param props propriétés du dispatcher
     * @return exécuteur pool borné
     */
    @Bean(name = "mmDispatcherExecutor")
    @ConditionalOnMissingBean(name = "mmDispatcherExecutor")
    @ConditionalOnProperty(name = "spring.threads.virtual.enabled",
            havingValue = "false", matchIfMissing = true)
    public Executor mmBoundedDispatcherExecutor(DispatcherProperties props) {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getPoolSize());
        executor.setMaxPoolSize(props.getPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix("mm-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Exécuteur d'agents : pool borné (fallback) — corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                props.getPoolSize(), props.getPoolSize(), props.getQueueCapacity());
        return executor;
    }

    /**
     * Dispatcher — câblé uniquement si au moins une {@link AgentFactory} est présente.
     * Démarre automatiquement sa boucle de poll.
     *
     * @param taskQueue                 file de tâches à consommer
     * @param factories                 factories d'agents disponibles
     * @param executor                  exécuteur d'agents (virtual ou borné selon config)
     * @param humanInteractionProvider  canal de notification (optionnel)
     * @param outcomeListenerProvider   observateurs de fin de tâche (fermeture de boucle, optionnels)
     * @return le Dispatcher démarré
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentFactory.class)
    public Dispatcher dispatcher(TaskQueue taskQueue,
                                  ObjectProvider<AgentFactory> factories,
                                  @Qualifier("mmDispatcherExecutor") Executor executor,
                                  ObjectProvider<HumanInteraction> humanInteractionProvider,
                                  ObjectProvider<TaskOutcomeListener> outcomeListenerProvider) {
        List<AgentFactory> factoryList = factories.orderedStream().toList();
        HumanInteraction humanInteraction = humanInteractionProvider.getIfAvailable();
        List<TaskOutcomeListener> outcomeListeners = outcomeListenerProvider.orderedStream().toList();
        dispatcher = new Dispatcher(taskQueue, factoryList, executor, humanInteraction, outcomeListeners);
        dispatcher.start();
        return dispatcher;
    }

    /**
     * Arrêt propre du Dispatcher à la fermeture du contexte Spring.
     */
    @PreDestroy
    public void shutdownDispatcher() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }
}
