package fr.ses10doigts.mm.starter.orchestration;

import fr.ses10doigts.mm.core.orchestration.AgentFactory;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.queue.InMemoryTaskQueue;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Autoconfiguration de l'orchestrateur minimal (étape 7).
 *
 * <p>Câble la file de tâches ({@link InMemoryTaskQueue}), le pool borné
 * ({@link ThreadPoolTaskExecutor}) et le {@link Dispatcher}. Le Dispatcher est
 * créé uniquement si au moins une {@link AgentFactory} est déclarée dans le contexte.</p>
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
     * Pool borné pour l'exécution des agents. La taille est configurable via
     * {@code mm.dispatcher.pool-size} (défaut : 4).
     */
    @Bean(name = "mmDispatcherExecutor")
    @ConditionalOnMissingBean(name = "mmDispatcherExecutor")
    public ThreadPoolTaskExecutor mmDispatcherExecutor(DispatcherProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getPoolSize());
        executor.setMaxPoolSize(props.getPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix("mm-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Pool d'exécution configuré — corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                props.getPoolSize(), props.getPoolSize(), props.getQueueCapacity());
        return executor;
    }

    /**
     * Dispatcher — câblé uniquement si au moins une {@link AgentFactory} est présente.
     * Démarre automatiquement sa boucle de poll.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentFactory.class)
    public Dispatcher dispatcher(TaskQueue taskQueue,
                                  ObjectProvider<AgentFactory> factories,
                                  ThreadPoolTaskExecutor mmDispatcherExecutor) {
        List<AgentFactory> factoryList = factories.orderedStream().toList();
        dispatcher = new Dispatcher(taskQueue, factoryList, mmDispatcherExecutor);
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
