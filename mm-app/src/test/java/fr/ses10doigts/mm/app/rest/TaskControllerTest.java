package fr.ses10doigts.mm.app.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests unitaires du contrôleur REST de pilotage (étape 8).
 *
 * <p>Utilise {@code @WebMvcTest} pour charger uniquement la couche web
 * et injecter des mocks pour le {@link Dispatcher} et la {@link TaskQueue}.</p>
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Dispatcher dispatcher;

    @MockitoBean
    private TaskQueue taskQueue;

    /**
     * POST /api/tasks doit retourner HTTP 202 avec un taskId généré.
     */
    @Test
    void postSubmit_retourne202AvecTaskId() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Compile le projet\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty());

        verify(taskQueue).submit(any());
    }

    /**
     * GET /api/tasks doit retourner la liste des tâches actives et la taille de la file.
     */
    @Test
    void getList_retourneTachesActivesEtTailleFile() throws Exception {
        when(dispatcher.listActiveTaskIds()).thenReturn(Set.of("task-1", "task-2"));
        when(taskQueue.size()).thenReturn(3);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTasks").isArray())
                .andExpect(jsonPath("$.queueSize").value(3));
    }

    /**
     * GET /api/tasks/{taskId} doit retourner le statut d'une tâche.
     */
    @Test
    void getStatus_retourneStatutTache() throws Exception {
        when(dispatcher.listActiveTaskIds()).thenReturn(Set.of("task-42"));

        mockMvc.perform(get("/api/tasks/task-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-42"))
                .andExpect(jsonPath("$.active").value(true));
    }

    /**
     * DELETE /api/tasks/{taskId} doit arrêter la tâche et retourner le résultat.
     */
    @Test
    void deleteStop_retourneResultatArret() throws Exception {
        when(dispatcher.stop("task-99")).thenReturn(true);

        mockMvc.perform(delete("/api/tasks/task-99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-99"))
                .andExpect(jsonPath("$.stopped").value(true));
    }
}
