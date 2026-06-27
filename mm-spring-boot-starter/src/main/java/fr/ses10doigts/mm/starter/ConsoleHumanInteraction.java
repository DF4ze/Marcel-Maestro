package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.starter.hitl.CancellableHumanInteraction;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;
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
        request.question().lines().forEach(line -> out.printf("  %s%n", line));
        out.println();
        out.printf("  UF = Une fois              DY = Refuser%n");
        if (request.strictScopeLabel() != null) {
            String sl = truncate(request.strictScopeLabel(), 40);
            out.printf("  SS / SP / SA = Stricte    — %s  (conv / proj / ∞)%n", sl);
        }
        if (request.localScopeLabel() != null) {
            out.printf("  LS / LP / LA = Local      — %s  (conv / proj / ∞)%n",
                    request.localScopeLabel());
        }
        String toolLbl = request.toolName() != null ? request.toolName() : "outil";
        out.printf("  XS / XP / XA = Large      — %s  (conv / proj / ∞)%n", toolLbl);
        out.println(SEPARATOR);
        out.print("  > ");
        out.flush();

        log.info("Console ask() — en attente de réponse HITL (riskLevel={}) :\n  {}",
                request.riskLevel(),
                request.question().replace("\n", "\n  "));

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
                ConsentDecision decision = parseDecision(line);
                if (decision != null) {
                    log.info("Console ask() — décision reçue : {}", decision);
                    return decision;
                } else {
                    out.println("  Entrée invalide. Codes : UF DY | SS SP SA | LS LP LA | XS XP XA");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Table des codes courts et des noms complets acceptés en entrée console. */
    private static final Map<String, ConsentDecision> SHORTCODES = Map.ofEntries(
            Map.entry("UF", ConsentDecision.ALLOW_ONCE),
            Map.entry("DY", ConsentDecision.DENY),
            Map.entry("SS", ConsentDecision.ALLOW_STRICT_SESSION),
            Map.entry("SP", ConsentDecision.ALLOW_STRICT_PROJECT),
            Map.entry("SA", ConsentDecision.ALLOW_STRICT_ALWAYS),
            Map.entry("LS", ConsentDecision.ALLOW_LOCAL_SESSION),
            Map.entry("LP", ConsentDecision.ALLOW_LOCAL_PROJECT),
            Map.entry("LA", ConsentDecision.ALLOW_LOCAL_ALWAYS),
            Map.entry("XS", ConsentDecision.ALLOW_LARGE_SESSION),
            Map.entry("XP", ConsentDecision.ALLOW_LARGE_PROJECT),
            Map.entry("XA", ConsentDecision.ALLOW_LARGE_ALWAYS)
    );

    /**
     * Parse une entrée console (code court ou nom enum complet) en {@link ConsentDecision}.
     *
     * @param input entrée de l'utilisateur (déjà en majuscules)
     * @return la décision, ou {@code null} si l'entrée est invalide
     */
    private static ConsentDecision parseDecision(String input) {
        ConsentDecision fromCode = SHORTCODES.get(input);
        if (fromCode != null) return fromCode;
        try {
            return ConsentDecision.valueOf(input);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Tronque un texte à {@code maxLen} caractères.
     *
     * @param text   texte à tronquer
     * @param maxLen longueur maximale
     * @return texte tronqué avec "…" si nécessaire
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : "…" + text.substring(text.length() - (maxLen - 1));
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
