package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MissionBriefBuilderTest {

    private final MissionBriefBuilder builder = new MissionBriefBuilder();

    @Test
    @DisplayName("Liste les workspaces autorises dans le brief")
    void build_includesDeclaredWorkspaces() {
        AgentTask task = AgentTask.builder().id("task-1").title("Titre").description("Description").build();
        MarcelContext context = MarcelContext.builder()
                .projectMd("Projet")
                .roadmapResultMd("Roadmap")
                .c3Facts(List.of("fact 1"))
                .workingDirectory("D:/repo")
                .declaredWorkspaces(List.of("D:/repo", "D:/repo/workspace/marcel-maestro"))
                .build();

        String brief = builder.build(task, context);

        assertThat(brief).contains("RACINES DE TRAVAIL AUTORISEES");
        assertThat(brief).contains("- D:/repo (repertoire courant)");
        assertThat(brief).contains("- D:/repo/workspace/marcel-maestro");
        assertThat(brief).contains("Si un fichier manque dans le workspace interne");
    }
}
