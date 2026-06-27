package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatAgent {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final MarcelChatPromptComposer promptComposer;
    private final TaskQueue taskQueue;
    private final AgentContextHolder agentContextHolder;
    private final ObjectProvider<Dispatcher> dispatcherProvider;
    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceRepository projectWorkspaceRepository;
    private final PathValidator pathValidator;

    @Value("${mm.chat.context.max-file-read-chars:5000}")
    private int maxFileReadChars;

    public String chat(String conversationId, String userMessage) {
        long startedAt = System.currentTimeMillis();
        log.info("ChatAgent demarre - conversationId={}", conversationId);

        String content = chatClient.prompt()
                .advisors(spec -> spec
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .system(promptComposer.compose())
                .tools(this)
                .user(userMessage)
                .call()
                .content();

        long durationMs = System.currentTimeMillis() - startedAt;
        log.info("ChatAgent termine - conversationId={}, durationMs={}", conversationId, durationMs);
        return content == null ? "" : content;
    }

    @Tool(name = "submit_task",
            description = "Soumet une tache au moteur agentique Marcel pour execution en arriere-plan. "
                    + "A utiliser quand la demande requiert une action concrete : lire/ecrire des fichiers, "
                    + "lancer un build Maven, deployer sur le VPS. "
                    + "Ne pas utiliser pour une simple question ou discussion. "
                    + "Retourne l'identifiant de la tache soumise.")
    public String submitTask(String description) {
        AgentContext currentContext = agentContextHolder.get();
        if (currentContext == null) {
            throw new IllegalStateException("Aucun AgentContext lie pour submit_task");
        }
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null || !dispatcher.isRunning()) {
            throw new IllegalStateException("Dispatcher indisponible pour submit_task");
        }

        String taskId = UUID.randomUUID().toString();
        TaskMessage taskMessage = new TaskMessage(
                taskId,
                TaskType.USER_REQUEST,
                "cortex",
                description,
                AgentContext.of(
                        currentContext.tenant(),
                        currentContext.projectId(),
                        currentContext.conversationId(),
                        taskId));

        taskQueue.submit(taskMessage);
        log.info("submit_task execute - taskId={}, projectId={}, conversationId={}, description='{}'",
                taskId,
                currentContext.projectId(),
                currentContext.conversationId(),
                truncate(description, 80));
        return "Tache soumise - id: " + taskId;
    }

    @Tool(name = "read_project_file",
            description = "Lit le contenu d'un fichier dans le projet courant. "
                    + "Cherche d'abord dans le workspace interne du projet, puis dans ses workspaces rattaches si besoin. "
                    + "Chemin relatif (ex: 'src/Main.java', 'notes/todo.md'). "
                    + "Ne pas utiliser pour PROJECT.md et ROADMAP.md qui sont deja dans le contexte.")
    public String readProjectFile(String relativePath) {
        AgentContext currentContext = agentContextHolder.get();
        if (currentContext == null || currentContext.projectId() == null || currentContext.projectId().isBlank()) {
            return "Impossible de lire le fichier : aucun projet courant.";
        }
        if (relativePath == null || relativePath.isBlank()) {
            return "Impossible de lire le fichier : chemin relatif vide.";
        }

        Optional<ProjectEntity> project = projectRepository.findById(currentContext.projectId());
        if (project.isEmpty()) {
            return "Impossible de lire le fichier : projet courant introuvable.";
        }

        Path workspacePath;
        try {
            workspacePath = Path.of(project.get().getWorkspacePath()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("read_project_file - workspacePath invalide pour projectId={}",
                    currentContext.projectId(), e);
            return "Impossible de lire le fichier : workspace du projet invalide.";
        }

        Path primaryPath = workspacePath.resolve(relativePath).normalize();
        if (!primaryPath.startsWith(workspacePath)) {
            log.warn("read_project_file - chemin rejete hors workspace projet: projectId={}, path={}",
                    currentContext.projectId(), primaryPath);
            return "Acces refuse : chemin hors du workspace du projet.";
        }

        Path readablePath = resolveReadablePath(currentContext.projectId(), relativePath, primaryPath);
        if (readablePath == null) {
            return "Fichier introuvable dans le workspace du projet : " + relativePath;
        }

        try {
            String content = Files.readString(readablePath, StandardCharsets.UTF_8);
            String truncated = truncateContent(content, maxFileReadChars, currentContext.projectId(), relativePath);
            log.debug("read_project_file - fichier lu: projectId={}, path={}, lengthBefore={}, lengthAfter={}",
                    currentContext.projectId(), readablePath, content.length(), truncated.length());
            return truncated;
        } catch (IOException e) {
            log.warn("read_project_file - lecture impossible: projectId={}, path={}",
                    currentContext.projectId(), readablePath, e);
            return "Impossible de lire le fichier : erreur d'acces a " + relativePath;
        }
    }

    private Path resolveReadablePath(String projectId, String relativePath, Path primaryPath) {
        try {
            pathValidator.validatePath(primaryPath.toString(), projectId);
            if (Files.exists(primaryPath) && Files.isRegularFile(primaryPath)) {
                return primaryPath;
            }
        } catch (ToolException e) {
            log.warn("read_project_file - validation refusee: projectId={}, path={}", projectId, primaryPath, e);
            return null;
        }

        List<Path> candidates = projectWorkspaceRepository.findAllByProjectId(projectId).stream()
                .map(ws -> Path.of(ws.getPath()).toAbsolutePath().normalize().resolve(relativePath).normalize())
                .toList();

        for (Path candidate : candidates) {
            try {
                pathValidator.validatePath(candidate.toString(), projectId);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    log.info("read_project_file - fallback workspace rattache: projectId={}, path={}",
                            projectId, candidate);
                    return candidate;
                }
            } catch (ToolException e) {
                log.debug("read_project_file - candidate externe refuse: projectId={}, path={}",
                        projectId, candidate, e);
            }
        }
        return null;
    }

    private String truncateContent(String content, int maxChars, String projectId, String relativePath) {
        if (content.length() <= maxChars) {
            return content;
        }
        int endIndex = Math.min(content.length(), maxChars);
        log.info("read_project_file - troncature appliquee: projectId={}, path={}, originalLength={}, maxChars={}",
                projectId, relativePath, content.length(), maxChars);
        return content.substring(0, endIndex) + "\n[... contenu tronque]";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "null";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
