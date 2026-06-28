package fr.ses10doigts.mm.app.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gère la conversation initiale de cadrage projet et l'enrichissement mécanique de PROJECT.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectBootstrapService {

    public static final String BOOTSTRAP_CONVERSATION_TITLE = "Cadrage initial du projet";
    static final String PROJECT_FILE_NAME = "PROJECT.md";
    static final String LEGACY_PROJECT_FILE_NAME = "project.md";
    static final String BOOTSTRAP_PENDING_MARKER = "<!-- MARCEL:PROJECT_BOOTSTRAP_PENDING -->";
    static final String BOOTSTRAP_NOTES_MARKER = "<!-- MARCEL:PROJECT_BOOTSTRAP_NOTES -->";
    static final String BOOTSTRAP_CONVERSATION_ID_KEY = "bootstrapConversationId";

    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void initializeBootstrapConversation(ProjectEntity project, String conversationId) {
        ObjectNode config = readConfig(project.getConfig());
        config.put(BOOTSTRAP_CONVERSATION_ID_KEY, conversationId);
        project.setConfig(writeConfig(config));
        project.setUpdatedAt(Instant.now().toString());
        projectRepository.save(project);
        log.info("Conversation de cadrage initial enregistrée — projectId={}, conversationId={}",
                project.getId(), conversationId);
    }

    public boolean isBootstrapConversation(String projectId, String conversationId) {
        if (projectId == null || projectId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        Optional<ProjectEntity> project = projectRepository.findById(projectId);
        if (project.isEmpty()) {
            return false;
        }
        String bootstrapConversationId = readConfig(project.get().getConfig())
                .path(BOOTSTRAP_CONVERSATION_ID_KEY)
                .asText(null);
        return conversationId.equals(bootstrapConversationId) && hasBootstrapPendingMarker(project.get());
    }

    @Transactional
    public void appendUserInputToProject(String projectId, String conversationId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (!isRegisteredBootstrapConversation(project, conversationId)) {
            return;
        }
        Path projectFile = resolveProjectFile(project);
        String existingContent;
        try {
            existingContent = Files.readString(projectFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de lire PROJECT.md pour le projet " + projectId, e);
        }

        String updatedContent = appendBeforeMarker(existingContent, buildNoteBlock(userMessage));
        if (updatedContent.equals(existingContent)) {
            return;
        }

        try {
            Files.writeString(projectFile, updatedContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de mettre à jour PROJECT.md pour le projet " + projectId, e);
        }
        log.info("PROJECT.md enrichi automatiquement — projectId={}, conversationId={}", projectId, conversationId);
    }

    /**
     * Vérifie que la conversation cible correspond bien au cadrage initial enregistré.
     */
    private boolean isRegisteredBootstrapConversation(ProjectEntity project, String conversationId) {
        if (project == null || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        String bootstrapConversationId = readConfig(project.getConfig())
                .path(BOOTSTRAP_CONVERSATION_ID_KEY)
                .asText(null);
        return conversationId.equals(bootstrapConversationId);
    }

    private Path resolveProjectFile(ProjectEntity project) {
        Path workspacePath;
        try {
            workspacePath = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Workspace invalide pour le projet " + project.getId(), e);
        }
        Path uppercase = workspacePath.resolve(PROJECT_FILE_NAME);
        if (Files.exists(uppercase)) {
            return uppercase;
        }
        return workspacePath.resolve(LEGACY_PROJECT_FILE_NAME);
    }

    private boolean hasBootstrapPendingMarker(ProjectEntity project) {
        Path projectFile = resolveProjectFile(project);
        if (!Files.exists(projectFile)) {
            return false;
        }
        try {
            String content = Files.readString(projectFile, StandardCharsets.UTF_8);
            return content.contains(BOOTSTRAP_PENDING_MARKER);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de lire PROJECT.md pour le projet " + project.getId(), e);
        }
    }

    private ObjectNode readConfig(String rawConfig) {
        if (rawConfig == null || rawConfig.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawConfig);
            if (parsed instanceof ObjectNode objectNode) {
                return objectNode.deepCopy();
            }
        } catch (IOException e) {
            log.warn("Config projet illisible, réinitialisation du JSON d'amorçage", e);
        }
        return objectMapper.createObjectNode();
    }

    private String writeConfig(ObjectNode config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de sérialiser la config projet", e);
        }
    }

    private static String buildNoteBlock(String userMessage) {
        return "### Réponse utilisateur — " + Instant.now() + System.lineSeparator()
                + userMessage.strip() + System.lineSeparator() + System.lineSeparator();
    }

    private static String appendBeforeMarker(String content, String note) {
        int markerIndex = content.indexOf(BOOTSTRAP_NOTES_MARKER);
        if (markerIndex < 0) {
            return content + System.lineSeparator() + note;
        }
        return content.substring(0, markerIndex) + note + content.substring(markerIndex);
    }
}
