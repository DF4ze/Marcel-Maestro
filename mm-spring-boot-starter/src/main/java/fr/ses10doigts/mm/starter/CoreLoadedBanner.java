package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.CoreInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bean témoin de l'étape 1 : prouve que l'autoconfiguration du starter s'est
 * bien appliquée et que le noyau (mm-core) est sur le classpath du consommateur.
 *
 * <p>Émet le log « noyau chargé » à la construction. Sera remplacé par le câblage
 * réel des ports aux étapes suivantes.</p>
 */
public class CoreLoadedBanner {

    private static final Logger log = LoggerFactory.getLogger(CoreLoadedBanner.class);

    public CoreLoadedBanner() {
        log.info("Marcel Maestro — noyau chargé : {} {} (via mm-spring-boot-starter, autoconfiguration vide)",
                CoreInfo.NAME, CoreInfo.VERSION);
    }
}
