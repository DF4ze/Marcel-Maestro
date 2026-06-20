package fr.ses10doigts.mm.starter;

import fr.ses10doigts.mm.core.engine.AgentLoop;
import fr.ses10doigts.mm.core.engine.AgentStateMachine;
import fr.ses10doigts.mm.core.engine.LoopConfig;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.hitl.HitlPolicy;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.ToolExecutionGuard;
import fr.ses10doigts.mm.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.starter.hitl.CompositeHumanInteraction;
import fr.ses10doigts.mm.starter.hitl.HitlChannelProperties;
import fr.ses10doigts.mm.starter.hitl.PersistentConsentCache;
import fr.ses10doigts.mm.starter.journal.FileJournal;
import fr.ses10doigts.mm.starter.journal.JournalProperties;
import fr.ses10doigts.mm.starter.memory.JpaMemoryStore;
import fr.ses10doigts.mm.starter.memory.MemoryEntryRepository;
import fr.ses10doigts.mm.starter.tool.RememberFactTool;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Autoconfiguration du noyau Marcel Maestro.
 *
 * <p>Câble les composants <em>purs et déterministes</em> de la boucle agentique
 * ({@link SystemPromptComposer}, {@link AgentResponseParser}, {@link AgentStateMachine},
 * {@link LoopConfig}), la couche HITL (étape 4 : {@link HitlPolicy}, {@link ConsentCache},
 * {@link HitlGuard}, {@link ConsoleHumanInteraction}), la mémoire factuelle (étape 5 :
 * {@link JpaMemoryStore}, {@link PersistentConsentCache}, {@link RememberFactTool}) et le
 * système d'outils (étape 6 : {@link ToolRegistry}, {@link ToolExecutionGuard},
 * {@link PathValidator}).</p>
 *
 * <p>L'{@link AgentLoop} n'est créé que si un {@link ChatClient} est présent
 * ({@code @ConditionalOnBean}) : sans provider LLM, la boucle n'est pas câblée et le
 * démarrage reste vert.</p>
 *
 * <p>Aucun bean concret de LLM n'est créé ici : le {@link ChatClient} est fourni par le
 * starter de provider Spring AI choisi par l'hôte.</p>
 */
@AutoConfiguration
@EnableJpaRepositories(basePackages = "fr.ses10doigts.mm.starter.memory")
@EntityScan(basePackages = "fr.ses10doigts.mm.starter.memory")
@EnableConfigurationProperties({JournalProperties.class, HitlChannelProperties.class})
public class MmCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnMissingBean
    public CoreLoadedBanner coreLoadedBanner() {
        return new CoreLoadedBanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemPromptComposer systemPromptComposer(ObjectProvider<SystemPromptExtension> extensions) {
        List<SystemPromptExtension> ordered = extensions.orderedStream().toList();
        return new SystemPromptComposer(ordered);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentResponseParser agentResponseParser() {
        return new AgentResponseParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentStateMachine agentStateMachine() {
        return new AgentStateMachine();
    }

    @Bean
    @ConditionalOnMissingBean
    public LoopConfig loopConfig() {
        return LoopConfig.defaults();
    }

    // ── Mémoire factuelle (étape 5) ────────────────────────────────────

    /**
     * Implémentation JPA/SQLite du port {@link MemoryStore}. Vit dans le starter,
     * jamais dans mm-core (litmus de pureté).
     */
    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    public JpaMemoryStore jpaMemoryStore(MemoryEntryRepository repository) {
        return new JpaMemoryStore(repository);
    }

    /**
     * Outil « retiens ceci » — capture explicite de faits par l'agent.
     * Prêt pour le registre d'outils de l'étape 6.
     */
    @Bean
    @ConditionalOnMissingBean(RememberFactTool.class)
    public RememberFactTool rememberFactTool(MemoryStore memoryStore) {
        return new RememberFactTool(memoryStore);
    }

    // ── HITL (étape 4 + persistance étape 5) ────────────────────────────

    /**
     * Canal console du port {@link HumanInteraction}. Toujours créé comme canal
     * individuel ; le Composite l'agrège avec les autres canaux éventuels (Telegram…).
     */
    @Bean("consoleHumanInteraction")
    @ConditionalOnMissingBean(ConsoleHumanInteraction.class)
    @ConditionalOnProperty(prefix = "mm.hitl.console", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ConsoleHumanInteraction consoleHumanInteraction() {
        return new ConsoleHumanInteraction();
    }

    /**
     * Composite multi-canal (étape 8). Agrège tous les beans {@link HumanInteraction}
     * (Console, Telegram…) et coordonne notify() (broadcast) et ask() (race ou canal
     * nommé selon {@code mm.hitl.primary-channel}).
     *
     * <p>Marqué {@code @Primary} pour être injecté dans le {@link HitlGuard} et
     * l'{@link AgentLoop} à la place des canaux individuels.</p>
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(CompositeHumanInteraction.class)
    public CompositeHumanInteraction compositeHumanInteraction(
            List<HumanInteraction> allChannels,
            HitlChannelProperties channelProperties) {
        // Filtrer le Composite lui-même pour éviter la récursion
        List<HumanInteraction> channels = allChannels.stream()
                .filter(ch -> !(ch instanceof CompositeHumanInteraction))
                .toList();
        return new CompositeHumanInteraction(channels, channelProperties.getPrimaryChannel());
    }

    /**
     * Politique HITL par défaut : seuil {@code MEDIUM}.
     */
    @Bean
    @ConditionalOnMissingBean
    public HitlPolicy hitlPolicy() {
        return HitlPolicy.defaults();
    }

    /**
     * Cache de consentement avec persistance SQLite (étape 5, livrable 3).
     * Remplace le {@link ConsentCache} in-memory de l'étape 4 : les décisions
     * {@code ALLOW_PROJECT}/{@code ALLOW_ALWAYS} survivent au redémarrage.
     */
    @Bean
    @ConditionalOnMissingBean(ConsentCache.class)
    public PersistentConsentCache consentCache(MemoryStore memoryStore,
                                               MemoryEntryRepository repository) {
        PersistentConsentCache cache = new PersistentConsentCache(memoryStore, repository);
        cache.loadFromStore();
        return cache;
    }

    /**
     * Garde-fou HITL : décide quand demander un consentement. Câblé uniquement si un
     * {@link HumanInteraction} est disponible.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(HumanInteraction.class)
    public HitlGuard hitlGuard(HitlPolicy policy, ConsentCache cache,
                               HumanInteraction interaction) {
        return new HitlGuard(policy, cache, interaction);
    }

    // ── Journal (étape 8) ──────────────────────────────────────────────

    /**
     * Journal JSONL append-only par agent/jour. Sert l'audit et le debug.
     */
    @Bean
    @ConditionalOnMissingBean(Journal.class)
    public FileJournal fileJournal(ObjectMapper objectMapper, JournalProperties journalProperties) {
        return new FileJournal(objectMapper, Path.of(journalProperties.getBasePath()));
    }

    // ── Outils (étape 6) ───────────────────────────────────────────────

    /**
     * Validateur de chemins anti path-traversal. Le workspace racine est configurable
     * via {@code mm.workspace.root} (défaut {@code ./workspace}).
     */
    @Bean
    @ConditionalOnMissingBean
    public PathValidator pathValidator(
            @Value("${mm.workspace.root:./workspace}") String workspaceRoot) {
        return new PathValidator(Path.of(workspaceRoot));
    }

    /**
     * Garde d'exécution transverse : HITL + path validation + timeout.
     * Le {@link HitlGuard} est optionnel (absent = pas de consentement).
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionGuard toolExecutionGuard(
            ObjectProvider<HitlGuard> hitlGuard,
            ObjectProvider<PathValidator> pathValidator) {
        return new ToolExecutionGuard(hitlGuard.getIfAvailable(), pathValidator.getIfAvailable());
    }

    /**
     * Registre central des outils déclarés. Collecte tous les beans {@link AgentTool}
     * du contexte Spring. L'hôte enregistre ses outils comme beans ; le registre
     * les rend disponibles au moteur.
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ObjectProvider<AgentTool> tools) {
        List<AgentTool> toolList = tools.orderedStream().toList();
        return new ToolRegistry(toolList);
    }

    // ── AgentLoop ───────────────────────────────────────────────────────

    /**
     * Câblé uniquement si un {@link ChatClient} existe (provider LLM configuré). Le
     * {@link Journal}, le {@link HumanInteraction}, le {@link ToolRegistry} et le
     * {@link ToolExecutionGuard} sont optionnels.
     */
    @Bean
    @ConditionalOnBean(ChatClient.class)
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(ChatClient chatClient,
                               SystemPromptComposer promptComposer,
                               AgentResponseParser parser,
                               AgentStateMachine stateMachine,
                               LoopConfig loopConfig,
                               ObjectProvider<Journal> journal,
                               ObjectProvider<HumanInteraction> humanInteraction,
                               ObjectProvider<ToolRegistry> toolRegistry,
                               ObjectProvider<ToolExecutionGuard> toolExecutionGuard) {
        return new AgentLoop(chatClient, promptComposer, parser, stateMachine,
                loopConfig, journal.getIfAvailable(), humanInteraction.getIfAvailable(),
                toolRegistry.getIfAvailable(), toolExecutionGuard.getIfAvailable());
    }
}
