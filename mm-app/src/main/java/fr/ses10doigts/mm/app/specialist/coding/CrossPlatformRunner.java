package fr.ses10doigts.mm.app.specialist.coding;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lance un processus CLI en subprocess de façon cross-platform.
 */
@Component
@Slf4j
public class CrossPlatformRunner {

    private static final Charset PROCESS_CHARSET = Charset.forName("UTF-8");

    /**
     * Résout le chemin absolu d'un binaire selon l'OS courant.
     *
     * @param binaryName nom simple du binaire ou chemin configuré
     * @return chemin absolu si le binaire est trouvé
     */
    public Optional<Path> resolveBinary(String binaryName) {
        if (binaryName == null || binaryName.isBlank()) {
            return Optional.empty();
        }

        Path configuredPath = Path.of(binaryName);
        if (configuredPath.isAbsolute() || binaryName.contains("/") || binaryName.contains("\\")) {
            return Files.exists(configuredPath)
                    ? Optional.of(configuredPath.toAbsolutePath().normalize())
                    : Optional.empty();
        }

        for (String candidate : binaryCandidates(binaryName)) {
            Optional<Path> fromPath = resolveFromPath(candidate);
            if (fromPath.isPresent()) {
                return fromPath;
            }
            Optional<Path> fromCommonDirs = resolveFromCommonDirs(candidate);
            if (fromCommonDirs.isPresent()) {
                return fromCommonDirs;
            }
        }
        return Optional.empty();
    }

    /**
     * Lance le processus et capture stdout+stderr avec timeout global.
     *
     * @param binary chemin absolu du binaire
     * @param args arguments CLI
     * @param workingDir répertoire de travail cible
     * @param timeoutSeconds timeout global du processus
     * @return sortie complète et code retour ; -1 si timeout forcé
     */
    public ProcessResult run(Path binary, List<String> args, Path workingDir, int timeoutSeconds) {
        return run(binary, args, workingDir, timeoutSeconds, null);
    }

    /**
     * Lance le processus et écrit éventuellement un payload UTF-8 sur stdin.
     *
     * @param binary chemin absolu du binaire
     * @param args arguments CLI
     * @param workingDir répertoire de travail cible
     * @param timeoutSeconds timeout global du processus
     * @param stdinPayload contenu à pousser sur stdin ; {@code null} pour fermer stdin immédiatement
     * @return sortie complète et code retour ; -1 si timeout forcé
     */
    public ProcessResult run(Path binary,
                             List<String> args,
                             Path workingDir,
                             int timeoutSeconds,
                             String stdinPayload) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", buildAugmentedPath());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Process process = null;
        try {
            process = builder.start();
            writeStdin(process, stdinPayload);
            Process runningProcess = process;
            Future<String> outputFuture = executor.submit(() -> readFully(runningProcess));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String output = getOutput(outputFuture);
                return ProcessResult.builder()
                        .output(output)
                        .exitCode(-1)
                        .build();
            }

            return ProcessResult.builder()
                    .output(getOutput(outputFuture))
                    .exitCode(process.exitValue())
                    .build();
        } catch (IOException | InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Échec d'exécution du processus externe — binaire={}, workingDir={}, cause={}",
                    binary, workingDir, ex.toString());
            return ProcessResult.builder()
                    .output(ex.getMessage())
                    .exitCode(-1)
                    .build();
        } finally {
            executor.shutdownNow();
        }
    }

    private void writeStdin(Process process, String stdinPayload) throws IOException {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream(), PROCESS_CHARSET)) {
            if (stdinPayload != null) {
                writer.write(stdinPayload);
            }
        }
    }

    private String getOutput(Future<String> outputFuture) {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return "Impossible de lire la sortie du processus : " + ex.getMessage();
        }
    }

    private String readFully(Process process) throws IOException {
        try (Reader reader = new InputStreamReader(process.getInputStream(), PROCESS_CHARSET);
             StringWriter writer = new StringWriter()) {
            reader.transferTo(writer);
            return writer.toString();
        }
    }

    private Optional<Path> resolveFromPath(String candidate) {
        String pathEnv = buildAugmentedPath();
        for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry).resolve(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private Optional<Path> resolveFromCommonDirs(String candidate) {
        for (Path directory : commonSearchDirectories()) {
            Path path = directory.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private List<String> binaryCandidates(String binaryName) {
        if (!isWindows()) {
            return List.of(binaryName);
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(binaryName + ".cmd");
        candidates.add(binaryName + ".exe");
        candidates.add(binaryName + ".bat");
        candidates.add(binaryName);
        return List.copyOf(candidates);
    }

    private List<Path> commonSearchDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        if (isWindows()) {
            addIfPresent(directories, System.getenv("APPDATA"), "npm");
            addIfPresent(directories, System.getenv("LOCALAPPDATA"), "Programs", "nodejs");
            addIfPresent(directories, System.getenv("ProgramFiles"), "nodejs");
            addIfPresent(directories, System.getenv("ProgramFiles(x86)"), "nodejs");
        } else {
            directories.add(Path.of("/usr/local/bin"));
            directories.add(Path.of("/usr/bin"));
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                directories.add(Path.of(home, ".npm", "bin"));
                directories.add(Path.of(home, ".local", "bin"));
            }
        }
        return List.copyOf(directories);
    }

    private void addIfPresent(Set<Path> directories, String root, String... children) {
        if (root == null || root.isBlank()) {
            return;
        }
        directories.add(Path.of(root, children));
    }

    private String buildAugmentedPath() {
        List<String> entries = new ArrayList<>();
        String currentPath = System.getenv("PATH");
        if (currentPath != null && !currentPath.isBlank()) {
            entries.add(currentPath);
        }
        for (Path directory : commonSearchDirectories()) {
            entries.add(directory.toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
