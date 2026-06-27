# Regles de codage - Marcel Maestro (MM)

Regles **transverses et obligatoires** pour tout le code produit, a TOUTES les etapes,
en plus des contraintes propres a chaque etape (et de la discipline de purete de `mm-core`).

## 1. Lombok partout

Utiliser **Lombok** systematiquement pour eliminer le boilerplate : `@Getter`/`@Setter`,
`@RequiredArgsConstructor`, `@Builder`, `@Value` (types immuables), `@Slf4j`, etc.

- Ajouter la dependance `org.projectlombok:lombok` (version geree par le BOM Spring Boot)
  en scope **provided/optional** dans chaque module qui produit du code, **y compris
  `mm-core`**. Lombok est *compile-time only* : il ne viole pas la purete du noyau
  (ni web, ni data, ni metier) et le litmus `maven-enforcer` reste vert.
- Les `record` deja en place ne sont **pas** a convertir de force : Lombok s'applique au
  nouveau code et aux classes (non-record). Choisir `record` ou classe Lombok selon le cas
  (record pour un DTO immuable simple, classe Lombok des qu'il faut builder/logique).

## 2. Logging SLF4J par annotation

- Annoter les classes avec **`@Slf4j`** (Lombok).
- Deposer un **`log.info(...)` a chaque etape importante du process** : entree/sortie d'une
  operation significative, decision de routage, transition de statut, appel d'outil,
  consentement HITL, persistance, demarrage/arret de boucle, etc.
- Mettre des **`log.debug(...)` sur les elements cles a verifier** : valeurs de variables
  determinantes, cles de cache, contenu parse, requetes, compteurs de garde-fous, etc.
- **Jamais** de secret, cle d'API ou donnee sensible en clair dans les logs.
- Messages parametres (`log.info("... {}", valeur)`), jamais de concatenation de String.

## 3. JavaDoc sur les methodes

- Toute methode **publique et protegee** porte un JavaDoc decrivant son role, ses
  parametres (`@param`), son retour (`@return`) et les exceptions (`@throws`) le cas echeant.
- JavaDoc egalement au niveau **classe** pour expliquer sa responsabilite.
- Le JavaDoc explique le *pourquoi* / le contrat, pas la paraphrase ligne a ligne du code.

## 4. Gestion de la configuration sensible

`application.yml` contient des credentials (tokens, cles API, identifiants) et ne doit
**jamais** etre versionne dans Git.

- `mm-app/src/main/resources/application.yml` est dans le `.gitignore` - **ne jamais
  forcer son ajout** (`git add -f` interdit sur ce fichier).
- Le fichier versionne de reference est `application-template.yml`, dans le meme
  repertoire. Il contient la structure complete mais **uniquement des placeholders**
  (`${MA_VARIABLE}`) a la place de toute valeur sensible.
- **A chaque ajout dans `application.yml`** (nouvelle cle, nouveau bloc) : repercuter
  immediatement la modification dans `application-template.yml`. Si la valeur est
  sensible, remplacer par un placeholder `${NOM_VARIABLE}` et documenter la variable
  dans l'en-tete du template. Si la valeur est non-sensible (chemin local, flag, timeout),
  elle peut rester telle quelle dans le template.
- Un nouveau developpeur clone le depot, copie le template et renseigne ses propres
  valeurs :
  ```bash
  cp application-template.yml application.yml
  # puis edite application.yml avec ses credentials
  ```

## 5. Timeouts - build Maven et outils longs

Le build `mvn verify` de Marcel Maestro est **tres long** sur l'environnement cible (Windows,
Java 21). Un timeout insuffisant provoque des echecs silencieux difficiles a diagnostiquer.

**Regles obligatoires :**

- Tout test JUnit qui invoque un processus externe (ex. `MavenBuildTool`) doit configurer
  un timeout d'au moins **10 minutes** (600 000 ms) :
  ```java
  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void monTest() { ... }
  ```
- La propriete de timeout de `MavenBuildTool` (et de tout outil d'execution longue) doit
  avoir une valeur par defaut **>= 600 000 ms** dans `application.yml` / `application-template.yml`.
- Ne jamais laisser un timeout par defaut herite (souvent 30 s a 2 min) sur une operation Maven.
- Pour la validation manuelle, compter **5 a 15 minutes** pour un `mvn verify` complet selon
  les conditions reseau (telechargement de dependances) et la charge machine.
- Pour les agents qui executent `mvn verify` en fin de milestone : prevoir explicitement
  ce delai dans les instructions ; ne pas interpreter une absence de resultat rapide comme
  un echec.

## 6. Qualification obligatoire des tests

Tout test nouveau ou modifie doit etre **qualifie au moment de son ecriture** selon sa
rapidite d'execution. Cette qualification sert directement a maintenir les lanes Maven :
`rapide`, `rapide + lent`, `full`.

### Categories

- **Rapide** : test unitaire pur ou test leger, sans `@SpringBootTest`, sans DB, sans Flyway,
  sans filesystem lourd, sans timeout metier.
- **Lent** : test avec demarrage Spring, acces SQLite/JPA/Flyway, MockMvc, autoconfiguration
  applicative, nettoyage filesystem ou cout d'integration notable.
- **Tres lent** : test qui valide explicitement un **timeout**, une attente longue, ou un
  test d'integration massif avec redemarrages de contexte et cout cumule important.

### Regles obligatoires

- Un test `lent` doit etre tague `@Tag("slow")`.
- Un test `tres lent` doit etre tague `@Tag("very-slow")`.
- Un test de type spike reste tague `@Tag("spike")` et ne fait pas partie du full standard.
- Un test manuel reste tague `@Tag("manual")`.
- Un test qui verifie un timeout doit etre classe `tres lent`, meme si le timeout de test
  est artificiellement raccourci pour accelerer l'execution.
- En cas de doute, tout test avec `@SpringBootTest` ou acces DB doit etre considere au minimum
  comme `lent`.
