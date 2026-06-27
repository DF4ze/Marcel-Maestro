package fr.ses10doigts.mm.app.config;

import java.util.concurrent.Executor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Configuration de test qui remplace l'executor asynchrone par un executor synchrone.
 *
 * <p>Avec {@link org.springframework.scheduling.annotation.EnableAsync} actif,
 * les méthodes {@code @Async} s'exécutent dans des threads séparés, ce qui rend
 * les assertions de test instables (race condition entre la complétion de la tâche
 * et les vérifications). Cette configuration fournit un {@link SyncTaskExecutor}
 * qui exécute les méthodes {@code @Async} de façon synchrone dans le même thread
 * d'appel, rendant les tests déterministes.</p>
 *
 * <p>À inclure dans les classes de test via {@code @Import(SyncAsyncTestConfiguration.class)}.</p>
 */
@TestConfiguration
public class SyncAsyncTestConfiguration {

    /**
     * Executor synchrone pour les tests : les méthodes {@code @Async} s'exécutent
     * dans le thread appelant, comme un appel synchrone ordinaire.
     *
     * @return executor synchrone (pas de thread séparé)
     */
    @Bean
    @Primary
    public Executor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
