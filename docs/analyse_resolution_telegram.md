# Analyse de résolution — « DISPATCHER ABSENT » sur message Telegram

> Objectif : qu'un simple message Telegram déclenche la boucle agentique jusqu'à
> une réponse renvoyée dans Telegram.
> Symptôme : `Telegram chat — DISPATCHER ABSENT. Vérifie que CortexFactory,
> AgentLoop et ChatClient sont bien câblés. TelegramHI=absent`

---

## 1. Verdict en une phrase

`LlmConfiguration` était une **`@Configuration` ordinaire** portant
`@ConditionalOnBean(ChatClient.Builder.class)`. Une `@Configuration` scannée est
évaluée **avant** les auto-configurations Spring AI : au moment du test, le
`ChatClient.Builder` n'existe pas encore → la condition est **fausse** → **aucun
`ChatClient` n'est créé**, et toute la chaîne en aval s'effondre jusqu'au
`Dispatcher`. Correctif : passer `LlmConfiguration` en **`@AutoConfiguration`**
ordonnée, et l'enregistrer dans le fichier `.imports`.

> Note honnête : ma première hypothèse (« jar périmé ») était fausse — ton
> `mvn clean install` reconstruit bien tout. Le vrai problème est ce conditionnel
> runtime, pas un artefact obsolète.

---

## 2. D'où vient le message

`TelegramMmController.chat(...)` :

```java
Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
if (dispatcher == null) {
    log.warn("Telegram chat — DISPATCHER ABSENT. ... TelegramHI={}", ...);
    return "⚠️ Le moteur agent n'est pas démarré (aucune AgentFactory configurée).";
}
```

Le contrôleur reçoit ton message (le poller marche) mais le bean `Dispatcher` est
`null`. Reste à comprendre pourquoi.

---

## 3. La chaîne de câblage — effondrement en cascade

Chaque maillon n'existe que si le précédent existe (`@ConditionalOnBean`). C'est
voulu (démarrage « vert » sans clé LLM), mais **un seul maillon manquant éteint
toute la chaîne** :

```
spring-ai-starter-model-openai            (dépendance Maven — présente ✅)
        ▼  autoconfig Spring AI
ChatClient.Builder                        (ChatClientAutoConfiguration, Spring AI)
        ▼  @ConditionalOnBean(ChatClient.Builder.class)
ChatClient                                ← LlmConfiguration   ⛔ MAILLON CASSÉ
        ▼  @ConditionalOnBean(ChatClient.class)
AgentLoop                                 (MmCoreAutoConfiguration)
        ▼  @ConditionalOnBean(AgentLoop.class)
CortexFactory / EchoSpecialist            (AppAgentsAutoConfiguration)
        ▼  @ConditionalOnBean(AgentFactory.class)
Dispatcher                                (DispatcherAutoConfiguration)
        ▼
TelegramMmController.chat()  →  Dispatcher == null  →  « DISPATCHER ABSENT »
```

Le `ChatClient` ne se crée jamais, donc tout le reste (`AgentLoop`,
`CortexFactory`, `Dispatcher`) reste absent. D'où les trois suspects listés dans
le warning.

---

## 4. Le mécanisme précis du bug (le cœur du sujet)

Deux faits Spring Boot se combinent :

1. **Ordre d'évaluation.** Les classes `@Configuration` trouvées par
   `@ComponentScan` (donc `LlmConfiguration`, dans `fr.ses10doigts.mm.app.config`)
   sont traitées **tôt**, *avant* les auto-configurations. Les beans Spring AI
   (`ChatClient.Builder`) viennent d'une **auto-configuration**, enregistrée
   **plus tard**. Donc quand `@ConditionalOnBean(ChatClient.Builder.class)` est
   testé sur `LlmConfiguration.chatClient()`, le builder n'est pas encore là → la
   condition échoue silencieusement. C'est le piège que la doc Spring résume par :
   « n'utilisez jamais `@ConditionalOnBean` hors d'une auto-configuration ».

2. **`AutoConfigurationExcludeFilter`.** `@SpringBootApplication` installe un
   filtre qui **retire du component-scan toute `@Configuration` listée dans**
   `META-INF/spring/...AutoConfiguration.imports`. C'est exactement ce qui fait
   fonctionner `AppAgentsAutoConfiguration` (qui est pourtant dans le package
   scanné `...app.config`) : comme elle est dans le fichier `.imports`, elle n'est
   PAS scannée tôt, mais enregistrée tard comme auto-config, avec le bon ordre.

`LlmConfiguration`, elle, était un `@Configuration` **absent du `.imports`** →
scannée tôt → bug. L'équipe avait appliqué ce correctif à `EchoSpecialist` et
`AppAgents` (cf. leurs javadocs), mais l'avait **oublié sur `LlmConfiguration`**.

---

## 5. Le correctif appliqué

**Fichier `LlmConfiguration.java`** — passage de `@Configuration` à
`@AutoConfiguration` ordonnée :

```java
@AutoConfiguration(
        afterName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
        before = MmCoreAutoConfiguration.class)
@Slf4j
public class LlmConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatClient.Builder.class)
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

- `afterName = "...ChatClientAutoConfiguration"` : garantit que le bean est évalué
  **après** que Spring AI a enregistré le `ChatClient.Builder` (référence par nom
  pour ne pas dépendre d'une classe interne au compile).
- `before = MmCoreAutoConfiguration.class` : garantit que le `ChatClient` existe
  **avant** que `MmCoreAutoConfiguration.agentLoop()`
  (`@ConditionalOnBean(ChatClient.class)`) soit évalué.

**Fichier `...AutoConfiguration.imports`** — enregistrement (et donc exclusion du
scan via `AutoConfigurationExcludeFilter`) :

```
fr.ses10doigts.mm.app.config.LlmConfiguration
fr.ses10doigts.mm.app.config.AppAgentsAutoConfiguration
```

Ordre final des auto-configs :
`ChatClientAutoConfiguration (Spring AI)` → `LlmConfiguration` →
`MmCoreAutoConfiguration` → `AppAgentsAutoConfiguration` → `DispatcherAutoConfiguration`.

---

## 6. Vérifier que c'est résolu

```bash
export OPENROUTER_API_KEY=sk-or-...
mvn -pl mm-app -am clean package      # ou: mvn clean install à la racine
java -jar mm-app/target/mm-app.jar
```

### a) Le rapport de câblage au démarrage doit montrer :

```
Câblage du ChatClient Cortex à partir du provider LLM configuré
CortexFactory enregistré — agent 'cortex' disponible
=== Marcel-Maestro — rapport de câblage ===
  AgentLoop   : ✅ présent
  Dispatcher  : ✅ présent
===========================================
```

### b) Diagnostic définitif si jamais ça résiste — le rapport de conditions :

```bash
java -jar mm-app/target/mm-app.jar --debug 2>&1 \
  | grep -iE "chatClient|agentLoop|dispatcher|CortexFactory"
```

Cherche dans le *CONDITIONS EVALUATION REPORT* :
- avant le correctif : `LlmConfiguration#chatClient` apparaît dans **Negative
  matches** (`@ConditionalOnBean … did not find any beans of type ChatClient.Builder`).
- après le correctif : il passe en **Positive matches**, et `chatClient`,
  `agentLoop`, `dispatcher` sont créés.

C'est l'outil de référence pour ce genre de panne : il dit *exactement* quel bean
n'a pas matché et pourquoi.

---

## 7. Le flux complet d'un message (une fois réparé)

```
[Toi] ──msg──► Telegram ──poller──► TelegramMmController.chat()
        soumet TaskMessage(USER_REQUEST, assignee="cortex")
                  ▼
              TaskQueue ──poll──► Dispatcher ──route "cortex"──► CortexFactory.execute()
                                                                      ▼
                                                                AgentLoop.run()
                                                                  ChatClient → LLM (OpenRouter)
                                                                      ▼
                                                                AgentOutcome (DONE, output)
                  ┌───────────────────────────────────────────────────┘
                  ▼
        Dispatcher.handleOutcome() → notifyOutcome() → humanInteraction.notify()
                  ▼
        CompositeHumanInteraction (@Primary) ──fan-out──► TelegramHumanInteraction.notify()
                  ▼
[Toi] ◄── réponse du LLM ── Telegram
```

L'accusé immédiat (« ⏳ Message reçu… ») est synchrone ; la vraie réponse arrive
**asynchrone** via `notify()` à la clôture de la tâche. Le canal de notification
résout bien vers Telegram grâce au `CompositeHumanInteraction` marqué `@Primary`.

---

## 8. Mécanismes Spring à retenir (volet pédagogique)

- **`@ConditionalOnBean` est sensible à l'ordre.** Il ne « voit » que les beans
  déjà enregistrés au moment de son évaluation. Dans une `@Configuration` scannée,
  les beans d'auto-configuration n'existent pas encore → condition fausse. Règle :
  **`@ConditionalOnBean`/`@ConditionalOnMissingBean` uniquement dans des
  `@AutoConfiguration`.**

- **`@AutoConfiguration` vs `@Configuration`.** L'`@AutoConfiguration` est
  enregistrée tardivement et ordonnable (`after/before`). C'est *le* bon outil dès
  qu'on conditionne sur un bean fourni par une autre librairie (ici Spring AI).

- **`AutoConfigurationExcludeFilter`.** Lister une classe dans le fichier
  `.imports` la retire automatiquement du component-scan : pas de double
  enregistrement, et l'ordre « auto-config » est respecté. C'est pour ça que des
  auto-configs peuvent vivre dans le package de l'app sans casser.

- **`ObjectProvider<T>` + `@Primary`.** Injection optionnelle (`getIfAvailable()`
  rend `null` au lieu de planter) ; `@Primary` lève l'ambiguïté quand plusieurs
  beans du même type existent (ici, le `CompositeHumanInteraction` est l'unique
  cible d'injection pour `HumanInteraction`).

---

## 9. Hygiène à corriger ensuite (hors blocage)

1. **Commiter** `AppAgentsAutoConfiguration.java`, `CortexFactory.java`, le dossier
   `META-INF/spring/` et le `LlmConfiguration.java` corrigé : plusieurs sont encore
   non suivis par git → absents de la CI et de tout build reproductible.
2. **Donner une valeur par défaut** au placeholder de clé : `api-key:
   ${OPENROUTER_API_KEY:}` — sinon, clé absente = l'app ne démarre pas du tout,
   contrairement à la « dégradation gracieuse » annoncée par le README.
3. **Réflexe diagnostic** : pour toute panne de câblage Spring, lancer une fois
   avec `--debug` et lire le *CONDITIONS EVALUATION REPORT* avant de chercher
   ailleurs.
