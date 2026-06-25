package fr.ses10doigts.mm.app.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceEntity;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration du {@link ProjectService} (E2-M1).
 *
 * <p>Vérifie le cycle CRUD complet, les collisions de slug, les opérations
 * filesystem (création et suppression de dossier), l'import de dossier existant,
 * et la gestion des workspaces externes.</p>
 *
 * <p>Le workspace racine est fixé à {@code ./target/test-workspace}
 * (cf. {@code application.yml} de test). Chaque test nettoie les données DB et
 * les dossiers créés via {@code @BeforeEach} / {@code @AfterEach}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectWorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceProperties workspaceProperties;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @AfterEach
    void tearDown() throws IOException {
        // Nettoyage filesystem — supprime le dossier test-workspace s'il existe.
        Path root = Paths.get(workspaceProperties.getRoot());
        if (Files.exists(root)) {
            Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // Best-effort cleanup
                        }
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle CRUD complet
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create → findById retourne le projet avec les bons champs")
    void createThenRead() {
        ProjectEntity created = projectService.create("Mon Projet Alpha");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getName()).isEqualTo("Mon Projet Alpha");
        assertThat(created.getSanitizedName()).isEqualTo("mon-projet-alpha");
        assertThat(created.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(created.getCreatedAt()).isNotBlank();
        assertThat(created.getUpdatedAt()).isNotBlank();

        ProjectEntity found = projectService.findById(created.getId());
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getSanitizedName()).isEqualTo("mon-projet-alpha");
    }

    @Test
    @DisplayName("create crée le dossier workspace sur le filesystem")
    void createCreatesDirectory() {
        ProjectEntity project = projectService.create("test-dir-creation");

        Path expectedDir = Paths.get(workspaceProperties.getRoot()).resolve("test-dir-creation");
        assertThat(Files.exists(expectedDir)).isTrue();
        assertThat(Files.isDirectory(expectedDir)).isTrue();
        assertThat(project.getWorkspacePath()).isEqualTo(expectedDir.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("archive → statut passe à ARCHIVED")
    void archiveChangesStatus() {
        ProjectEntity project = projectService.create("projet-a-archiver");

        ProjectEntity archived = projectService.archive(project.getId());

        assertThat(archived.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    @DisplayName("archive puis unarchive → statut revient à ACTIVE")
    void archiveThenUnarchive() {
        ProjectEntity project = projectService.create("va-et-vient");

        projectService.archive(project.getId());
        ProjectEntity reactivated = projectService.unarchive(project.getId());

        assertThat(reactivated.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    @DisplayName("delete supprime la DB et le dossier filesystem")
    void deleteRemovesDbAndFilesystem() {
        ProjectEntity project = projectService.create("a-supprimer");
        String id = project.getId();
        Path dir = Paths.get(project.getWorkspacePath());

        assertThat(Files.exists(dir)).isTrue();

        projectService.delete(id);

        assertThat(projectRepository.findById(id)).isEmpty();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    @DisplayName("findAll et findByStatus filtrent correctement")
    void findAllAndByStatus() {
        projectService.create("actif-1");
        ProjectEntity actif2 = projectService.create("actif-2");
        projectService.archive(actif2.getId());

        List<ProjectEntity> allProjects = projectService.findAll();
        assertThat(allProjects).hasSize(2);

        List<ProjectEntity> actifs = projectService.findByStatus(ProjectStatus.ACTIVE);
        assertThat(actifs).hasSize(1);
        assertThat(actifs.getFirst().getSanitizedName()).isEqualTo("actif-1");

        List<ProjectEntity> archives = projectService.findByStatus(ProjectStatus.ARCHIVED);
        assertThat(archives).hasSize(1);
        assertThat(archives.getFirst().getSanitizedName()).isEqualTo("actif-2");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Collision de sanitizedName
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("collision de sanitizedName → ProjectNameConflictException avec message explicite")
    void slugCollisionThrowsWithExplicitMessage() {
        projectService.create("Mon Projet");

        // "mon-projet" et "Mon Projet" donnent le même slug
        assertThatThrownBy(() -> projectService.create("mon-projet"))
                .isInstanceOf(ProjectNameConflictException.class)
                .hasMessageContaining("mon-projet");
    }

    @Test
    @DisplayName("collision : aucun dossier créé pour le second projet en conflit")
    void slugCollisionCreatesNoDirectory() {
        projectService.create("Double");

        assertThatThrownBy(() -> projectService.create("double"))
                .isInstanceOf(ProjectNameConflictException.class);

        // Un seul dossier "double" doit exister
        Path root = Paths.get(workspaceProperties.getRoot());
        try (var stream = Files.list(root)) {
            assertThat(stream.count()).isEqualTo(1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import de dossier existant
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("importExisting enregistre le projet sans recréer le dossier")
    void importExistingRegistersWithoutModifyingDirectory() throws IOException {
        Path existingDir = Paths.get(workspaceProperties.getRoot()).resolve("repo-existant");
        Files.createDirectories(existingDir);
        Path sentinelFile = existingDir.resolve("README.md");
        Files.writeString(sentinelFile, "contenu existant");

        ProjectEntity imported = projectService.importExisting(
                "repo-existant", existingDir.toAbsolutePath().toString());

        assertThat(imported.getId()).isNotBlank();
        assertThat(imported.getSanitizedName()).isEqualTo("repo-existant");
        assertThat(imported.getWorkspacePath()).isEqualTo(existingDir.toAbsolutePath().toString());

        // Le fichier sentinel doit toujours être là (dossier inchangé)
        assertThat(Files.exists(sentinelFile)).isTrue();
        assertThat(Files.readString(sentinelFile)).isEqualTo("contenu existant");
    }

    @Test
    @DisplayName("importExisting sur chemin inexistant → IllegalArgumentException")
    void importExistingOnMissingPathThrows() {
        assertThatThrownBy(() -> projectService.importExisting(
                "fantome", "/chemin/qui/nexiste/pas/du/tout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/chemin/qui/nexiste/pas/du/tout");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workspaces externes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addWorkspace ajoute un dossier externe, removeWorkspace le retire")
    void addAndRemoveWorkspace() throws IOException {
        ProjectEntity project = projectService.create("projet-avec-ws");

        // Créer un vrai dossier externe temporaire
        Path externalDir = Files.createTempDirectory("mm-test-external-ws-");
        try {
            ProjectWorkspaceEntity ws = projectService.addWorkspace(
                    project.getId(), externalDir.toAbsolutePath().toString());

            assertThat(ws.getId()).isNotBlank();
            assertThat(ws.getPath()).isEqualTo(externalDir.toAbsolutePath().toString());

            List<ProjectWorkspaceEntity> workspaces =
                    projectService.findWorkspaces(project.getId());
            assertThat(workspaces).hasSize(1);
            assertThat(workspaces.getFirst().getId()).isEqualTo(ws.getId());

            projectService.removeWorkspace(ws.getId());

            assertThat(projectService.findWorkspaces(project.getId())).isEmpty();
            // Le dossier externe ne doit PAS être supprimé
            assertThat(Files.exists(externalDir)).isTrue();
        } finally {
            Files.deleteIfExists(externalDir);
        }
    }

    @Test
    @DisplayName("addWorkspace avec chemin relatif → IllegalArgumentException")
    void addWorkspaceRelativePathThrows() {
        ProjectEntity project = projectService.create("projet-chemin-relatif");

        assertThatThrownBy(() -> projectService.addWorkspace(project.getId(), "chemin/relatif"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolu");
    }

    @Test
    @DisplayName("delete supprime en cascade les workspaces externes en DB")
    void deleteCascadesWorkspaces() throws IOException {
        ProjectEntity project = projectService.create("projet-cascade");
        Path externalDir = Files.createTempDirectory("mm-test-cascade-");
        try {
            projectService.addWorkspace(project.getId(), externalDir.toAbsolutePath().toString());
            assertThat(workspaceRepository.findAllByProjectId(project.getId())).hasSize(1);

            projectService.delete(project.getId());

            assertThat(workspaceRepository.findAllByProjectId(project.getId())).isEmpty();
            // Le dossier externe ne doit PAS être supprimé par delete()
            assertThat(Files.exists(externalDir)).isTrue();
        } finally {
            Files.deleteIfExists(externalDir);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sanitisation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sanitize : accents, espaces, caractères spéciaux → kebab-case ASCII")
    void sanitizeVariousCases() {
        assertThat(projectService.sanitize("Mon Super Projet!")).isEqualTo("mon-super-projet");
        assertThat(projectService.sanitize("Été 2026")).isEqualTo("ete-2026");
        assertThat(projectService.sanitize("  --Hello World--  ")).isEqualTo("hello-world");
        assertThat(projectService.sanitize("API v2.0")).isEqualTo("api-v2-0");
        assertThat(projectService.sanitize("café")).isEqualTo("cafe");
    }
}
