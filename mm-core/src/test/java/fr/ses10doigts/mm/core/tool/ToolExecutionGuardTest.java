package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.engine.support.ScriptedHumanInteraction;
import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.hitl.HitlPolicy;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires de {@link ToolExecutionGuard} : vérification HITL (deny/allow),
 * timeout d'exécution, exécution directe sans guard, rejet par PathValidator, et
 * bypass HITL write pour les chemins dans un workspace externe déclaré (ADR-023, E2-M3).
 *
 * <p>{@link WorkspaceRegistry} est stubbé via un lambda — zéro accès DB dans mm-core.</p>
 */
class ToolExecutionGuardTest {

    private static final AgentContext CTX = AgentContext.of("default", "p1", "c1", "t1");

    @TempDir
    Path workspace;

    @TempDir
    Path externalWorkspace;

    // ──────────────────────────────────────────────────────────────────────────────────
    // Comportement HITL existant — inchangé
    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void hitlDeny_retourneEchec() {
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, null);

        AgentTool tool = stubTool("danger", RiskLevel.HIGH);
        ToolResult result = guard.execute(tool, Map.of(), CTX);

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("denied"));
    }

    @Test
    void hitlAllow_executeNormalement() {
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, null);

        AgentTool tool = stubTool("safe", RiskLevel.HIGH);
        ToolResult result = guard.execute(tool, Map.of("key", "val"), CTX);

        assertTrue(result.success());
        assertNotNull(result.data());
    }

    @Test
    void outilTropLent_retourneTimeout() {
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        AgentTool slowTool = new AgentTool() {
            @Override public String name() { return "slow"; }
            @Override public String description() { return "Slow tool"; }
            @Override public JsonNode inputSchema() {
                return new ObjectMapper().createObjectNode().put("type", "object");
            }
            @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
            @Override public long maxExecutionTimeMs() { return 100; }
            @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { /* ignore */ }
                return ToolResult.ok("trop tard");
            }
        };

        ToolResult result = guard.execute(slowTool, Map.of(), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("timeout"));
    }

    @Test
    void sansHitlGuard_executeDirectement() {
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);
        AgentTool tool = stubTool("direct", RiskLevel.HIGH);

        ToolResult result = guard.execute(tool, Map.of("a", "b"), CTX);

        assertTrue(result.success());
    }

    @Test
    void pathValidatorRejetteCheminDangereux() {
        PathValidator pathValidator = new PathValidator(workspace);
        ToolExecutionGuard guard = new ToolExecutionGuard(null, pathValidator);
        AgentTool tool = stubTool("fs_tool", RiskLevel.LOW);

        ToolResult result = guard.execute(tool, Map.of("file", "../../etc/passwd"), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("path violation"));
    }

    // ──────────────────────────────────────────────────────────────────────────────────
    // Bypass HITL write — workspace externe déclaré (ADR-023, E2-M3)
    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void cheminDansWorkspaceExterne_bypasseHitl() {
        // HITL configuré pour refuser (DENY) — mais le bypass doit l'empêcher d'être appelé
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);

        String externalFile = externalWorkspace.resolve("src/Foo.java").toAbsolutePath().toString();
        WorkspaceRegistry registry = (path, projectId) ->
                Path.of(path).normalize().startsWith(externalWorkspace.toAbsolutePath().normalize());
        PathValidator pathValidator = new PathValidator(workspace, registry);

        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_file", RiskLevel.HIGH);

        ToolResult result = guard.execute(tool, Map.of("file", externalFile), CTX);

        assertTrue(result.success(), "L'outil doit s'exécuter sans HITL");
        assertEquals(0, hitl.askCount(), "Le HITL ne doit pas être appelé pour un workspace déclaré");
    }

    @Test
    void cheminDansWorkspaceInterne_hitlDeclenche() {
        // Chemin dans le workspace interne → HITL s'applique normalement
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);

        // Le registre dit "non" (le chemin n'est pas dans un workspace externe déclaré)
        WorkspaceRegistry registry = (path, projectId) -> false;
        PathValidator pathValidator = new PathValidator(workspace, registry);

        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_internal", RiskLevel.HIGH);

        String internalFile = workspace.resolve("output/result.txt").toAbsolutePath().toString();
        ToolResult result = guard.execute(tool, Map.of("file", internalFile), CTX);

        assertTrue(result.success());
    }

    @Test
    void cheminHorsToutWorkspace_hitlDeclenche() {
        // Chemin hors workspace interne ET hors dossiers externes → HITL s'applique
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);

        WorkspaceRegistry registry = (path, projectId) -> false;
        PathValidator pathValidator = new PathValidator(workspace, registry);

        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_elsewhere", RiskLevel.HIGH);

        ToolResult result = guard.execute(tool, Map.of("file", "/tmp/secret"), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("denied"));
    }

    @Test
    void pathTraversalDansWorkspaceExterne_rejete() {
        // Traversal dans un dossier externe : /external/../etc/passwd → rejeté par PathValidator
        WorkspaceRegistry registry = (path, projectId) -> {
            // Simule la normalisation embarquée : un traversal normalisé sort du dossier déclaré
            Path normalized = Path.of(path).normalize();
            return normalized.startsWith(externalWorkspace.toAbsolutePath().normalize());
        };
        PathValidator pathValidator = new PathValidator(workspace, registry);
        ToolExecutionGuard guard = new ToolExecutionGuard(null, pathValidator, registry);
        AgentTool tool = stubTool("write_file", RiskLevel.LOW);

        String traversal = externalWorkspace.resolve("../etc/passwd").toString();
        ToolResult result = guard.execute(tool, Map.of("file", traversal), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("path violation"));
    }

    @Test
    void projectIdNull_pasDeBypasse() {
        // Contexte sans projectId → pas de bypass, même avec un registre présent
        AgentContext ctxSansProjet = AgentContext.of("default", null, "c1", "t1");

        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);

        WorkspaceRegistry registry = (path, projectId) -> true; // dirait "oui" si appelé avec projectId
        PathValidator pathValidator = new PathValidator(workspace, registry);

        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_file", RiskLevel.HIGH);

        String externalFile = externalWorkspace.resolve("src/Foo.java").toAbsolutePath().toString();
        guard.execute(tool, Map.of("file", externalFile), ctxSansProjet);

        assertEquals(1, hitl.askCount(), "HITL doit être consulté quand projectId est null");
    }

    @Test
    void sansWorkspaceRegistry_pasDeBypasse() {
        // Sans WorkspaceRegistry, le bypass ne s'active jamais
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, null); // pas de registry

        AgentTool tool = stubTool("write_file", RiskLevel.HIGH);
        String externalFile = externalWorkspace.resolve("src/Foo.java").toAbsolutePath().toString();

        ToolResult result = guard.execute(tool, Map.of("file", externalFile), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("denied"));
    }

    // ──────────────────────────────────────────────────────────────────────────────────
    // Rejet système pré-HITL (E2-M3+) — SystemPathGuard avant tout
    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void cheminSystemeAbsolu_rejeteAvantHitl() {
        // HITL configuré pour ALLOW — mais il ne doit jamais être consulté pour un chemin système
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        WorkspaceRegistry registry = (path, projectId) -> false;
        PathValidator pathValidator = new PathValidator(workspace, registry);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_system", RiskLevel.HIGH);

        String systemPath = platformSystemPath();
        ToolResult result = guard.execute(tool, Map.of("file", systemPath), CTX);

        assertFalse(result.success(), "Un chemin système doit être rejeté");
        assertTrue(result.error().contains("path violation"),
                "Le message d'erreur doit contenir 'path violation'");
        assertEquals(0, hitl.askCount(),
                "Le HITL ne doit jamais être consulté pour un chemin système");
    }

    @Test
    void cheminExterneNonSysteme_hitlApprouve_execute() {
        // Chemin absolu non-système hors workspace : HITL doit être consulté UNE FOIS
        // et si approuvé, l'outil doit s'exécuter
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        WorkspaceRegistry registry = (path, projectId) -> false;
        PathValidator pathValidator = new PathValidator(workspace, registry);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_external", RiskLevel.HIGH);

        // Un fichier dans externalWorkspace (TempDir) — non-système, hors workspace interne,
        // non déclaré dans le registre
        String externalFile = externalWorkspace.resolve("docs/readme.md")
                .toAbsolutePath().toString();
        ToolResult result = guard.execute(tool, Map.of("file", externalFile), CTX);

        assertTrue(result.success(),
                "Un chemin non-système approuvé par HITL doit permettre l'exécution");
        assertEquals(1, hitl.askCount(),
                "Le HITL doit être consulté exactement une fois");
    }

    @Test
    void cheminExterneNonSysteme_hitlRefuse_echoue() {
        // Même scénario mais HITL refuse → échec
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction()
                .respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        WorkspaceRegistry registry = (path, projectId) -> false;
        PathValidator pathValidator = new PathValidator(workspace, registry);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, pathValidator, registry);
        AgentTool tool = stubTool("write_external_denied", RiskLevel.HIGH);

        String externalFile = externalWorkspace.resolve("docs/readme.md")
                .toAbsolutePath().toString();
        ToolResult result = guard.execute(tool, Map.of("file", externalFile), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("denied"));
        assertEquals(1, hitl.askCount(), "Le HITL doit avoir été consulté une fois");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static String platformSystemPath() {
        if (WINDOWS) {
            String sysRoot = System.getenv("SystemRoot");
            if (sysRoot == null || sysRoot.isBlank()) sysRoot = "C:\\Windows";
            return sysRoot + "\\System32\\drivers\\etc\\hosts";
        } else {
            return "/etc/passwd";
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────────────

    private static AgentTool stubTool(String name, RiskLevel risk) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Stub " + name; }
            @Override public JsonNode inputSchema() {
                return new ObjectMapper().createObjectNode().put("type", "object");
            }
            @Override public RiskLevel riskLevel() { return risk; }
            @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
                return ToolResult.ok("result:" + params);
            }
        };
    }
}
