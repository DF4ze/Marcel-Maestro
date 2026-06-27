# Plan de test — E2-M2 : Conversations + JdbcChatMemory

## Prérequis

- `mm-app` démarré (`mvn spring-boot:run` ou jar)
- Base `data/mm-memory.db` initialisée par Flyway (V1 → V3)
- Port : `http://localhost:8080`
- Import Postman : `docs/postman-e2-m2.json`

---

## T1 — Démarrage & migration Flyway

| # | Action | Résultat attendu |
|---|--------|-----------------|
| T1.1 | Démarrer l'application | Log `MmChatMemoryAutoConfiguration — JdbcChatMemoryRepository câblé, dialecte=SqliteChatMemoryRepositoryDialect` |
| T1.2 | Vérifier les logs Flyway | `Successfully applied 3 migrations` (V1 memory_entry, V2 projects, V3 conversations) |
| T1.3 | Ouvrir `data/mm-memory.db` (DB Browser for SQLite ou équivalent) | Tables `conversation` et `SPRING_AI_CHAT_MEMORY` présentes |
| T1.4 | Vérifier colonnes `SPRING_AI_CHAT_MEMORY` | `conversation_id TEXT`, `content TEXT`, `type TEXT`, `timestamp INTEGER` |

---

## T2 — CRUD conversations (nominal)

> Utiliser le dossier **"T2 — Conversations"** de la collection Postman.
> Les requêtes capturent automatiquement `projectId` et `conversationId` dans les variables de collection.

| # | Requête Postman | Résultat attendu |
|---|----------------|-----------------|
| T2.1 | `[Setup] Créer projet ACTIVE` | 201 · `id` capturé dans `{{projectId}}` |
| T2.2 | `Démarrer conversation` | 201 · `id` capturé dans `{{conversationId1}}`, `status: "OPEN"` |
| T2.3 | `Lister conversations du projet` | 200 · tableau avec 1 entrée |
| T2.4 | `Démarrer 2e conversation` | 201 · `id` capturé dans `{{conversationId2}}` |
| T2.5 | `Lister conversations du projet` (replay) | 200 · tableau avec **2** entrées |
| T2.6 | `Détail conversation 1` | 200 · `projectId` = celui du projet créé en T2.1 |
| T2.7 | `Détail conversation — ID inconnu` | 404 · `{"error": "Conversation introuvable : ..."}` |

---

## T3 — Messages & isolation mémoire

> Dossier **"T3 — Messages"**. Dépend de T2 (variables `projectId`, `conversationId1`, `conversationId2`).

| # | Requête Postman | Résultat attendu |
|---|----------------|-----------------|
| T3.1 | `Ajouter message dans conv1` (`"Bonjour depuis conv1"`) | 201 |
| T3.2 | `Ajouter 2e message dans conv1` (`"Second message conv1"`) | 201 |
| T3.3 | `Ajouter message dans conv2` (`"Bonjour depuis conv2"`) | 201 |
| T3.4 | `Lire messages conv1` | 200 · **2 messages**, types `USER`, textes corrects |
| T3.5 | `Lire messages conv2` | 200 · **1 message** uniquement — isolation ✓ |
| T3.6 | `Ajouter message vide` (`""`) | 400 · `{"error": "Le champ 'content' est obligatoire..."}` |
| T3.7 | `Lire messages — conversationId inconnu` | 404 |

**Vérification isolation dans la DB :**  
Après T3.1→T3.3, dans DB Browser :
```sql
SELECT conversation_id, COUNT(*) FROM SPRING_AI_CHAT_MEMORY GROUP BY conversation_id;
```
→ 2 lignes : conv1 = 2, conv2 = 1.

---

## T4 — Rejet projet archivé

> Dossier **"T4 — Archivage"**.

| # | Requête Postman | Résultat attendu |
|---|----------------|-----------------|
| T4.1 | `[Setup] Créer projet pour archivage` | 201 · capturé dans `{{projectIdArchived}}` |
| T4.2 | `Archiver le projet` | 200 · `status: "ARCHIVED"` |
| T4.3 | `Démarrer conversation sur projet archivé` | **409 Conflict** · `{"error": "Impossible de démarrer une conversation : le projet est archivé..."}` |

---

## T5 — Persistance après redémarrage

| # | Action | Résultat attendu |
|---|--------|-----------------|
| T5.1 | Effectuer T3.1 → T3.3 (si pas déjà fait) | — |
| T5.2 | **Arrêter** l'application (`Ctrl+C`) | — |
| T5.3 | **Redémarrer** l'application | Flyway log : `0 migrations applied` (schéma déjà à jour) |
| T5.4 | Rejouer `Lire messages conv1` (T3.4) | **2 messages toujours présents** — persistance JDBC ✓ |
| T5.5 | Rejouer `Lire messages conv2` (T3.5) | **1 message toujours présent** |

> C'est le test le plus important de E2-M2 : il valide que `JdbcChatMemoryRepository` persiste bien en SQLite (vs `InMemoryChatMemoryRepository` qui perdrait tout au redémarrage).

---

## T6 — Non-régression E2-M1

> Vérifier que les projets restent opérationnels.

| # | Requête Postman | Résultat attendu |
|---|----------------|-----------------|
| T6.1 | `GET /projects` | 200 · projets créés en T2 et T4 présents |
| T6.2 | `GET /projects?status=ARCHIVED` | 200 · projet T4 présent |
| T6.3 | `DELETE /projects/{{projectId}}` | 204 · ses conversations sont supprimées en cascade (ON DELETE CASCADE) |
| T6.4 | `GET /projects/{{projectId}}/conversations` | 404 (projet supprimé) |

**Vérification cascade en DB :**
```sql
SELECT * FROM conversation WHERE project_id = '<id supprimé>';
-- → 0 lignes
SELECT * FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id IN ('<conv1>', '<conv2>');
-- → messages toujours présents (SPRING_AI_CHAT_MEMORY n'a pas de FK — nettoyage manuel nécessaire en E2-M4)
```

---

## Résumé des critères de succès

| Critère | Test |
|---------|------|
| Flyway V3 appliquée, 2 nouvelles tables | T1 |
| `SqliteChatMemoryRepositoryDialect` auto-détecté | T1.1 log |
| REST conversations opérationnel | T2 |
| Isolation mémoire par conversationId | T3.4 vs T3.5 |
| Rejet projet archivé → 409 | T4.3 |
| Persistance redémarrage | T5.4 / T5.5 |
| Non-régression projets E2-M1 | T6 |
