## Pré-requis
1. Démarrer l'application Marcel Maestro sur `http://localhost:8080`.
2. Importer la collection Postman `docs/evolution-3-conversation/postman-e3-m3.json`.
3. Créer un projet de test via l'API.
4. Vérifier que `PROJECT.md` et `ROADMAP.md` ont été créés automatiquement dans son workspace.
5. Remplacer leur contenu par `Projet de démonstration E3-M3. Stack : Java 21, Spring Boot 3.` pour `PROJECT.md` et `Étape en cours : E3-M3 — contexte projet.` pour `ROADMAP.md`.
6. Préparer aussi un fichier applicatif de test, par exemple `src/Main.java`, dans le même workspace.

## T1 - Contexte injecté automatiquement
1. Exécuter `Setup / Créer projet de test`, puis `Setup / Lire le projet créé` dans Postman.
2. Noter `workspacePath` dans la réponse et mettre à jour `PROJECT.md`, `ROADMAP.md` et `src/Main.java` dans ce dossier.
3. Exécuter `Setup / Créer conversation de test`.
4. Exécuter `T1 - Contexte injecté / POST /messages - parle-moi de ce projet`.
5. Résultat attendu : `HTTP 200`, body avec `role = ASSISTANT`.
6. Résultat attendu : la réponse mentionne `Java 21` ou `Spring Boot 3`, preuve que `PROJECT.md` a été injecté.
7. Dans DB Browser SQLite, exécuter :
```sql
SELECT content
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id = '<conversationId>'
  AND type = 'ASSISTANT';
```
8. Vérifier : le message assistant est persisté.

## T2 - Lecture à la demande via read_project_file
1. Exécuter `T2 - read_project_file / POST /messages - lis src/Main.java`.
2. Résultat attendu : `HTTP 200`, body avec `role = ASSISTANT`.
3. Résultat attendu : la réponse retourne ou paraphrase le contenu de `src/Main.java`.
4. Vérifier dans les logs applicatifs la présence de `read_project_file` et du chemin relatif demandé.

## T3 - Sécurité path traversal
1. Exécuter `T3 - Path traversal / POST /messages - demande interdite`.
2. Résultat attendu : `HTTP 200`, Marcel répond qu'il ne peut pas accéder à ce chemin ou refuse la lecture.
3. Vérifier dans les logs applicatifs la présence d'un `log.warn` lié à `read_project_file` ou `PathValidator`.

## T4 - Projet sans PROJECT.md ni ROADMAP.md
1. Exécuter `T4 - Projet sans contexte / Créer second projet vide`.
2. Supprimer ou renommer `PROJECT.md` et `ROADMAP.md` dans le workspace de ce second projet.
3. Exécuter `T4 - Projet sans contexte / Créer conversation du projet vide`.
4. Exécuter `T4 - Projet sans contexte / POST /messages - projet vide`.
5. Résultat attendu : `HTTP 200`, Marcel répond normalement sans planter.
6. Résultat attendu : la réponse ne dépend pas d'un contexte projet absent.

## T5 - Troncature et logs
1. Remplacer `PROJECT.md` par un contenu supérieur à 3 000 caractères.
2. Rejouer `T1 - Contexte injecté / POST /messages - parle-moi de ce projet`.
3. Vérifier dans les logs applicatifs :
   `ProjectContextExtension` avec la lecture du fichier.
4. Vérifier dans les logs applicatifs :
   un message de troncature avec `originalLength` et `maxChars`.
5. Si un appel cible un fichier long via `read_project_file`, vérifier aussi le log de troncature de cet outil.

## Vérifications SQL complémentaires
1. Vérifier l'historique complet de la conversation :
```sql
SELECT conversation_id, type, content
FROM SPRING_AI_CHAT_MEMORY
WHERE conversation_id = '<conversationId>'
ORDER BY timestamp;
```
2. Résultat attendu : alternance cohérente `USER` puis `ASSISTANT`.

## Vérifications logs obligatoires
1. Chercher `ProjectContextExtension` : lecture de `PROJECT.md` ou `ROADMAP.md`.
2. Chercher `troncature appliquée` : présent uniquement si le contenu dépasse la limite.
3. Chercher `read_project_file` : lecture ciblée à la demande.
4. Chercher `PathValidator` ou `validation refusée` : présent sur tentative de path traversal.

## Vérification finale
1. Exécuter `mvn -pl mm-core test`.
2. Exécuter `mvn verify`.
3. Vérifier : tous les tests sont verts et `mm-core` reste pur.
