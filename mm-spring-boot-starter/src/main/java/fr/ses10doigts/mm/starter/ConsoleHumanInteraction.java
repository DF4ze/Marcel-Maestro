package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.starter.hitl.CancellableHumanInteraction;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Implémentation console du port {@link fr.ses10doigts.mm.core.hitl.HumanInteraction}
 * (étape 4, livrable 4 — enrichi étape 8 pour la coexistence multi-canal).
 *
 * <p>Première implémentation concrète d'un port du noyau :</p>
 * <ul>
 *   <li>{@link #ask(HitlRequest)} — affiche la question sur {@code stdout}, lit la
 *       décision sur {@code stdin} en <strong>lecture bloquante</strong> (compatible
 *       Java 21 + virtual threads). Boucle tant que l'entrée n'est pas une valeur valide
 *       de {@link ConsentDecision}.</li>
 *   <li>{@link #notify(AgentNotification)} — affiche la notification sur {@code stdout}.</li>
 * </ul>
 *
 * <p>Les flux {@code in} et {@code out} sont injectables pour faciliter les tests.</p>
 *
 * <p><strong>Note multi-canal</strong> : {@link #cancelPendingAsk()} positionne le flag
 * d'annulation vérifié entre les lectures. Quand {@code System.in} est utilisé (console
 * réelle), le thread est bloqué sur {@code scanner.nextLine()} ; l'annulation prend effet
 * après que l'utilisateur a appuyé sur Entrée. En configuration mono-canal (cas habituel
 * en développement), cela n'a aucun impact.</p>
 */
@Slf4j
public class ConsoleHumanInteraction implements CancellableHumanInteraction {

    private static final String SEPARATOR = "─".repeat(60);

    private final Scanner scanner;
    private final PrintStream out;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Constructeur par défaut : stdin / stdout.
     */
    public ConsoleHumanInteraction() {
        this(System.in, System.out);
    }

    /**
     * Constructeur injectable (tests).
     *
     * @param in  flux d'entrée
     * @param out flux de sortie
     */
    public ConsoleHumanInteraction(InputStream in, PrintStream out) {
        this.scanner = new Scanner(in);
        this.out = out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lecture <strong>bloquante</strong> sur stdin — fonctionne dans tous les
     * environnements (terminal, IntelliJ, Maven spring-boot:run, …). Aucun polling
     * par {@code InputStream.available()} qui peut se comporter de façon erratique sur
     * Windows ou dans les consoles IDE.</p>
     */
    @Override
    public ConsentDecision ask(HitlRequest request) {
        cancelled.set(false);

        out.println();
        out.println(SEPARATOR);
        out.printf("  [HITL — %s]%n", request.riskLevel());
        out.printf("  %s%n", request.question());
        out.println(SEPARATOR);
        out.println("  Options : ALLOW_ONCE | ALLOW_SESSION | ALLOW_PROJECT | ALLOW_ALWAYS | DENY");
        out.print("  > ");
        out.flush();

        log.info("Console ask() — en attente de réponse HITL (riskLevel={})", request.riskLevel());

        while (true) {
            if (cancelled.get()) {
                out.println();
                out.println("  [Réponse reçue sur un autre canal — attente console annulée]");
                log.info("Console ask() — annulé par un autre canal");
                return ConsentDecision.DENY;
            }

            try {
                if (!scanner.hasNextLine()) {
                    log.info("Console ask() — fin de flux détectée, retour DENY");
                    return ConsentDecision.DENY;
                }
                String line = scanner.nextLine().trim().toUpperCase();
                if (cancelled.get()) {
                    // Annulation reçue pendant la lecture — la décision tapée est ignorée
                    out.println("  [Annulé — réponse reçue sur un autre canal]");
                    log.info("Console ask() — annulé pendant la lecture, retour DENY");
                    return ConsentDecision.DENY;
                }
                if (line.isEmpty()) {
                    out.print("  > ");
                    out.flush();
                    continue;
                }
                try {
                    ConsentDecision decision = ConsentDecision.valueOf(line);
                    log.info("Console ask() — décision reçue : {}", decision);
                    return decision;
                } catch (IllegalArgumentException e) {
                    out.printf("  Entrée invalide '%s'. Valeurs acceptées : "
                            + "ALLOW_ONCE, ALLOW_SESSION, ALLOW_PROJECT, ALLOW_ALWAYS, DENY%n", line);
                    out.print("  > ");
                    out.flush();
                }
            } catch (IllegalStateException e) {
                // Scanner fermé (flux injecté en test ou stdin fermé)
                log.info("Console ask() — scanner fermé, retour DENY");
                return ConsentDecision.DENY;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancelPendingAsk() {
        cancelled.set(true);
        log.debug("Console ask() — annulation demandée");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notify(AgentNotification notification) {
        out.printf("[%s] %s — %s%n",
                notification.level(),
                notification.title(),
                notification.message());
    }
}
