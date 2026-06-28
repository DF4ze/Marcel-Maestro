## Pre-requis
1. Demarrer l'application Marcel Maestro sur `http://localhost:8080`.
2. Importer la collection Postman `docs/evolution-3-conversation/postman-e3-m5.json`.
3. Verifier que la base SQLite applicative est accessible.
4. Verifier que les logs applicatifs sont consultables.
5. Si tu veux derouler la partie Telegram, verifier aussi que le bot est configure et connecte.

## Ce que couvre ce plan
1. Validation REST executable maintenant :
   liste enrichie des conversations, filtres `OPEN|ARCHIVED|ALL`, renommage manuel, archivage, non-regression de la memoire.
2. Verifications manuelles complementaires :
   SQL sur `conversation` et `SPRING_AI_CHAT_MEMORY`, logs applicatifs, comportement Telegram quand les commandes M5 sont disponibles.
3. Point important :
   une conversation archivee est en lecture seule. Il ne doit pas etre possible d'y ajouter un message, ni via REST, ni via Telegram. Il n'existe pas de desarchivage.
4. Point important :
   tout message Telegram commencant par `/` est une commande systeme. Il doit etre intercepte avant tout passage au LLM, a la TaskQueue ou au flux HITL.

## Ordre recommande dans Postman
1. Executer tout le dossier `1 - Setup`.
2. Executer tout le dossier `2 - Liste enrichie`.
3. Executer tout le dossier `3 - Renommage`.
4. Executer tout le dossier `4 - Archivage`.
5. Derouler ensuite `5 - Verifications manuelles`.
6. Finir par `6 - Nettoyage`.

## T1 - Setup
1. Executer `1 - Setup / Creer projet de test`.
   Attendu : `HTTP 201`, `projectId` memorise dans la collection.
2. Executer `1 - Setup / Creer conversation A`.
   Attendu : `HTTP 201`, `conversationIdA` memorise.
3. Executer `1 - Setup / Creer conversation B`.
   Attendu : `HTTP 201`, `conversationIdB` memorise.
4. Executer `1 - Setup / Poster message dans conversation A`.
   Attendu : `HTTP 200`, body avec `role = ASSISTANT`.
5. Executer `1 - Setup / Poster message dans conversation B`.
   Attendu : `HTTP 200`, body avec `role = ASSISTANT`.

## T2 - Liste enrichie REST
1. Executer `2 - Liste enrichie / GET conversations - liste OPEN enrichie`.
2. Verifier dans Postman :
   `HTTP 200`, tableau non vide, chaque conversation expose `messageCount` et `lastMessageAt`.
3. Verifier metierement :
   `messageCount >= 2` pour les conversations ayant deja fait un aller-retour `USER + ASSISTANT`.
4. Verifier :
   `lastMessageAt != null` pour les conversations ayant des messages.
5. Executer `2 - Liste enrichie / GET conversations - filtre OPEN`.
   Attendu : toutes les lignes ont `status = OPEN`.
6. Executer `2 - Liste enrichie / GET conversations - filtre ALL`.
   Attendu : les deux conversations A et B sont presentes.

## T3 - Renommage manuel
1. Executer `3 - Renommage / PATCH conversation A - renommer`.
   Attendu : `HTTP 200`, `title = "Renommee via Postman"`.
2. Refaire ensuite un `GET /projects/{projectId}/conversations?status=ALL`.
   Attendu : le titre renomme est visible dans la liste.
3. Executer `3 - Renommage / PATCH conversation A - titre vide`.
   Attendu : `HTTP 400`.
4. Verifier que le titre n'a pas ete ecrase par l'appel invalide.

## T4 - Archivage et filtres
1. Executer `4 - Archivage / POST archive conversation B`.
   Attendu : `HTTP 200`, `status = ARCHIVED`.
2. Executer `4 - Archivage / POST archive conversation B - deja archivee`.
   Attendu : `HTTP 409`.
3. Executer `4 - Archivage / POST message conversation B archivee - refuse`.
   Attendu : `HTTP 409`.
4. Executer `4 - Archivage / GET conversations - filtre OPEN apres archivage`.
   Attendu : la conversation B n'apparait plus.
5. Executer `4 - Archivage / GET conversations - filtre ARCHIVED`.
   Attendu : la conversation B apparait avec `status = ARCHIVED`.
6. Executer `4 - Archivage / GET conversations - filtre ALL apres archivage`.
   Attendu : A et B sont toutes les deux presentes.

## Verifications SQL - table conversation
1. Ouvrir la base SQLite.
2. Executer :
```sql
SELECT id, project_id, title, status, message_count, last_message_at
FROM conversation
WHERE project_id = '<projectId>'
ORDER BY started_at DESC;
```
3. Attendus :
   conversation A a le titre renomme, `status = OPEN`, `message_count >= 2`, `last_message_at` non null.
4. Attendus :
   conversation B a `status = ARCHIVED`, `message_count >= 2`, `last_message_at` non null.
5. Si tu veux verifier le tri applicatif :
   les conversations avec `last_message_at` null doivent etre en bas de liste dans l'API.

## Verifications SQL - SPRING_AI_CHAT_MEMORY
1. Executer :
```sql
SELECT conversation_id, type, content, timestamp
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id IN ('<conversationIdA>', '<conversationIdB>')
ORDER BY conversation_id, timestamp;
```
2. Attendus :
   pour A et B, presence de messages `USER` et `ASSISTANT`.
3. Attendus :
   l'archivage ne purge pas `SPRING_AI_CHAT_MEMORY`.
4. Tu peux aussi verifier la coherence entre agregat et memoire :
```sql
SELECT conversation_id, COUNT(*) AS cnt
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id IN ('<conversationIdA>', '<conversationIdB>')
GROUP BY conversation_id;
```
5. Attendu :
   `cnt` correspond a `message_count` dans la table `conversation`.

## Verifications logs
1. Chercher `Conversation creee`.
   Attendu : une ligne par creation.
2. Chercher `Activite conversation mise a jour`.
   Attendu : une ligne apres ecriture memoire.
3. Chercher `Conversation renommee`.
   Attendu : une ligne avec ancien et nouveau titre.
4. Chercher `Conversation archivee`.
   Attendu : une ligne lors du `POST /archive`.
5. Chercher `POST /projects/.../conversations/.../archive - ok`.
   Attendu : trace du controller.
6. Chercher une tentative d'ecriture sur conversation archivee.
   Attendu : trace de refus metier, sans ecriture memoire.
7. Lors des commandes Telegram `/conversations`, `/conv 1`, `/new`, `/reset`, verifier l'absence de soumission de tache et l'absence de log HITL lie a la commande.

## Verifications Telegram manuelles
1. Dans Telegram, faire `/switch <nom_du_projet>`.
   Attendu : le projet de test devient actif.
2. Faire `/conversations`.
   Attendu : affichage d'une liste numerotee de conversations ouvertes.
3. Verifier dans les logs que `/conversations` n'a cree aucune tache et n'a declenche aucun HITL.
4. Faire `/conv 1` ou `/conv 2`.
   Attendu : message de reprise de conversation.
5. Verifier dans les logs que `/conv 1` n'a cree aucune tache et n'a declenche aucun HITL.
6. Envoyer un message libre.
   Attendu : le message est rattache a la conversation reprise.
7. Verifier en SQL :
```sql
SELECT conversation_id, type, content
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id IN ('<conversationIdA>', '<conversationIdB>')
ORDER BY timestamp;
```
8. Faire `/new`.
   Attendu : creation d'une nouvelle conversation ouverte.
9. Verifier dans les logs que `/new` n'a cree aucune tache et n'a declenche aucun HITL.
10. Verifier dans `conversation` qu'une nouvelle ligne `OPEN` apparait pour `project_id = <projectId>`.
11. Archiver via Postman la conversation qui etait active dans Telegram, puis renvoyer un message Telegram libre.
12. Attendu :
    le message est refuse, la conversation archivee n'est pas reutilisee, et la reponse guide l'utilisateur vers `/new` ou `/conversations`.
13. Verifier en logs qu'aucune tache n'a ete soumise pour ce message refuse.
14. Faire `/reset`.
15. Attendu :
    la conversation active et les suggestions de reprise sont effacees, sans tache ni HITL.

## Verification fichiers / workspace
1. Si le projet cree un workspace local, verifier simplement qu'aucun renommage ou archivage de conversation n'impacte les fichiers du projet.
2. Verifier en particulier qu'aucun effet de bord n'apparait dans `PROJECT.md`, `ROADMAP.md` ou les fichiers applicatifs du projet de test.

## Nettoyage
1. Dans le checkout courant, aucun endpoint REST `DELETE /projects/{projectId}/conversations/{id}` n'est expose.
2. Executer donc `6 - Nettoyage / DELETE projet`.
   Attendu : `HTTP 204`.
3. Optionnel SQL :
```sql
SELECT id, project_id
FROM conversation
WHERE project_id = '<projectId>';
```
4. Attendu :
   0 ligne.

## Verification finale
1. Rejouer rapidement `GET /projects/{projectId}/conversations?status=ALL` avant suppression si besoin pour screenshot ou preuve.
2. Si tu qualifies la feature pour livraison, executer ensuite le build de verification adapte a ton environnement.
