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
import lombok.extern.slf4j.Slf4j;
import fr.ses10doigts.mm.starter.hitl.HitlChannelProperties;
import fr.ses10doigts.mm.starter.hitl.PersistentConsentCache;
import fr.ses10doigts.mm.starter.journal.FileJournal;
import fr.ses10doigts.mm.starter.journal.JournalProperties;
import fr.ses10doigts.mm.starter.memory.JpaMemoryStore;
import fr.ses10doigts.mm.starter.memory.MemoryEntryRepository;
import fr.ses10doigts.mm.starter.prompt.ToolsSystemPromptExtension;
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
 * <p>Cable les composants purs de la boucle agentique, la couche HITL,
 * la memoire factuelle et le systeme d'outils.</p>
 *
 * <p>L'AgentLoop n'est cree que si un ChatClient est present : sans provider
 * LLM, la boucle n'est pas cablee et le demarrage reste vert.</p>
 */
@AutoConfiguration
@EnableJpaRepositories(basePackages = "fr.ses10doigts.mm.starter.memory")
@EntityScan(basePackages = "fr.ses10doigts.mm.starter.memory")
@EnableConfigurationProperties({JournalProperties.class, HitlChannelProperties.class})
@Slf4j
public class MmCoreAutoConfiguration {

    /** ObjectMapper partage. Conditionnel : Spring Boot fournit deja le sien. */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** Bean temoin de demarrage. */
    @Bean
    @ConditionalOnMissingBean
    public CoreLoadedBanner coreLoadedBanner() {
        return new CoreLoadedBanner();
    }

    // composants purs boucle agentique

    /** @return composeur de system prompt aggregeant les extensions hote */
    @Bean
    @ConditionalOnMissingBean
    public SystemPromptComposer systemPromptComposer(ObjectProvider<SystemPromptExtension> extensions) {
        return new SystemPromptComposer(extensions.orderedStream().toList());
    }

    /** @return parseur de reponse LLM avec ObjectMapper par defaut */
    @Bean
    @ConditionalOnMissingBean
    public AgentResponseParser agentResponseParser() {
        return new AgentResponseParser();
    }

    /** @return machine a etats sans etat, sûre a partager */
    @Bean
    @ConditionalOnMissingBean
    public AgentStateMachine agentStateMachine() {
        return new AgentStateMachine();
    }

    /** @return parametres de bornage par defaut (25/3/5) */
    @Bean
    @ConditionalOnMissingBean
    public LoopConfig loopConfig() {
        return LoopConfig.defaults();
    }

    // memoire factuelle

    /** @return implementation JPA du port MemoryStore */
    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    public JpaMemoryStore jpaMemoryStore(MemoryEntryRepository repository) {
        return new JpaMemoryStore(repository);
    }

    /** @return outil de capture explicite de faits par l'agent */
    @Bean
    @ConditionalOnMissingBean(RememberFactTool.class)
    public RememberFactTool rememberFactTool(MemoryStore memoryStore) {
        return new RememberFactTool(memoryStore);
    }

    // HITL

    /**
     * Canal console. Desactivable via {@code mm.hitl.console.enabled=false}.
     *
     * @return adaptateur console (stdin/stdout)
     */
    @Bean("consoleHumanInteraction")
    @ConditionalOnMissingBean(ConsoleHumanInteraction.class)
    @ConditionalOnProperty(prefix = "mm.hitl.console", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ConsoleHumanInteraction consoleHumanInteraction() {
        return new ConsoleHumanInteraction();
    }

    /**
     * Composite multi-canal. Marque Primary pour les injections de HumanInteraction.
     *
     * @param allChannels       tous les canaux declares
     * @param channelProperties proprietes mm.hitl.*
     * @return composite pret
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(CompositeHumanInteraction.class)
    public CompositeHumanInteraction compositeHumanInteraction(
            List<HumanInteraction> allChannels,
            HitlChannelProperties channelProperties) {
        log.info("MmCoreAutoConfiguration — assemblage CompositeHumanInteraction, {} bean(s) HumanInteraction détecté(s)",
                allChannels.size());
        allChannels.forEach(ch ->
                log.debug("  HumanInteraction candidat : {}", ch.getClass().getSimpleName()));
        List<HumanInteraction> channels = allChannels.stream()
                .filter(ch -> !(ch instanceof CompositeHumanInteraction))
                .toList();
        log.debug("  → {} canal/aux retenu(s) après filtrage du Composite lui-même", channels.size());
        return new CompositeHumanInteraction(channels, channelProperties.getPrimaryChannel());
    }

    /** @return politique HITL par defaut (seuil MEDIUM) */
    @Bean
    @ConditionalOnMissingBean
    public HitlPolicy hitlPolicy() {
        return HitlPolicy.defaults();
    }

    /**
     * Cache de consentement avec persistance SQLite.
     *
     * @param memoryStore store sous-jacent
     * @param repository  acces JPA pour le rechargement initial
     * @return cache pret (decisions pre-chargees)
     */
    @Bean
    @ConditionalOnMissingBean(ConsentCache.class)
    public PersistentConsentCache persistentConsentCache(MemoryStore memoryStore,
                                                         MemoryEntryRepository repository) {
        PersistentConsentCache cache = new PersistentConsentCache(memoryStore, repository);
        cache.loadFromStore();
        return cache;
    }

    /**
     * Garde-fou HITL. Cable uniquement si un canal HumanInteraction existe.
     *
     * @param policy      politique de seuil de risque
     * @param cache       cache de decisions
     * @param interaction canal humain (primaire = Composite)
     * @return garde initialise
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(HumanInteraction.class)
    public HitlGuard hitlGuard(HitlPolicy policy, ConsentCache cache,
                               HumanInteraction interaction) {
        return new HitlGuard(policy, cache, interaction);
    }

    // journal

    /**
     * Journal JSONL append-only par agent/jour.
     *
     * @param objectMapper      serialiseur JSON
     * @param journalProperties configuration mm.journal.*
     * @return journal pret
     */
    @Bean
    @ConditionalOnMissingBean(Journal.class)
    public FileJournal fileJournal(ObjectMapper objectMapper, JournalProperties journalProperties) {
        return new FileJournal(objectMapper, Path.of(journalProperties.getBasePath()));
    }

    // outils

    /**
     * Validateur de chemins anti path-traversal.
     *
     * @param workspaceRoot racine autorisee (defaut ./workspace)
     * @return validateur
     */
    @Bean
    @ConditionalOnMissingBean
    public PathValidator pathValidator(
            @Value("${mm.workspace.root:./workspace}") String workspaceRoot) {
        return new PathValidator(Path.of(workspaceRoot));
    }

    /**
     * Garde d'execution transverse : HITL + path validation + timeout.
     *
     * @param hitlGuard     garde HITL optionnel
     * @param pathValidator validateur de chemins optionnel
     * @return garde configure
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionGuard toolExecutionGuard(
            ObjectProvider<HitlGuard> hitlGuard,
            ObjectProvider<PathValidator> pathValidator) {
        return new ToolExecutionGuard(
                hitlGuard.getIfAvailable(), pathValidator.getIfAvailable());
    }

    /**
     * Registre central des outils. Collecte tous les AgentTool beans du contexte.
     *
     * @param tools tous les AgentTool beans (peuvent etre absents)
     * @return registre peuple
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ObjectProvider<AgentTool> tools) {
        return new ToolRegistry(tools.orderedStream().toList());
    }

    /**
     * Extension du system prompt listant les outils disponibles avec leurs noms exacts.
     *
     * <p>Indispensable : le LLM produit des {@code tool_calls} en JSON pur (pas de function
     * calling natif). Sans ce catalogue, il invente des noms ({@code create_file},
     * {@code file_creation}…) qui échouent avec "outil inconnu" dans le registre.</p>
     *
     * @param tools tous les AgentTool beans du contexte
     * @return extension injectée dans SystemPromptComposer
     */
    @Bean
    @ConditionalOnMissingBean(ToolsSystemPromptExtension.class)
    public ToolsSystemPromptExtension toolsSystemPromptExtension(ObjectProvider<AgentTool> tools) {
        List<AgentTool> toolList = tools.orderedStream().toList();
        log.info("ToolsSystemPromptExtension — {} outil(s) exposé(s) au LLM", toolList.size());
        toolList.forEach(t -> log.info("  → outil LLM : {} [{}]", t.name(), t.riskLevel()));
        return new ToolsSystemPromptExtension(toolList);
    }

    // AgentLoop

    /**
     * Boucle agentique principale. Cablee uniquement si un ChatClient existe.
     * Journal, HumanInteraction, ToolRegistry et ToolExecutionGuard sont optionnels.
     *
     * @param chatClient         client LLM Spring AI
     * @param promptComposer     composeur de system prompt
     * @param parser             parseur de reponses
     * @param stateMachine       machine a etats
     * @param loopConfig         parametres de bornage
     * @param journal            journal optionnel
     * @param humanInteraction   canal HITL optionnel
     * @param toolRegistry       registre d'outils optionnel
     * @param toolExecutionGuard garde d'execution optionnel
     * @return boucle agentique prete
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
