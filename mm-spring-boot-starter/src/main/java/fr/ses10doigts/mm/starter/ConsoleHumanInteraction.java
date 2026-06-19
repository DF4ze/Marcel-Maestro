package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Scanner;

/**
 * Implémentation console du port {@link HumanInteraction} (étape 4, livrable 4).
 *
 * <p>Première implémentation concrète d'un port du noyau :</p>
 * <ul>
 *   <li>{@link #ask(HitlRequest)} — affiche la question sur {@code stdout}, lit la
 *       décision sur {@code stdin}. Boucle tant que l'entrée n'est pas une valeur valide
 *       de {@link ConsentDecision}.</li>
 *   <li>{@link #notify(AgentNotification)} — affiche la notification sur {@code stdout}.</li>
 * </ul>
 *
 * <p>Enregistré comme bean par défaut dans {@link MmCoreAutoConfiguration} avec
 * {@code @ConditionalOnMissingBean} : tout consommateur (mm-app, futur module Telegram)
 * peut le remplacer en déclarant son propre bean {@link HumanInteraction}.</p>
 *
 * <p>Les flux {@code in} et {@code out} sont injectables pour faciliter les tests.</p>
 */
public class ConsoleHumanInteraction implements HumanInteraction {

    private static final String SEPARATOR = "─".repeat(60);

    private final Scanner scanner;
    private final PrintStream out;

    /**
     * Constructeur par défaut : stdin / stdout.
     */
    public ConsoleHumanInteraction() {
        this(System.in, System.out);
    }

    /**
     * Constructeur injectable (tests).
     */
    public ConsoleHumanInteraction(InputStream in, PrintStream out) {
        this.scanner = new Scanner(in);
        this.out = out;
    }

    @Override
    public ConsentDecision ask(HitlRequest request) {
        out.println();
        out.println(SEPARATOR);
        out.printf("  [HITL — %s]%n", request.riskLevel());
        out.printf("  %s%n", request.question());
        out.println(SEPARATOR);
        out.println("  Options : ALLOW_ONCE | ALLOW_SESSION | ALLOW_PROJECT | ALLOW_ALWAYS | DENY");

        while (true) {
            out.print("  > ");
            out.flush();
            if (!scanner.hasNextLine()) {
                // stdin fermé (pipe, EOF) → refuser par sécurité
                out.println("  [stdin fermé — décision par défaut : DENY]");
                return ConsentDecision.DENY;
            }
            String line = scanner.nextLine().trim().toUpperCase();
            if (line.isEmpty()) {
                continue;
            }
            try {
                return ConsentDecision.valueOf(line);
            } catch (IllegalArgumentException e) {
                out.printf("  Entrée invalide '%s'. Valeurs acceptées : "
                        + "ALLOW_ONCE, ALLOW_SESSION, ALLOW_PROJECT, ALLOW_ALWAYS, DENY%n", line);
            }
        }
    }

    @Override
    public void notify(AgentNotification notification) {
        out.printf("[%s] %s — %s%n",
                notification.level(),
                notification.title(),
                notification.message());
    }
}
