package fr.ses10doigts.mm.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration du noyau Marcel Maestro.
 *
 * <p>Étape 1 : VOLONTAIREMENT vide de logique fonctionnelle. Elle ne déclare qu'un
 * bean témoin ({@link CoreLoadedBanner}) pour valider le smoke test de démarrage.
 * Les beans réels (ports, boucle agentique, mémoire, …) seront câblés ici aux
 * étapes 2 et suivantes.</p>
 */
@AutoConfiguration
public class MmCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CoreLoadedBanner coreLoadedBanner() {
        return new CoreLoadedBanner();
    }
}
