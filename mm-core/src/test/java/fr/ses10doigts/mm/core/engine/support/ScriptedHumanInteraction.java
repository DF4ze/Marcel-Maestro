package fr.ses10doigts.mm.core.engine.support;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Implémentation scriptée de {@link HumanInteraction} pour les tests (étape 4).
 *
 * <p>On empile des décisions pré-définies ; quand la pile est vide, la dernière décision
 * est répétée (même logique que {@link ScriptedChatModel}). Enregistre les requêtes et
 * notifications reçues pour assertion.</p>
 */
public final class ScriptedHumanInteraction implements HumanInteraction {

    private final Deque<ConsentDecision> decisions = new ArrayDeque<>();
    private final List<HitlRequest> receivedRequests = new ArrayList<>();
    private final List<AgentNotification> receivedNotifications = new ArrayList<>();
    private ConsentDecision last = ConsentDecision.DENY;

    /**
     * Empile une ou plusieurs décisions qui seront retournées dans l'ordre.
     */
    public ScriptedHumanInteraction respond(ConsentDecision... ds) {
        for (ConsentDecision d : ds) {
            decisions.add(d);
        }
        return this;
    }

    @Override
    public ConsentDecision ask(HitlRequest request) {
        receivedRequests.add(request);
        if (!decisions.isEmpty()) {
            last = decisions.poll();
        }
        return last;
    }

    @Override
    public void notify(AgentNotification notification) {
        receivedNotifications.add(notification);
    }

    /** Nombre d'appels à {@code ask()}. */
    public int askCount() {
        return receivedRequests.size();
    }

    /** Nombre d'appels à {@code notify()}. */
    public int notifyCount() {
        return receivedNotifications.size();
    }

    /** Requêtes reçues (pour assertions détaillées). */
    public List<HitlRequest> requests() {
        return receivedRequests;
    }

    /** Notifications reçues (pour assertions détaillées). */
    public List<AgentNotification> notifications() {
        return receivedNotifications;
    }
}
