package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.starter.hitl.CancellableHumanInteraction;
import java.io.IOException;
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
 *       décision sur {@code stdin}. Boucle tant que l'entrée n'est pas une valeur valide
 *       de {@link ConsentDecision}. Supporte l'annulation via
 *       {@link #cancelPendingAsk()} pour la coexistence multi-canal.</li>
 *   <li>{@link #notify(AgentNotification)} — affiche la notification sur {@code stdout}.</li>
 * </ul>
 *
 * <p>Les flux {@code in} et {@code out} sont injectables pour faciliter les tests.</p>
 */
@Slf4j
public class ConsoleHumanInteraction implements CancellableHumanInteraction {

    private static final String SEPARATOR = "─".repeat(60);
    private static final long POLL_INTERVAL_MS = 200;

    private final InputStream inputStream;
    private final Scanner scanner;
    private final PrintStream out;
    private final boolean pollConsoleInput;
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
        this.inputStream = in;
        this.scanner = new Scanner(in);
        this.out = out;
        this.pollConsoleInput = in == System.in;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Utilise un polling sur {@code stdin} avec vérification périodique du flag
     * d'annulation. Si le {@code ask()} est annulé par un autre canal (via
     * {@link #cancelPendingAsk()}), affiche un message et retourne {@code DENY}
     * (valeur ignorée par le Composite puisque l'autre canal a déjà répondu).</p>
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

        while (!cancelled.get()) {
            try {
                if (canAttemptRead()) {
                    if (!scanner.hasNextLine()) {
                        log.info("Console ask() — fin de flux détectée, retour DENY");
                        return ConsentDecision.DENY;
                    }
                    String line = scanner.nextLine().trim().toUpperCase();
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
                } else {
                    // Polling : attendre un peu avant de revérifier
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Console ask() — thread interrompu, retour DENY");
                return ConsentDecision.DENY;
            } catch (IOException e) {
                log.info("Console ask() — erreur I/O sur stdin, retour DENY");
                return ConsentDecision.DENY;
            }
        }

        // Annulé par un autre canal
        out.println();
        out.println("  [Réponse reçue sur un autre canal — attente console annulée]");
        log.info("Console ask() — annulé par un autre canal");
        return ConsentDecision.DENY;
    }

    /**
     * Détermine si une lecture de ligne peut être tentée sans bloquer indéfiniment.
     *
     * <p>Avec {@code System.in}, on reste en mode polling pour préserver l'annulation
     * multi-canal. Avec un flux injectable de test, on lit directement via le
     * {@link Scanner} afin d'éviter les faux négatifs de {@link InputStream#available()}
     * quand le scanner a déjà bufferisé les octets restants.</p>
     *
     * @return {@code true} si une tentative de lecture doit être faite maintenant
     * @throws IOException si l'état du flux ne peut pas être vérifié
     */
    private boolean canAttemptRead() throws IOException {
        return !pollConsoleInput || inputStream.available() > 0;
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
