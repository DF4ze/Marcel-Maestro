package fr.ses10doigts.mm.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée du consommateur dev/devops de Marcel Maestro (MM).
 *
 * <p>Étape 1 : démarre simplement le contexte Spring. L'autoconfiguration du
 * starter ({@code MmCoreAutoConfiguration}) déclare le bean témoin qui log
 * « noyau chargé ». Aucune interface métier, aucun appel LLM, aucune BDD.</p>
 */
@SpringBootApplication
public class MarcelMaestroApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarcelMaestroApplication.class, args);
    }
}
