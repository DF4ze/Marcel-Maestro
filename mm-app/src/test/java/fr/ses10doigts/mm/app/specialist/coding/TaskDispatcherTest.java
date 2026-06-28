package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link TaskDispatcher}.
 */
class TaskDispatcherTest {

    @Test
    @DisplayName("Submit retourne un taskId et persiste le rapport une fois terminé")
    void submit_returnsTaskIdAndStoresCompletedReport() {
        AgentReport doneReport = AgentReport.builder()
                .status(AgentReport.Status.DONE)
                .summary("ok")
                .factsDiscovered(java.util.List.of())
                .decisions(java.util.List.of())
                .blocker(null)
                .build();
        SpecialistAgentPort claude = (task, context) -> doneReport;
        TaskRouter router = new TaskRouter(Map.of("claude", claude, "codex", claude), new CodingAgentsProperties());
        Executor directExecutor = Runnable::run;
        TaskDispatcher dispatcher = new TaskDispatcher(router, directExecutor);
        AgentTask task = AgentTask.builder()
                .id("task-1")
                .title("Titre")
                .description("Description")
                .category(TaskCategory.CODING)
                .build();
        MarcelContext context = MarcelContext.builder().workingDirectory("D:/work/project").build();

        String taskId = dispatcher.submit(task, context);

        assertThat(taskId).isEqualTo("task-1");
        assertThat(dispatcher.isRunning(taskId)).isFalse();
        assertThat(dispatcher.getReport(taskId)).contains(doneReport);
    }

    @Test
    @DisplayName("Stop annule une tâche en cours et enregistre un rapport ko")
    void stop_cancelsRunningTask() throws Exception {
        AtomicBoolean started = new AtomicBoolean(false);
        SpecialistAgentPort claude = (task, context) -> {
            started.set(true);
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return AgentReport.ko("interrompu");
            }
            return AgentReport.ko("non interrompu");
        };
        TaskRouter router = new TaskRouter(Map.of("claude", claude, "codex", claude), new CodingAgentsProperties());
        TaskDispatcher dispatcher = new TaskDispatcher(router, java.util.concurrent.Executors.newCachedThreadPool());
        AgentTask task = AgentTask.builder()
                .id("task-stop")
                .title("Titre")
                .description("Description")
                .category(TaskCategory.CODING)
                .build();
        MarcelContext context = MarcelContext.builder().workingDirectory("D:/work/project").build();

        dispatcher.submit(task, context);
        while (!started.get()) {
            Thread.sleep(10);
        }

        dispatcher.stop("task-stop");

        assertThat(dispatcher.isRunning("task-stop")).isFalse();
        assertThat(dispatcher.getReport("task-stop")).isPresent();
        assertThat(dispatcher.getReport("task-stop").orElseThrow().getStatus()).isEqualTo(AgentReport.Status.KO);
        assertThat(dispatcher.getReport("task-stop").orElseThrow().getSummary()).contains("STOP");
    }
}
