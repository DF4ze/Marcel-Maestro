## Pré-requis
1. Démarrer l'application Marcel Maestro sur `http://localhost:8080`.
2. Importer la collection Postman `docs/evolution-3-conversation/postman-e3-m2.json`.
3. Vérifier que Telegram est configuré et que `data/mm-memory.db` est accessible.

## T1 - Conversation pure (pas de tâche)
1. Dans Postman, exécuter `Setup / Créer projet`, puis `Créer conversation A`.
   Résultat attendu : `projectId` et `conversationIdA` sont mémorisés dans la collection.
2. Exécuter `T1 - Conversation pure / POST /messages - question simple`.
   Body utilisé : `{"content": "Qu'est-ce que Java 21 apporte comme nouveautés ?"}`.
3. Vérifier dans Postman : `HTTP 200`, body avec `{"role": "ASSISTANT", "content": "..."}`.
4. Vérifier dans les logs applicatifs :
   `ChatAgent démarré`, puis `ChatAgent terminé`, sans log `submit_task exécuté`.
5. Dans DB Browser SQLite, exécuter :
```sql
SELECT conversation_id, type, content
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id = '<conversationIdA>'
ORDER BY timestamp;
```
6. Vérifier : 2 lignes, dans l'ordre `USER` puis `ASSISTANT`.
7. Dans Postman, ne pas exécuter `GET /api/tasks` pour cette étape comme preuve métier.
   Résultat attendu : aucune nouvelle tâche visible côté opérateur.

## T2 - Délégation de tâche
1. Dans Postman, exécuter `T2 - Délégation de tâche / POST /messages - lancer un build Maven`.
   Body utilisé : `{"content": "Lance un build Maven du projet courant"}`.
2. Vérifier dans Postman : `HTTP 200`, body avec `role = ASSISTANT` et un message de lancement immédiat.
3. Exécuter `T2 - Délégation de tâche / GET /api/tasks - vérifier la queue`.
   Résultat attendu : `HTTP 200`, `queueSize` ou `activeTasks` reflètent la présence de la tâche selon le timing.
4. Vérifier dans les logs applicatifs :
   présence d'un log `submit_task exécuté` avec `taskId`, `projectId`, `conversationId` et le début de la description.
5. Vérifier dans Telegram :
   Marcel ne répond pas avec le résultat du build tout de suite dans la conversation REST.
   Une notification Telegram doit arriver en fin de tâche via le flux habituel du Dispatcher.
6. Dans DB Browser SQLite, exécuter :
```sql
SELECT conversation_id, type, content
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id = '<conversationIdA>'
ORDER BY timestamp;
```
7. Vérifier : l'historique contient maintenant au moins 4 lignes, avec un nouveau couple `USER` + `ASSISTANT`.

## T3 - Isolation entre deux conversations
1. Dans Postman, exécuter `Setup / Créer conversation B`.
2. Exécuter `T3 - Isolation entre conversations / POST /messages sur conversation B - autre délégation`.
   Body utilisé : `{"content": "Lance un build Maven pour cette seconde conversation"}`.
3. Vérifier dans les logs applicatifs :
   un second log `submit_task exécuté` apparaît avec un `conversationId` différent de `conversationIdA`.
4. Dans DB Browser SQLite, exécuter :
```sql
SELECT conversation_id, type, content
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id IN ('<conversationIdA>', '<conversationIdB>')
ORDER BY conversation_id, timestamp;
```
5. Vérifier :
   `conversationIdA` conserve son propre historique.
   `conversationIdB` contient uniquement ses messages à elle.
6. Vérifier via l'API REST que chaque conversation expose son historique isolé :
   `GET /projects/{projectId}/conversations/{conversationIdA}/messages`
   `GET /projects/{projectId}/conversations/{conversationIdB}/messages`

## T4 - Non-régression historique complet
1. Dans Postman, exécuter `T4 - Historique complet / GET /messages conversation A`.
2. Vérifier : `HTTP 200`, la liste contient à la fois des messages `USER` et `ASSISTANT`.
3. Vérifier que les messages initiaux de T1 sont toujours présents.
4. Vérifier dans les logs qu'aucune exception n'est levée pendant la lecture de l'historique.

## Vérification Telegram bout en bout
1. Depuis Telegram, sélectionner le bon projet avec `/switch` si nécessaire.
2. Envoyer : `Lance un build Maven du projet courant`.
3. Vérifier : Marcel accuse réception rapidement.
4. Vérifier dans les logs : `Telegram chat — réutilisation conversation active` ou création de conversation si c'est le premier message.
5. Vérifier en fin d'exécution : notification Telegram de succès ou d'erreur envoyée par le Dispatcher.

## Vérification finale
1. Exécuter `mvn verify`.
2. Exécuter `mvn -pl mm-core test`.
3. Vérifier : tous les tests sont verts et `mm-core` reste pur.
