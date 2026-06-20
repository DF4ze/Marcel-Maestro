package fr.ses10doigts.mm.starter.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.journal.JournalEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Implémentation JSONL append-only du port {@link Journal}.
 *
 * <p>Chaque entrée est sérialisée en une ligne JSON et écrite dans un fichier
 * organisé par agent et par jour : {@code {basePath}/{agentId}/{yyyy-MM-dd}.jsonl}.
 * Les données sensibles sont masquées avant écriture (PB-10).</p>
 *
 * <p>Thread-safe : un verrou par chemin de fichier évite les écritures concurrentes
 * sur un même fichier sans bloquer les écritures vers d'autres agents/jours.</p>
 */
@Slf4j
public class FileJournal implements Journal {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "token|password|secret|apiKey|api_key|credential|private_key",
            Pattern.CASE_INSENSITIVE);

    private static final String REDACTED = "***REDACTED***";

    private final ObjectMapper objectMapper;
    private final Path basePath;
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    /**
     * Construit un {@link FileJournal}.
     *
     * @param objectMapper mapper Jackson pour la sérialisation JSON
     * @param basePath     répertoire racine du journal
     */
    public FileJournal(ObjectMapper objectMapper, Path basePath) {
        this.objectMapper = objectMapper;
        this.basePath = basePath;
    }

    /**
     * Ajoute une entrée au journal. L'entrée est sérialisée en JSON, les données
     * sensibles sont masquées, et la ligne est ajoutée au fichier correspondant.
     *
     * @param entry entrée de journal à persister
     * @throws UncheckedIOException si l'écriture échoue
     */
    @Override
    public void append(JournalEntry entry) {
        String dateStr = DATE_FMT.format(entry.at());
        Path dir = basePath.resolve(entry.agentId());
        Path file = dir.resolve(dateStr + ".jsonl");
        String fileKey = file.toAbsolutePath().toString();

        // Sanitisation PB-10 : deep-clone + masquage des clés sensibles
        Map<String, Object> sanitizedData = sanitize(entry.data());
        JournalEntry sanitized = new JournalEntry(
                entry.at(), entry.agentId(), entry.taskId(), entry.category(), sanitizedData);

        Object lock = fileLocks.computeIfAbsent(fileKey, k -> new Object());

        synchronized (lock) {
            try {
                boolean newFile = !Files.exists(file);
                if (newFile) {
                    Files.createDirectories(dir);
                    log.info("Journal : création du fichier {}", file);
                }

                String line = objectMapper.writeValueAsString(sanitized) + System.lineSeparator();
                Files.writeString(file, line,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);

                log.debug("Journal append [{}] agent={}, task={}",
                        entry.category(), entry.agentId(), entry.taskId());
            } catch (IOException e) {
                throw new UncheckedIOException("Échec d'écriture journal : " + file, e);
            }
        }
    }

    /**
     * Deep-clone la map {@code data} en masquant les valeurs dont la clé correspond
     * à un pattern sensible (PB-10). Récursif pour les maps imbriquées.
     *
     * @param data données à assainir (peut être {@code null})
     * @return copie assainie, ou {@code null} si l'entrée est {@code null}
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> sanitize(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>(data.size());
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (SENSITIVE_KEY_PATTERN.matcher(key).find()) {
                result.put(key, REDACTED);
            } else if (value instanceof Map) {
                result.put(key, sanitize((Map<String, Object>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }
}
