/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAwareOverlayTest {

    @TempDir Path workspace;
    @TempDir Path project;

    private ProjectAwareOverlay overlay;
    private final RuntimeContext rc = RuntimeContext.empty();

    @BeforeEach
    void setUp() {
        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        LocalFsMode.ROOTED,
                        policy,
                        120,
                        100_000,
                        null,
                        false,
                        null,
                        project);
        LocalFilesystem lower = new LocalFilesystem(project, true, 10, null);
        LocalFilesystem projectFs =
                new LocalFilesystem(project, LocalFsMode.ROOTED, policy, 10, null);
        overlay =
                new ProjectAwareOverlay(
                        (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
    }

    // ==================== Write routing ====================

    @Test
    void write_projectFile_landsInProjectDir() {
        WriteResult r = overlay.write(rc, "src/App.java", "public class App {}");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(project.resolve("src/App.java")));
        assertFalse(Files.exists(workspace.resolve("src/App.java")));
    }

    @Test
    void write_memoryMd_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "MEMORY.md", "# Memory");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(project.resolve("MEMORY.md")));
    }

    @Test
    void write_agentsSubpath_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "agents/main/sessions/s1.json", "{}");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("agents/main/sessions/s1.json")));
        assertFalse(Files.exists(project.resolve("agents/main/sessions/s1.json")));
    }

    @Test
    void write_skillsPath_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "skills/my-skill/SKILL.md", "# Skill");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("skills/my-skill/SKILL.md")));
    }

    @Test
    void write_plansPath_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "plans/plan1.md", "# Plan");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("plans/plan1.md")));
    }

    // ==================== Edit routing ====================

    @Test
    void edit_projectFile_editsInProject() throws IOException {
        Path file = project.resolve("README.md");
        Files.writeString(file, "Hello World", StandardCharsets.UTF_8);

        EditResult r = overlay.edit(rc, "README.md", "World", "AgentScope", false);
        assertTrue(r.isSuccess(), () -> "edit failed: " + r.error());
        assertEquals("Hello AgentScope", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void edit_memoryMd_editsInWorkspace() throws IOException {
        Path file = workspace.resolve("MEMORY.md");
        Files.writeString(file, "old memory", StandardCharsets.UTF_8);

        EditResult r = overlay.edit(rc, "MEMORY.md", "old", "new", false);
        assertTrue(r.isSuccess(), () -> "edit failed: " + r.error());
        assertEquals("new memory", Files.readString(file, StandardCharsets.UTF_8));
    }

    // ==================== Read (unchanged overlay semantics) ====================

    @Test
    void read_projectFile_visFallback() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        ReadResult r = overlay.read(rc, "pom.xml", 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("<project/>", r.fileData().content());
    }

    @Test
    void read_workspaceFile_takePrecedence() throws IOException {
        Files.writeString(project.resolve("AGENTS.md"), "project version", StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("AGENTS.md"), "workspace version", StandardCharsets.UTF_8);

        ReadResult r = overlay.read(rc, "AGENTS.md", 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("workspace version", r.fileData().content());
    }

    // ==================== Delete routing ====================

    @Test
    void delete_projectFile_deletesFromProject() throws IOException {
        Path file = project.resolve("temp.txt");
        Files.writeString(file, "temp", StandardCharsets.UTF_8);

        WriteResult r = overlay.delete(rc, "temp.txt");
        assertTrue(r.isSuccess());
        assertFalse(Files.exists(file));
    }

    @Test
    void delete_workspacePath_deletesFromWorkspace() throws IOException {
        Path dir = workspace.resolve("memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2024-01-01.md"), "log", StandardCharsets.UTF_8);

        WriteResult r = overlay.delete(rc, "memory/2024-01-01.md");
        assertTrue(r.isSuccess());
        assertFalse(Files.exists(dir.resolve("2024-01-01.md")));
    }

    // ==================== uploadFiles routing ====================

    @Test
    void uploadFiles_splitsByTarget() {
        List<Map.Entry<String, byte[]>> files =
                List.of(
                        Map.entry(
                                "src/Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8)),
                        Map.entry("MEMORY.md", "# Mem".getBytes(StandardCharsets.UTF_8)));

        List<FileUploadResponse> results = overlay.uploadFiles(rc, files);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(FileUploadResponse::isSuccess));

        assertTrue(Files.exists(project.resolve("src/Main.java")));
        assertTrue(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(workspace.resolve("src/Main.java")));
        assertFalse(Files.exists(project.resolve("MEMORY.md")));
    }

    // ==================== isWorkspacePath ====================

    @Test
    void isWorkspacePath_classifiesCorrectly() {
        assertTrue(overlay.isWorkspacePath("MEMORY.md"));
        assertTrue(overlay.isWorkspacePath("memory/2024-01-01.md"));
        assertTrue(overlay.isWorkspacePath("AGENTS.md"));
        assertTrue(overlay.isWorkspacePath("agents/main/sessions/s.json"));
        assertTrue(overlay.isWorkspacePath("skills/my-skill/SKILL.md"));
        assertTrue(overlay.isWorkspacePath("knowledge/KNOWLEDGE.md"));
        assertTrue(overlay.isWorkspacePath("rules/rule1.md"));
        assertTrue(overlay.isWorkspacePath("tools.json"));
        assertTrue(overlay.isWorkspacePath("subagents/researcher.md"));
        assertTrue(overlay.isWorkspacePath("plans/plan.md"));
        assertTrue(overlay.isWorkspacePath(".index/workspace.db"));
        assertTrue(overlay.isWorkspacePath(".skills-cache/cached"));
        assertTrue(overlay.isWorkspacePath("large_tool_results/agent/call1"));

        assertFalse(overlay.isWorkspacePath("src/App.java"));
        assertFalse(overlay.isWorkspacePath("pom.xml"));
        assertFalse(overlay.isWorkspacePath("README.md"));
        assertFalse(overlay.isWorkspacePath("docker-compose.yml"));
    }

    @Test
    void isWorkspacePath_absoluteUnderWorkspace_returnsTrue() {
        String absPath = workspace.resolve("anything.txt").toAbsolutePath().toString();
        assertTrue(overlay.isWorkspacePath(absPath));
    }

    @Test
    void isWorkspacePath_absoluteUnderProject_returnsFalse() {
        String absPath = project.resolve("src/App.java").toAbsolutePath().toString();
        assertFalse(overlay.isWorkspacePath(absPath));
    }

    // ==================== Shell execute delegates to upper ====================

    @Test
    void execute_delegatesToShellBackend() {
        var r = overlay.execute(rc, "echo hello", 10);
        assertTrue(r.output().contains("hello"));
        assertEquals(0, r.exitCode());
    }

    // ==================== Namespace-scoped read/write symmetry (regression) ====================
    //
    // When a NamespaceFactory is active (e.g. IsolationScope.USER -> [userId]), projectWritable
    // writes land under <project>/<userId>/. Reads, listings and searches must resolve the same
    // namespaced location so a file written as "hi.txt" is readable back as "hi.txt" — they used
    // to fall through to the non-namespaced lower layer and miss it (caller had to pass
    // "<userId>/hi.txt").

    private static final NamespaceFactory USER_NS = runtimeContext -> List.of("u1");

    private ProjectAwareOverlay namespacedOverlay() {
        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        LocalFsMode.ROOTED,
                        policy,
                        120,
                        100_000,
                        null,
                        false,
                        USER_NS,
                        project);
        // Mirrors LocalFilesystemSpec.toFilesystem: lower is non-namespaced (raw project tree),
        // projectFs is namespace-scoped (where projectWritable writes go).
        LocalFilesystem lower = new LocalFilesystem(project, true, 10, null);
        LocalFilesystem projectFs =
                new LocalFilesystem(project, LocalFsMode.ROOTED, policy, 10, USER_NS);
        return new ProjectAwareOverlay(
                (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
    }

    @Test
    void namespacedWrite_isReadableBackUnderSamePath() {
        ProjectAwareOverlay ns = namespacedOverlay();

        WriteResult w = ns.write(rc, "hi.txt", "hello");
        assertTrue(w.isSuccess(), () -> "write failed: " + w.error());
        // Stored namespace-scoped on disk.
        assertTrue(
                Files.exists(project.resolve("u1/hi.txt")),
                "namespaced write should land under <project>/u1/");
        assertFalse(Files.exists(project.resolve("hi.txt")));

        // The reported bug: this read used to fail because it fell through to the non-namespaced
        // lower layer looking at <project>/hi.txt.
        ReadResult r = ns.read(rc, "hi.txt", 0, 0);
        assertTrue(r.isSuccess(), () -> "read failed: " + r.error());
        assertEquals("hello", r.fileData().content());

        assertTrue(ns.exists(rc, "hi.txt"));
    }

    @Test
    void namespacedWrite_visibleInRootListing() {
        ProjectAwareOverlay ns = namespacedOverlay();
        ns.write(rc, "hi.txt", "hello");

        for (String dir : List.of("/", ".", "")) {
            LsResult ls = ns.ls(rc, dir);
            assertTrue(ls.isSuccess(), () -> "ls failed for " + dir);
            List<String> paths = ls.entries().stream().map(fi -> fi.path()).toList();
            assertTrue(
                    paths.stream().anyMatch(p -> p.equals("hi.txt")),
                    () -> "ls(" + dir + ") missing hi.txt: " + paths);
            // The namespace folder must not leak through the non-namespaced lower layer.
            assertTrue(
                    paths.stream().noneMatch(ProjectAwareOverlayTest::mentionsNamespaceDir),
                    () -> "ls(" + dir + ") leaked namespace prefix: " + paths);
        }
    }

    @Test
    void namespacedWrite_foundByGlobAndGrep() {
        ProjectAwareOverlay ns = namespacedOverlay();
        ns.write(rc, "hi.txt", "the needle is here");

        GlobResult g = ns.glob(rc, "**/*.txt", "/");
        assertTrue(g.isSuccess());
        List<String> globPaths = g.matches().stream().map(fi -> fi.path()).toList();
        assertTrue(
                globPaths.stream().anyMatch(p -> p.equals("hi.txt")),
                () -> "glob missing hi.txt: " + globPaths);
        assertTrue(
                globPaths.stream().noneMatch(ProjectAwareOverlayTest::mentionsNamespaceDir),
                () -> "glob leaked namespace prefix: " + globPaths);

        GrepResult gr = ns.grep(rc, "needle", ".", null);
        assertTrue(gr.isSuccess());
        List<String> grepPaths = gr.matches().stream().map(m -> m.path()).toList();
        assertTrue(
                grepPaths.stream().anyMatch(p -> p.equals("hi.txt")),
                () -> "grep missing hi.txt: " + grepPaths);
        assertTrue(
                grepPaths.stream().noneMatch(ProjectAwareOverlayTest::mentionsNamespaceDir),
                () -> "grep leaked namespace prefix: " + grepPaths);
    }

    /** True if a listing/search path exposes the raw {@code u1/} namespace folder. */
    private static boolean mentionsNamespaceDir(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.equals("u1") || p.equals("u1/") || p.startsWith("u1/");
    }

    @Test
    void namespaced_originalProjectTreeStillReadable() throws IOException {
        ProjectAwareOverlay ns = namespacedOverlay();
        // Pre-existing, non-namespaced file in the raw project tree (e.g. checked-out source).
        Files.writeString(project.resolve("existing.txt"), "from repo", StandardCharsets.UTF_8);

        ReadResult r = ns.read(rc, "existing.txt", 0, 0);
        assertTrue(r.isSuccess(), () -> "read failed: " + r.error());
        assertEquals("from repo", r.fileData().content());
        assertTrue(ns.exists(rc, "existing.txt"));
    }

    @Test
    void namespaced_workspaceWriteStillTakesPrecedenceOnRead() throws IOException {
        ProjectAwareOverlay ns = namespacedOverlay();
        // Same relative path present as raw project file and as a workspace (upper) write.
        Files.writeString(project.resolve("AGENTS.md"), "project version", StandardCharsets.UTF_8);
        ns.write(rc, "AGENTS.md", "workspace version");

        ReadResult r = ns.read(rc, "AGENTS.md", 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("workspace version", r.fileData().content());
    }

    @Test
    void namespaced_workspaceMetadataRead_ignoresProjectFsNamespaceDir() throws IOException {
        ProjectAwareOverlay ns = namespacedOverlay();
        // A file that coincidentally lives under projectFs's namespace folder and shares a
        // workspace-metadata name. Writes of "AGENTS.md" always go to the workspace (upper), so
        // reads of it must NOT be served from projectFs's <project>/u1/ tree.
        Files.createDirectories(project.resolve("u1"));
        Files.writeString(
                project.resolve("u1/AGENTS.md"), "namespaced project copy", StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("AGENTS.md"), "original project copy", StandardCharsets.UTF_8);

        ReadResult r = ns.read(rc, "AGENTS.md", 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("original project copy", r.fileData().content());
    }

    @Test
    void namespaced_workspaceMetadataSearches_ignoreProjectFsNamespaceDir() throws IOException {
        ProjectAwareOverlay ns = namespacedOverlay();
        Files.createDirectories(project.resolve("u1/skills/demo"));
        Files.writeString(
                project.resolve("u1/skills/demo/SKILL.md"),
                "namespaced project skill",
                StandardCharsets.UTF_8);
        Files.createDirectories(project.resolve("skills/original"));
        Files.writeString(
                project.resolve("skills/original/SKILL.md"),
                "original project skill",
                StandardCharsets.UTF_8);

        LsResult ls = ns.ls(rc, "skills");
        assertTrue(ls.isSuccess());
        List<String> lsPaths =
                ls.entries().stream().map(fi -> stripLeadingSlash(fi.path())).toList();
        assertTrue(lsPaths.stream().anyMatch(p -> p.equals("skills/original/")));
        assertTrue(lsPaths.stream().noneMatch(p -> p.equals("skills/demo/")));

        GlobResult glob = ns.glob(rc, "**/SKILL.md", "skills");
        assertTrue(glob.isSuccess());
        List<String> globPaths =
                glob.matches().stream().map(fi -> stripLeadingSlash(fi.path())).toList();
        assertTrue(globPaths.stream().anyMatch(p -> p.equals("skills/original/SKILL.md")));
        assertTrue(globPaths.stream().noneMatch(p -> p.equals("skills/demo/SKILL.md")));

        GrepResult grep = ns.grep(rc, "skill", "skills", null);
        assertTrue(grep.isSuccess());
        List<String> grepPaths =
                grep.matches().stream().map(m -> stripLeadingSlash(m.path())).toList();
        assertTrue(grepPaths.stream().anyMatch(p -> p.equals("skills/original/SKILL.md")));
        assertTrue(grepPaths.stream().noneMatch(p -> p.equals("skills/demo/SKILL.md")));
    }

    private static String stripLeadingSlash(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
