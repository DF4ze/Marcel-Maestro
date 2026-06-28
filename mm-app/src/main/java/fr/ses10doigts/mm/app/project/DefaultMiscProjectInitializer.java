package fr.ses10doigts.mm.app.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Cree au demarrage le projet systeme par defaut "Autre" s'il est absent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultMiscProjectInitializer implements ApplicationRunner {

    private final ProjectService projectService;

    @Override
    public void run(ApplicationArguments args) {
        var project = projectService.ensureDefaultMiscProjectExists();
        log.info("Projet systeme par defaut pret - projectId={}, name='{}'",
                project.getId(), project.getName());
    }
}
