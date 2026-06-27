# Prompt — Implémentation E3-M1 Marcel Maestro

Tu prends en charge le milestone **E3-M1** du projet **Marcel Maestro**. L'objectif est simple mais central : `POST /conversations/{id}/messages` doit enfin appeler le LLM et retourner une réponse. Aujourd'hui cet endpoint stocke le message utilisateur dans la mémoire JDBC mais ne répond rien. **Commence par lire tous les fichiers listés avant d'écrire la moindre ligne de code.**

---

## Contexte projet

**Localisation :** `D:\Documents\Spring\Marcel-Maestro`

**Structure Maven multi-modules :**
- `mm-core` — noyau pur Java. **Aucune dépendance Spring, JPA, ou infra. Le litmus `maven-enforcer` l'enforce.**
- `mm-spring-boot-starter` — implémentations des ports, entités JPA, autoconfiguration
- `mm-app` — services applicatifs, contrôleurs REST, `@SpringBootApplication`

**Règles de codage** : lire `D:\Documents\Spring\CODING_RULES.md`, puis `docs/coding_rules.md`. Lombok pour les nouvelles classes, `@Slf4j`, JavaDoc sur toutes les méthodes publiques, `log.info` sur chaque étape significative.

**E3-M0 est terminé.** Les corrections suivantes sont déjà en place :
- `TelegramSessionService` maintient le `conversationId` actif par `chatId`
- `chatMemory.clear()` est appelé à la suppression d'une conversation ou d'un projet
- `ProjectSystemPromptExtension` injecte le nom et workspace du projet courant dans le system prompt via `SystemPromptComposer`

---

## Fichiers à lire impérativement avant de coder

```text
docs/coding_rules.md
docs/evolution-3-conversation/conception.md          ← vision E3, ADR-026 à ADR-031

# Conversation — état actuel (ce que tu vas modifier)
mm-app/src/main/java/fr/ses10doigts/mm/app/conversation/ConversationService.java
mm-app/src/main/java/fr/ses10doigts/mm/app/rest/ConversationController.java
mm-app/src/main/java/fr/ses10doigts/mm/app/rest/dto/AddMessageRequest.java
mm-app/src/main/java/fr/ses10doigts/mm/app/rest/dto/MessageResponse.java
mm-app/src/main/java/fr/ses10doigts/mm/app/conversation/ConversationTitleService.java

# Références Spring AI déjà utilisées dans le projet
mm-app/src/main/java/fr/ses10doigts/mm/app/config/LlmConfiguration.java
mm-app/src/main/java/fr/ses10doigts/mm/app/specialist/CortexFactory.java

# Prompt — contrat à étendre
mm-core/src/main/java/fr/ses10doigts/mm/core/prompt/SystemPromptComposer.java
mm-core/src/main/java/fr/ses10doigts/mm/core/prompt/SystemPromptExtension.java
mm-spring-boot-starter/src/main/java/fr/ses10doigts/mm/starter/chat/MmChatMemoryAutoConfiguration.java

# Tests existants — comprendre les patterns de mock LLM
mm-app/src/test/java/fr/ses10doigts/mm/app/conversation/ConversationTitleServiceTest.java
mm-app/src/test/java/fr/ses10doigts/mm/app/conversation/ConversationServiceTest.java
mm-core/src/test/java/fr/ses10doigts/mm/core/engine/support/ScriptedChatModel.java
```

---

## Ce que E3-M1 doit livrer

### 1. Bean `ChatAgent` dans `mm-app`

Créer `mm-app/.../conversation/ChatAgent.java` — un `@Service` Spring qui encapsule l'appel LLM conversationnel.

```java
public String chat(String conversationId, String userMessage) { ... }
```

**Mécanisme :**
- Utiliser le `ChatClient` Spring AI injecté, le même bean que `ConversationTitleService`
- Utiliser `MessageChatMemoryAdvisor` ou l'équivalent Spring AI disponible dans la version du projet, configuré avec `conversationId` comme clé mémoire
- **Ne pas appeler manuellement `chatMemory.add()` dans `ChatAgent`** : l'advisor gère déjà l'écriture des messages USER et ASSISTANT
- Appliquer le system prompt Marcel via `SystemPromptComposer`
- Retourner le contenu textuel de la réponse
- Ajouter des `log.info` d'entrée et de sortie

### 2. System prompt Marcel

Définir le system prompt dans la configuration (`application.yml` / `application-template.yml`) sous la clé `mm.chat.system-prompt`, avec valeur par défaut dans le code.

Le prompt doit :
- présenter Marcel comme un assistant de développement Java/Spring
- préciser qu'il s'exprime en français
- indiquer qu'il répond directement, discute d'architecture et analyse du code
- mentionner que les actions concrètes seront exécutées plus tard via le système de tâches, pas encore câblé en M1
- rester concis et direct

Le `ChatAgent` doit composer ce prompt statique avec les extensions dynamiques déjà apportées par `SystemPromptComposer`.

### 3. `ConversationService.chat()`

Ajouter `chat(String conversationId, String content)` avec la séquence suivante :

1. Vérifier que la conversation existe via `getConversation(conversationId)`
2. Déterminer si c'est le premier message avant l'appel LLM via `chatMemory.get(conversationId)`
3. Déléguer à `ChatAgent.chat(conversationId, content)`
4. Si c'est le premier message, déclencher `ConversationTitleService.generateTitle()`
5. Retourner la réponse texte du `ChatAgent`

**Conserver `addMessage()`** tel quel. Il sert encore de point de compatibilité pour les tests E2.

### 4. `ConversationController` — changement de sémantique

`POST /projects/{pId}/conversations/{id}/messages` doit passer :
- d'un `201 Created` sans corps
- à un `200 OK` avec la réponse de Marcel

Corps attendu :

```json
{"role": "ASSISTANT", "content": "Bonjour ! Comment puis-je t'aider ?"}
```

Réutiliser `MessageResponse` si possible en lui ajoutant le champ `role`, sinon créer un DTO dédié si c'est plus cohérent avec l'existant.

### 5. Tests

Utiliser le même pattern de mock `ChatClient` que dans `ConversationTitleServiceTest`.

Cas à couvrir :
- réponse retournée par HTTP
- persistance du message ASSISTANT après `chat()`
- historique rechargé sur deux appels successifs
- titre déclenché uniquement au premier message
- isolation entre deux `conversationId`
- non-régression sur `GET /conversations/{id}/messages`

---

## Critères de succès

```bash
mvn verify
mvn -pl mm-core test
```

- `POST /conversations/{id}/messages` retourne `200 OK` avec `{"role": "ASSISTANT", "content": "..."}`
- `SPRING_AI_CHAT_MEMORY` contient des messages USER et ASSISTANT
- `GET /conversations/{id}/messages` retourne l'historique complet
- le system prompt conserve l'injection du nom de projet apportée en M0
- aucun import infra n'entre dans `mm-core`

---

## Points d'attention

**Doublon mémoire à éviter :** `addMessage()` écrit déjà manuellement dans `chatMemory`, alors que `MessageChatMemoryAdvisor` le fait aussi. `ConversationService.chat()` ne doit donc pas appeler `addMessage()`.

**SystemPromptComposer vs prompt inline :** ne pas injecter un prompt Marcel en dur dans `.system("...")` si cela contourne `SystemPromptComposer` et casse `ProjectSystemPromptExtension`.

**API Spring AI exacte :** vérifier la signature réelle de `MessageChatMemoryAdvisor` dans la version présente dans le projet. Si la classe a changé de nom, utiliser l'équivalent officiel disponible sur le classpath.

**Poser la question si :**
- le contrat de `SystemPromptComposer` ne permet pas de faire proprement ce qui est demandé
- l'API Spring AI observée diffère du comportement attendu
- une meilleure alternative d'architecture apparaît et mérite arbitrage

---

*Projet : Marcel Maestro — E3-M1 — 2026-06-27*
