package fr.ses10doigts.mm.starter.orchestration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés de configuration du Dispatcher (étape 7).
 *
 * <p>Préfixe : {@code mm.dispatcher}.</p>
 *
 * <ul>
 *   <li>{@code pool-size} — nombre max de threads pour l'exécution des agents (défaut : 4)</li>
 *   <li>{@code queue-capacity} — capacité de la file d'attente du pool (défaut : 50)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mm.dispatcher")
@Getter
@Setter
public class DispatcherProperties {

    /** Nombre max de threads dans le pool d'exécution des agents. */
    private int poolSize = 4;

    /** Capacité de la file d'attente du ThreadPoolTaskExecutor. */
    private int queueCapacity = 50;
}
