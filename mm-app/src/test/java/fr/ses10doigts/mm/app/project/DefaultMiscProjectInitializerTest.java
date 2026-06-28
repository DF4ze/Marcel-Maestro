package fr.ses10doigts.mm.app.project;

import static org.assertj.core.api.Assertions.assertThat;

import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Valide la creation automatique du projet systeme par defaut au demarrage.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class DefaultMiscProjectInitializerTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceProperties workspaceProperties;

    @AfterEach
    void tearDown() throws IOException {
        Path root = Paths.get(workspaceProperties.getRoot());
        if (Files.exists(root)) {
            Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // best effort
                        }
                    });
        }
    }

    @Test
    @DisplayName("le projet systeme Autre est cree automatiquement au demarrage")
    void startupCreatesDefaultMiscProject() throws IOException {
        var project = projectRepository.findBySanitizedName(ProjectService.DEFAULT_MISC_PROJECT_SLUG);

        assertThat(project).isPresent();
        assertThat(project.get().getName()).isEqualTo(ProjectService.DEFAULT_MISC_PROJECT_NAME);

        Path projectFile = Paths.get(project.get().getWorkspacePath()).resolve("PROJECT.md");
        assertThat(Files.exists(projectFile)).isTrue();
        assertThat(Files.readString(projectFile))
                .contains("projet fourre-tout")
                .contains("projet par defaut");
    }
}
