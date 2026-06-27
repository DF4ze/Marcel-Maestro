package fr.ses10doigts.mm.starter.project;

import static org.assertj.core.api.Assertions.assertThat;

import fr.ses10doigts.mm.core.tool.WorkspaceRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration de {@link JpaWorkspaceRegistry} sur SQLite in-memory (E2-M3).
 *
 * <p>Vérifie les scénarios de bypass :</p>
 * <ul>
 *   <li>Chemin normalisé appartenant à un dossier déclaré → {@code true}.</li>
 *   <li>Chemin hors tout dossier déclaré → {@code false}.</li>
 *   <li>Path traversal essayant de sortir du dossier déclaré → {@code false}.</li>
 *   <li>Projet sans aucun dossier déclaré → {@code false}.</li>
 *   <li>{@code projectId} null → {@code false}.</li>
 *   <li>Normalisation cross-plateforme : séparateurs différents, sous-chemins profonds.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class JpaWorkspaceRegistryTest {

    @Autowired
    private WorkspaceRegistry workspaceRegistry;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectWorkspaceRepository workspaceRepository;

    private static final String PROJECT_ID = "proj-e2m3-test";

    /** Dossier externe déclaré (chemin absolu normalisé sur la plateforme courante). */
    private final String declaredRoot = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("mm-test-workspace-" + UUID.randomUUID())
            .toAbsolutePath().normalize().toString();

    @BeforeEach
    void setUp() {
        // Créer un projet minimal en base (sans créer le dossier sur disque)
        ProjectEntity project = ProjectEntity.builder()
                .id(PROJECT_ID)
                .name("Test E2-M3")
                .sanitizedName("test-e2-m3")
                .workspacePath("/tmp/mm-internal/test-e2-m3")
                .status(ProjectStatus.ACTIVE)
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();
        projectRepository.save(project);

        // Déclarer le dossier externe
        ProjectWorkspaceEntity ws = ProjectWorkspaceEntity.builder()
                .id(UUID.randomUUID().toString())
                .project(project)
                .path(declaredRoot)
                .addedAt(Instant.now().toString())
                .build();
        workspaceRepository.save(ws);
    }

    @Test
    @DisplayName("Chemin sous le dossier déclaré → true")
    void cheminSousDossierDeclaré_retourneTrue() {
        String path = Path.of(declaredRoot).resolve("src/main/Foo.java")
                .toAbsolutePath().toString();

        assertThat(workspaceRegistry.isInDeclaredWorkspace(path, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("Chemin exactement égal au dossier déclaré → true")
    void cheminEgalAuDossierDeclaré_retourneTrue() {
        assertThat(workspaceRegistry.isInDeclaredWorkspace(declaredRoot, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("Chemin hors du dossier déclaré → false")
    void cheminHorsDossierDeclaré_retourneFalse() {
        String outsidePath = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("autre-dossier/secret.txt").toAbsolutePath().toString();

        assertThat(workspaceRegistry.isInDeclaredWorkspace(outsidePath, PROJECT_ID)).isFalse();
    }

    @Test
    @DisplayName("Path traversal hors du dossier déclaré → false")
    void pathTraversalHorsDossierDeclaré_retourneFalse() {
        // /declared/../etc/passwd normalise en /etc/passwd → hors du dossier déclaré
        String traversal = Path.of(declaredRoot).resolve("../etc/passwd").toString();

        assertThat(workspaceRegistry.isInDeclaredWorkspace(traversal, PROJECT_ID)).isFalse();
    }

    @Test
    @DisplayName("Projet sans dossier déclaré → false")
    void projetSansDossierDeclaré_retourneFalse() {
        // Projet qui existe mais sans workspace externe
        String autreProjetId = "proj-sans-workspace";
        ProjectEntity autreProjet = ProjectEntity.builder()
                .id(autreProjetId)
                .name("Autre")
                .sanitizedName("autre")
                .workspacePath("/tmp/mm-internal/autre")
                .status(ProjectStatus.ACTIVE)
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();
        projectRepository.save(autreProjet);

        String path = Path.of(declaredRoot).resolve("src/Foo.java").toAbsolutePath().toString();

        assertThat(workspaceRegistry.isInDeclaredWorkspace(path, autreProjetId)).isFalse();
    }

    @Test
    @DisplayName("projectId null → false (sécurité par défaut)")
    void projectIdNull_retourneFalse() {
        String path = Path.of(declaredRoot).resolve("src/Foo.java").toAbsolutePath().toString();

        assertThat(workspaceRegistry.isInDeclaredWorkspace(path, null)).isFalse();
    }

    @Test
    @DisplayName("Sous-chemin profond dans le dossier déclaré → true")
    void sousCheminProfond_retourneTrue() {
        String deepPath = Path.of(declaredRoot)
                .resolve("src/main/java/fr/ses10doigts/mm/app/tool/WriteFileTool.java")
                .toAbsolutePath().toString();

        assertThat(workspaceRegistry.isInDeclaredWorkspace(deepPath, PROJECT_ID)).isTrue();
    }
}
