package fr.ses10doigts.mm.core.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentResponse;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.SubTask;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests du Dispatcher (étape 7) : parallélisme, pool plein, STOP, cycle sub_tasks.
 *
 * <p>Utilise une implémentation de test de {@link TaskQueue} et un pool JDK pur
 * pour ne pas dépendre du starter (litmus de pureté mm-core).</p>
 */
class DispatcherTest {

    private Dispatcher dispatcher;
    private ExecutorService executorService;

    /**
     * Implémentation de test de la file — JDK pur, pas de dépendance starter.
     */
    private static class TestTaskQueue implements TaskQueue {
        private final LinkedBlockingQueue<TaskMessage> queue = new LinkedBlockingQueue<>();

        @Override
        public void submit(TaskMessage task) { queue.add(task); }

        @Override
        public TaskMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        @Override
        public boolean remove(String taskId) {
            return queue.removeIf(t -> taskId.equals(t.taskId()));
        }

        @Override
        public int size() { return queue.size(); }
    }

    private ExecutorService pool(int size) {
        executorService = Executors.newFixedThreadPool(size);
        return executorService;
    }

    @AfterEach
    void cleanup() {
        if (dispatcher != null) dispatcher.shutdown();
        if (executorService != null) executorService.shutdownNow();
    }

    private TaskMessage userRequest(String id, String assignee) {
        return new TaskMessage(id, TaskType.USER_REQUEST, assignee, "contenu " + id,
                AgentContext.of("default", "p1", "c1", id));
    }

    // ── Test 1 : Deux tâches simultanées traitées en parallèle ──────────

    @Test
    void deuxTachesSimultaneesTraiteesEnParallele() throws InterruptedException {
        TestTaskQueue queue = new TestTaskQueue();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch bothFinished = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> executedIds = new ConcurrentLinkedQueue<>();

        AgentFactory slowFactory = new AgentFactory() {
            @Override
            public String agentId() { return "slow"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                bothStarted.countDown();
                try {
                    // Attend que les deux soient démarrés → prouve le parallélisme
                    bothStarted.await(5, TimeUnit.SECONDS);
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executedIds.add(task.taskId());
                bothFinished.countDown();
                return new AgentOutcome(AgentStatus.DONE, null, 1, "ok");
            }
        };

        dispatcher = new Dispatcher(queue, List.of(slowFactory), pool(2));
        dispatcher.start();

        queue.submit(userRequest("t1", "slow"));
        queue.submit(userRequest("t2", "slow"));

        assertTrue(bothFinished.await(10, TimeUnit.SECONDS),
                "Les deux tâches auraient dû terminer dans les 10s");
        assertEquals(2, executedIds.size());
        assertTrue(executedIds.contains("t1"));
        assertTrue(executedIds.contains("t2"));
    }

    // ── Test 2 : Pool plein — la N+1ᵉ attend sans crash ─────────────────

    @Test
    void poolPleinLaTacheAttendSansCrash() throws InterruptedException {
        TestTaskQueue queue = new TestTaskQueue();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicInteger executedCount = new AtomicInteger();

        AgentFactory blockingFactory = new AgentFactory() {
            @Override
            public String agentId() { return "blocker"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                if ("t1".equals(task.taskId())) {
                    firstStarted.countDown();
                    try { releaseFirst.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                executedCount.incrementAndGet();
                if ("t2".equals(task.taskId())) {
                    secondFinished.countDown();
                }
                return new AgentOutcome(AgentStatus.DONE, null, 1, "ok");
            }
        };

        // Pool de 1 seul thread
        dispatcher = new Dispatcher(queue, List.of(blockingFactory), pool(1));
        dispatcher.start();

        queue.submit(userRequest("t1", "blocker"));
        queue.submit(userRequest("t2", "blocker"));

        // t1 démarre, t2 attend dans le pool/executor
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(0, executedCount.get(), "Aucune tâche ne doit être terminée tant que t1 est bloquée");
        assertEquals(1L, secondFinished.getCount(), "t2 ne doit pas se terminer avant la libération de t1");

        // Libérer t1 → t2 s'exécute
        releaseFirst.countDown();
        assertTrue(secondFinished.await(10, TimeUnit.SECONDS));
        assertEquals(2, executedCount.get());
    }

    // ── Test 3 : STOP en cours → arrêt propre ───────────────────────────

    @Test
    void stopEnCoursArretPropre() throws InterruptedException {
        TestTaskQueue queue = new TestTaskQueue();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        ConcurrentLinkedQueue<AgentStatus> finalStatuses = new ConcurrentLinkedQueue<>();

        AgentFactory stoppableFactory = new AgentFactory() {
            @Override
            public String agentId() { return "stoppable"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                taskStarted.countDown();
                // Simule un travail qui vérifie le stop entre opérations
                while (!stop.isStopped()) {
                    try { Thread.sleep(50); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                AgentOutcome outcome = new AgentOutcome(AgentStatus.KO, null, 1, "stopped by user");
                finalStatuses.add(outcome.finalStatus());
                taskFinished.countDown();
                return outcome;
            }
        };

        dispatcher = new Dispatcher(queue, List.of(stoppableFactory), pool(2));
        dispatcher.start();

        queue.submit(userRequest("t1", "stoppable"));
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        // STOP
        assertTrue(dispatcher.stop("t1"));
        assertTrue(taskFinished.await(5, TimeUnit.SECONDS));
        assertEquals(AgentStatus.KO, finalStatuses.poll());

        // Vérifier qu'aucun handle n'est orphelin
        Thread.sleep(200);
        assertEquals(0, dispatcher.activeCount());
    }

    // ── Test 4 : Cycle Cortex → sub_tasks → spécialiste → rapport ───────

    @Test
    void cycleCompletCortexSubTasksSpecialisteRapport() throws InterruptedException {
        TestTaskQueue queue = new TestTaskQueue();
        CountDownLatch cortexReportReceived = new CountDownLatch(1);
        ConcurrentLinkedQueue<TaskMessage> cortexReceivedReports = new ConcurrentLinkedQueue<>();

        // Cortex : retourne des sub_tasks au premier appel, DONE au second
        AtomicInteger cortexCallCount = new AtomicInteger();
        AgentFactory cortexFactory = new AgentFactory() {
            @Override
            public String agentId() { return "cortex"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                int call = cortexCallCount.incrementAndGet();
                if (call == 1 && task.type() == TaskType.USER_REQUEST) {
                    // Premier appel : délègue au spécialiste
                    AgentResponse response = new AgentResponse(
                            AgentStatus.DONE, "délégation", null, List.of(),
                            List.of(new SubTask("echo", "dis bonjour")));
                    return new AgentOutcome(AgentStatus.DONE, response, 1, "délégation");
                }
                // Appels suivants (rapports de spécialistes)
                if (task.type() == TaskType.SPECIALIST_REPORT) {
                    cortexReceivedReports.add(task);
                    cortexReportReceived.countDown();
                }
                return new AgentOutcome(AgentStatus.DONE, null, 1, "terminé");
            }
        };

        // Spécialiste echo : retourne un rapport simple
        AgentFactory echoFactory = new AgentFactory() {
            @Override
            public String agentId() { return "echo"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                AgentResponse response = new AgentResponse(
                        AgentStatus.DONE, "écho effectué",
                        "[ECHO] " + task.content(), List.of(), List.of());
                return new AgentOutcome(AgentStatus.DONE, response, 1, "écho effectué");
            }
        };

        dispatcher = new Dispatcher(queue, List.of(cortexFactory, echoFactory), pool(4));
        dispatcher.start();

        // Soumettre la requête utilisateur au Cortex
        queue.submit(userRequest("main-task", "cortex"));

        // Attendre que le rapport revienne au Cortex
        assertTrue(cortexReportReceived.await(10, TimeUnit.SECONDS),
                "Le Cortex aurait dû recevoir le rapport du spécialiste");

        TaskMessage report = cortexReceivedReports.poll();
        assertNotNull(report);
        assertEquals(TaskType.SPECIALIST_REPORT, report.type());
        assertTrue(report.content().contains("[ECHO]"),
                "Le rapport devrait contenir le résultat de l'écho");
    }

    // ── Test 5 : Factory inconnue → tâche ignorée, rapport KO ───────────

    @Test
    void factoryInconnueProduiteRapportKo() throws InterruptedException {
        TestTaskQueue queue = new TestTaskQueue();
        CountDownLatch reportReceived = new CountDownLatch(1);

        AgentFactory cortexFactory = new AgentFactory() {
            @Override
            public String agentId() { return "cortex"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                if (task.type() == TaskType.SPECIALIST_REPORT) {
                    reportReceived.countDown();
                }
                return new AgentOutcome(AgentStatus.DONE, null, 1, "ok");
            }
        };

        dispatcher = new Dispatcher(queue, List.of(cortexFactory), pool(2));
        dispatcher.start();

        // Soumettre une tâche vers un spécialiste inexistant
        queue.submit(new TaskMessage("t1", TaskType.SPECIALIST_REQUEST, "inexistant",
                "fais ça", AgentContext.of("default", "p1", "c1", "t1")));

        // Le rapport KO devrait être soumis et routé vers cortex
        assertTrue(reportReceived.await(5, TimeUnit.SECONDS),
                "Un rapport KO devrait être remonté vers le Cortex");
    }

    // ── Test 6 : STOP sur tâche pas encore démarrée ─────────────────────

    @Test
    void stopSurTacheDansLaFile() throws InterruptedException {
        TestTaskQueue queue = new TestTaskQueue();
        CountDownLatch firstBlocks = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        AgentFactory blockingFactory = new AgentFactory() {
            @Override
            public String agentId() { return "blocker"; }

            @Override
            public AgentOutcome execute(TaskMessage task, StopSignal stop) {
                if ("t1".equals(task.taskId())) {
                    firstBlocks.countDown();
                    try { releaseFirst.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new AgentOutcome(AgentStatus.DONE, null, 1, "ok");
            }
        };

        dispatcher = new Dispatcher(queue, List.of(blockingFactory), pool(1));
        dispatcher.start();

        queue.submit(userRequest("t1", "blocker"));
        queue.submit(userRequest("t2", "blocker"));

        // Attendre que t1 démarre
        assertTrue(firstBlocks.await(5, TimeUnit.SECONDS));

        // STOP sur t2
        dispatcher.stop("t2");

        releaseFirst.countDown();
        Thread.sleep(500);

        // Pas de crash, le dispatcher tourne toujours
        assertTrue(dispatcher.isRunning());
    }
}
