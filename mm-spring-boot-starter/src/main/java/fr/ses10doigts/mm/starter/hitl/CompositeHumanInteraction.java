package fr.ses10doigts.mm.starter.hitl;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Multiplexeur multi-canal du port {@link HumanInteraction} (étape 8).
 *
 * <p>Agrège plusieurs adaptateurs (Console, Telegram, …) et coordonne leur usage :</p>
 * <ul>
 *   <li>{@link #notify(AgentNotification)} — broadcast à tous les canaux. Une erreur
 *       sur un canal n'empêche pas les autres.</li>
 *   <li>{@link #ask(HitlRequest)} — selon la configuration {@code mm.hitl.primary-channel} :
 *       <ul>
 *         <li>{@code race} (défaut) : tous les canaux reçoivent la demande en parallèle,
 *             le premier à répondre gagne, les autres sont annulés via
 *             {@link CancellableHumanInteraction#cancelPendingAsk()}.</li>
 *         <li>nom d'un canal : seul ce canal reçoit la demande.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Quand un seul canal est enregistré, le Composite se comporte comme un passthrough
 * transparent — zéro overhead.</p>
 */
@Slf4j
public class CompositeHumanInteraction implements HumanInteraction {

    private final List<HumanInteraction> channels;
    private final String primaryChannel;
    private final ExecutorService raceExecutor;

    /**
     * Construit un Composite avec les canaux fournis.
     *
     * @param channels       liste ordonnée des canaux enregistrés (non vide)
     * @param primaryChannel mode de sélection pour ask() : "race" ou nom du canal
     */
    public CompositeHumanInteraction(List<HumanInteraction> channels, String primaryChannel) {
        this.channels = List.copyOf(channels);
        this.primaryChannel = primaryChannel;
        this.raceExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mm-hitl-race-", 0).factory());
        log.info("CompositeHumanInteraction initialisé — {} canal/aux, mode ask()={}",
                this.channels.size(), primaryChannel);
        for (HumanInteraction ch : this.channels) {
            log.debug("  Canal enregistré : {}", ch.getClass().getSimpleName());
        }
    }

    /**
     * Broadcast la notification à tous les canaux enregistrés.
     * Une erreur sur un canal est loguée mais n'empêche pas les autres.
     *
     * @param notification notification à pousser
     */
    @Override
    public void notify(AgentNotification notification) {
        for (HumanInteraction channel : channels) {
            try {
                channel.notify(notification);
            } catch (Exception e) {
                log.info("Erreur notify() sur {} : {}",
                        channel.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * Demande une validation humaine selon le mode configuré.
     *
     * <p>En mode {@code race} : lance tous les canaux en parallèle, retourne la
     * première réponse, annule les canaux perdants. En mode nommé : délègue au
     * canal correspondant (fallback sur le premier canal si le nom ne matche pas).</p>
     *
     * @param request demande de consentement
     * @return décision du premier canal à répondre
     */
    @Override
    public ConsentDecision ask(HitlRequest request) {
        if (channels.size() == 1) {
            return channels.get(0).ask(request);
        }

        if ("race".equalsIgnoreCase(primaryChannel)) {
            return raceAsk(request);
        }

        // Mode canal nommé : chercher par nom de classe simplifié
        HumanInteraction target = findChannelByName(primaryChannel);
        if (target != null) {
            log.info("ask() délégué au canal primaire : {}", target.getClass().getSimpleName());
            return target.ask(request);
        }

        log.info("Canal '{}' introuvable — fallback sur race", primaryChannel);
        return raceAsk(request);
    }

    /**
     * Lance le {@code ask()} sur tous les canaux en parallèle. Le premier à répondre
     * gagne ; les perdants sont annulés via {@link CancellableHumanInteraction#cancelPendingAsk()}.
     *
     * @param request demande de consentement
     * @return décision du canal le plus rapide
     */
    private ConsentDecision raceAsk(HitlRequest request) {
        log.info("ask() en mode race — {} canaux en compétition", channels.size());

        List<CompletableFuture<ChannelResult>> futures = new ArrayList<>(channels.size());

        for (HumanInteraction channel : channels) {
            CompletableFuture<ChannelResult> future = CompletableFuture.supplyAsync(
                    () -> new ChannelResult(channel, channel.ask(request)),
                    raceExecutor);
            futures.add(future);
        }

        // Attendre le premier résultat
        CompletableFuture<Object> anyOf = CompletableFuture.anyOf(
                futures.toArray(CompletableFuture[]::new));

        try {
            ChannelResult winner = (ChannelResult) anyOf.get();
            log.info("ask() race gagnée par {} — décision : {}",
                    winner.channel().getClass().getSimpleName(), winner.decision());

            // Annuler les perdants
            cancelLosers(winner.channel());

            return winner.decision();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("ask() race interrompue — retour DENY");
            cancelAll();
            return ConsentDecision.DENY;
        } catch (ExecutionException e) {
            log.info("ask() race en erreur — retour DENY : {}", e.getCause().getMessage());
            cancelAll();
            return ConsentDecision.DENY;
        }
    }

    /**
     * Annule les canaux perdants après qu'un gagnant a été déterminé.
     *
     * @param winner le canal gagnant (à ne pas annuler)
     */
    private void cancelLosers(HumanInteraction winner) {
        for (HumanInteraction channel : channels) {
            if (channel != winner && channel instanceof CancellableHumanInteraction cancellable) {
                try {
                    cancellable.cancelPendingAsk();
                    log.debug("Canal {} annulé après victoire de {}",
                            channel.getClass().getSimpleName(),
                            winner.getClass().getSimpleName());
                } catch (Exception e) {
                    log.debug("Erreur lors de l'annulation de {} : {}",
                            channel.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Annule tous les canaux (en cas d'erreur globale).
     */
    private void cancelAll() {
        for (HumanInteraction channel : channels) {
            if (channel instanceof CancellableHumanInteraction cancellable) {
                try {
                    cancellable.cancelPendingAsk();
                } catch (Exception e) {
                    log.debug("Erreur lors de l'annulation de {} : {}",
                            channel.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Recherche un canal par nom simplifié (insensible à la casse).
     * Matche sur le nom simple de la classe (ex: "console" matche "ConsoleHumanInteraction").
     *
     * @param name nom à chercher
     * @return le canal trouvé, ou {@code null}
     */
    private HumanInteraction findChannelByName(String name) {
        for (HumanInteraction channel : channels) {
            String simpleName = channel.getClass().getSimpleName().toLowerCase();
            if (simpleName.contains(name.toLowerCase())) {
                return channel;
            }
        }
        return null;
    }

    /**
     * Résultat d'un canal dans la course ask().
     */
    private record ChannelResult(HumanInteraction channel, ConsentDecision decision) {}
}
