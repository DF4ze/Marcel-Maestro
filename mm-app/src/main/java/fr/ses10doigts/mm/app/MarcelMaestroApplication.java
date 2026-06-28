package fr.ses10doigts.mm.app;

import fr.ses10doigts.mm.app.project.WorkspaceProperties;
import fr.ses10doigts.mm.app.specialist.coding.CodingAgentsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Point d'entrée du consommateur dev/devops de Marcel Maestro (MM).
 *
 * <p>Étape 1 : démarre simplement le contexte Spring. L'autoconfiguration du
 * starter ({@code MmCoreAutoConfiguration}) déclare le bean témoin qui log
 * « noyau chargé ». Aucune interface métier, aucun appel LLM, aucune BDD.</p>
 *
 * <p>E2-M1 : active {@link WorkspaceProperties} ({@code mm.workspace.root})
 * pour que {@code ProjectService} connaisse le dossier racine des projets.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties({WorkspaceProperties.class, CodingAgentsProperties.class})
public class MarcelMaestroApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarcelMaestroApplication.class, args);
    }
}
