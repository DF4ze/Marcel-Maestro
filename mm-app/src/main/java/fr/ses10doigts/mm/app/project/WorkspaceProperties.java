package fr.ses10doigts.mm.app.project;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés du workspace Marcel Maestro.
 *
 * <p>Préfixe : {@code mm.workspace}.</p>
 *
 * <ul>
 *   <li>{@code root} — chemin (absolu ou relatif au répertoire de lancement) du
 *       dossier workspace principal. Marcel y crée un sous-dossier par projet lors
 *       de l'appel à {@code ProjectService.create()}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mm.workspace")
@Getter
@Setter
public class WorkspaceProperties {

    /**
     * Dossier racine du workspace.
     * Défaut : {@code ./workspace} (relatif au répertoire de lancement).
     */
    private String root = "./workspace";
}
