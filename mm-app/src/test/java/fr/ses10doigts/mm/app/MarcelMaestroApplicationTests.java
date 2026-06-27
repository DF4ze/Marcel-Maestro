package fr.ses10doigts.mm.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.ses10doigts.mm.starter.CoreLoadedBanner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test de démarrage (étape 1, item 5) : le contexte Spring se charge et
 * l'autoconfiguration du starter a bien fourni le bean témoin du noyau.
 */
@SpringBootTest
@Tag("slow")
class MarcelMaestroApplicationTests {

    @Autowired
    private CoreLoadedBanner coreLoadedBanner;

    @Test
    void contexteDemarreEtNoyauCharge() {
        assertNotNull(coreLoadedBanner, "Le bean témoin du noyau doit être fourni par l'autoconfiguration");
    }
}
