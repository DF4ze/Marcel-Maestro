# Prompt agent — E3-M6 : Streaming SSE + lien conversation↔tâches

## Contexte du projet

Tu travailles sur **Marcel Maestro**, un moteur agentique Java 21 / Spring Boot 3, organisé en
3 modules Maven :

- `mm-core` — noyau pur Java (zéro dépendance Spring/JPA — règle absolue, enforcer Maven actif)
- `mm-spring-boot-starter` — entités JPA, repositories, autoconfiguration
- `mm-app` — application Spring Boot, contrôleurs REST, services, Telegram

DB : SQLite + Flyway. ORM : Spring Data JPA. Pas de WebFlux dans le projet — Spring MVC pur.
Spring AI 1.1.x est utilisé (`ChatClient`, `JdbcChatMemory`, `MessageChatMemoryAdvisor`).

**Virtual threads actifs** (`spring.threads.virtual.enabled=true`) — les opérations bloquantes
dans des threads virtuels sont le pattern normal du projet.

---

## Ce qui existe — lis ces fichiers en priorité

```
mm-app/src/main/java/fr/ses10doigts/mm/app/
  conversation/ChatAgent.java              ← chat() batch avec .call().content()
  conversation/ConversationService.java    ← chat(conversationId, content) → String
  rest/ConversationController.java         ← POST /{id}/messages retourne MessageResponse (batch)
  telegram/TelegramMmController.java       ← @Chat handler — BUG identifié (voir §1)

mm-spring-boot-starter/src/main/resources/db/migration/
  V3__conversations_and_chat_memory.sql    ← dernier script Flyway en place
```

---


---

## 1. Streaming SSE sur `POST /conversations/{id}/messages`

### 1.1 Principe

Spring AI `.stream()` retourne un `Flux<String>`. Comme le projet n'utilise pas WebFlux,
le SSE passe par `SseEmitter` (Spring MVC) consommé dans un virtual thread.

Le endpoint doit supporter les deux modes selon l'en-tête `Accept` :
- `Accept: application/json` (ou absent) → comportement batch actuel, 200 JSON (rétrocompat)
- `Accept: text/event-stream` → streaming SSE

### 1.2 `ChatAgent` — méthode streamante

Ajouter une méthode `stream()` à `ChatAgent` :

```java
public Flux<String> stream(String conversationId, String userMessage) {
    return chatClient.prompt()
            .advisors(spec -> spec
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .param(ChatMemory.CONVERSATION_ID, conversationId))
            .system(promptComposer.compose())
            .tools(this)
            .user(userMessage)
            .stream()
            .content();  // Flux<String> — tokens au fil de l'eau
}
```

`MessageChatMemoryAdvisor` persiste automatiquement le message ASSISTANT complet à la fin
du flux (Spring AI 1.1.x) — pas de persistance manuelle nécessaire.

### 1.3 `ConversationService` — méthode streamante

```java
public Flux<String> chatStream(String conversationId, String content) {
    ConversationEntity conversation = getConversation(conversationId);
    // Même logique de contexte que chat() : AgentContext, ProjectBootstrapService, titre
    // Retourner chatAgent.stream(conversationId, content)
}
```

Gérer le titre de conversation (premier message) avant de retourner le Flux — pas dans un
callback asynchrone sur le flux pour éviter les race conditions.

### 1.4 `ConversationController` — endpoint SSE

```java
@PostMapping(value = "/{conversationId}/messages",
             produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamMessage(
        @PathVariable String projectId,
        @PathVariable String conversationId,
        @RequestBody AddMessageRequest request) {

    SseEmitter emitter = new SseEmitter(180_000L); // 3 min timeout

    Thread.startVirtualThread(() -> {
        try {
            Flux<String> tokenFlux = conversationService.chatStream(conversationId, request.content());
            tokenFlux.toStream().forEach(token -> {
                try {
                    emitter.send(SseEmitter.event().data(token));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE stream error - conversationId={}", conversationId, e);
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

**Note Spring MVC** : quand deux méthodes ont le même `@PostMapping` mais des `produces`
différents, Spring route automatiquement selon le `Accept` header. Si le batch existant est
déjà en `@PostMapping`, ajouter `produces = MediaType.APPLICATION_JSON_VALUE` dessus pour
être explicite.

---

## 2. Table `conversation_task` — lien conversation ↔ tâches

### 2.1 Migration Flyway V4

Créer `mm-spring-boot-starter/src/main/resources/db/migration/V4__conversation_task.sql` :

```sql
-- V4 — Lien conversation ↔ tâches soumises via submit_task (E3-M6)
-- task_id est une référence faible : les tâches vivent en mémoire (TaskQueue), pas en DB.
CREATE TABLE conversation_task (
    id              TEXT PRIMARY KEY,           -- UUID v4
    conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    task_id         TEXT NOT NULL,              -- UUID du TaskMessage (non FK)
    submitted_at    TEXT NOT NULL,              -- ISO-8601
    status          TEXT NOT NULL DEFAULT 'RUNNING'  -- RUNNING | DONE | KO
);

CREATE INDEX idx_conversation_task_conv ON conversation_task (conversation_id);
```

### 2.2 Entité + Repository (`mm-spring-boot-starter`)

```java
@Entity @Table(name = "conversation_task")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConversationTaskEntity {
    @Id @Column(nullable = false) private String id;
    @Column(name = "conversation_id", nullable = false) private String conversationId;
    @Column(name = "task_id", nullable = false) private String taskId;
    @Column(name = "submitted_at", nullable = false) private String submittedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @Builder.Default private ConversationTaskStatus status = ConversationTaskStatus.RUNNING;
}

public enum ConversationTaskStatus { RUNNING, DONE, KO }

public interface ConversationTaskRepository extends JpaRepository<ConversationTaskEntity, String> {
    List<ConversationTaskEntity> findAllByConversationIdOrderBySubmittedAtDesc(String conversationId);
}
```

### 2.3 Hook dans `ChatAgent.submitTask()`

Après `taskQueue.submit(taskMessage)` dans `ChatAgent.submitTask()`, enregistrer le lien :

```java
conversationTaskRepository.save(ConversationTaskEntity.builder()
        .id(UUID.randomUUID().toString())
        .conversationId(currentContext.conversationId())
        .taskId(taskId)
        .submittedAt(Instant.now().toString())
        .status(ConversationTaskStatus.RUNNING)
        .build());
```

Injecter `ConversationTaskRepository` dans `ChatAgent`.

---

## 3. Endpoint `GET /conversations/{id}/tasks`

Dans `ConversationController` :

```java
@GetMapping("/{conversationId}/tasks")
public List<ConversationTaskResponse> tasks(
        @PathVariable String projectId,
        @PathVariable String conversationId) {
    conversationService.getConversation(conversationId); // 404 si absent
    return conversationTaskRepository
            .findAllByConversationIdOrderBySubmittedAtDesc(conversationId)
            .stream()
            .map(ConversationTaskResponse::from)
            .toList();
}

public record ConversationTaskResponse(
    String id, String conversationId, String taskId,
    String submittedAt, String status
) { ... }
```

---

## 4. Règles de codage obligatoires

- **Lombok** : `@Slf4j`, `@RequiredArgsConstructor`, `@Builder`, etc.
- **Logging** : `log.info` à l'ouverture et fermeture de chaque flux SSE, à chaque `submit_task` persisté. `log.debug` sur les tokens si nécessaire (guard `log.isDebugEnabled()`).
- **JavaDoc** : toute méthode publique et protégée.
- **`mm-core` pur** : `ConversationTaskRepository` et `ConversationTaskEntity` dans `mm-spring-boot-starter`, pas dans `mm-core`.
- **`application-template.yml`** : répercuter tout ajout dans `application.yml`.

---

## 5. Tests

### 5.1 JUnit

**Rapides :**
- `ChatAgentStreamTest` : `stream()` retourne un `Flux` non vide sur un `ChatModel` mocké
- `ConversationTaskPersistenceTest` : `submitTask` crée bien un `ConversationTaskEntity`

**Lents (`@Tag("slow")` + `@SpringBootTest`) :**
- `ConversationSseIntegrationTest` : `POST /{id}/messages` avec `Accept: text/event-stream`
  → réponse de type SSE, événement `[DONE]` en fin de flux
- `ConversationTaskEndpointTest` : après un `submit_task`, `GET /{id}/tasks` retourne l'entrée

⚠️ **Build Maven très lent** : prévoir **10 à 15 minutes** pour `mvn verify`.
Tout test `@SpringBootTest` doit porter `@Tag("slow")` et `@Timeout(value = 10, unit = TimeUnit.MINUTES)`.

### 5.2 Plan de test Postman

Créer `docs/evolution-3-conversation/postman-e3-m6.json`.

**Dossier 1 — Setup**
1. `POST /projects` — créer projet `"Test E3-M6"`
2. `POST /projects/{id}/conversations` — créer une conversation

**Dossier 2 — Fix Telegram (vérification via REST)**
3. `POST /projects/{id}/conversations/{convId}/messages` — body `{"content": "Bonjour Marcel"}`
   → vérifier que la réponse est une vraie réponse LLM (pas un "tâche soumise")

**Dossier 3 — SSE**
4. `POST /projects/{id}/conversations/{convId}/messages`
   Header `Accept: text/event-stream`
   → vérifier que la réponse est en `text/event-stream`, tokens arrivant progressivement,
   dernier événement `[DONE]`
5. `GET /projects/{id}/conversations/{convId}/messages`
   → vérifier que le message ASSISTANT est bien persisté après le stream

**Dossier 4 — conversation_task**
6. `POST /{convId}/messages` avec une demande qui déclenche `submit_task`
   (ex : "Lance un build Maven du projet")
7. `GET /projects/{id}/conversations/{convId}/tasks`
   → vérifier qu'une entrée est présente avec `status = "RUNNING"`

**Dossier 5 — Nettoyage** _(toujours en dernier)_
8. `DELETE /projects/{id}/conversations/{convId}`
9. `DELETE /projects/{id}`

### 5.3 Plan de test manuel Telegram

1. Envoie "Bonjour Marcel, comment vas-tu ?" dans Telegram.
   → Marcel doit répondre directement en texte libre, **sans message "tâche soumise"**.
2. Envoie "Lance un build Maven sur ce projet."
   → Marcel doit répondre en indiquant qu'il soumet la tâche, puis la tâche tourne en arrière-plan.
3. Via SQLite : `SELECT * FROM conversation_task ORDER BY submitted_at DESC LIMIT 5;`
   → vérifier la présence de l'entrée.
4. Envoie plusieurs messages courts à la suite.
   → Vérifier que l'historique se construit correctement (pas de doublons, bon ordre).

---

## 6. Points ouverts

- **Mise à jour du statut `conversation_task`** : quand une tâche se termine (DONE/KO),
  la table n'est pas mise à jour automatiquement. La mise à jour au retour de la notification
  `HumanInteraction` est hors scope de ce milestone — à traiter en E4 si nécessaire.
- **Throttle SSE / timeout réseau** : si le LLM est lent (> 3 min), le client HTTP peut
  fermer la connexion avant la fin du flux. Le timeout `SseEmitter` est mis à 3 min —
  ajuster si nécessaire via `${mm.chat.sse.timeout-ms:180000}` dans `application.yml`.
- **Compatibilité Postman SSE** : Postman >= 10.18 supporte le streaming SSE nativement.
  Si la version installée est plus ancienne, utiliser `curl` :
  `curl -N -H "Accept: text/event-stream" -X POST -H "Content-Type: application/json" \`
  `-d '{"content":"Bonjour"}' http://localhost:8080/projects/{pId}/conversations/{id}/messages`

---

## 7. Livrable attendu

1. **Fix Telegram validé manuellement** : Marcel répond en texte conversationnel depuis Telegram.
2. `POST /conversations/{id}/messages` avec `Accept: text/event-stream` → flux SSE fonctionnel.
3. Rétrocompatibilité batch : sans `Accept: text/event-stream`, le endpoint répond en JSON comme avant.
4. Table `conversation_task` créée et alimentée à chaque `submit_task`.
5. `GET /conversations/{id}/tasks` opérationnel.
6. Tous les tests passent, collection Postman présente, non-régression E3-M0 à M5.
