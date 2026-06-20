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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overlay variant that routes writes to the <em>project</em> directory for non-workspace paths,
 * while keeping workspace metadata (memory, sessions, skills, etc.) in the upper (workspace) layer.
 *
 * <p>Produced exclusively by {@link io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec}
 * when {@code projectWritable(true)} is set. Other filesystem specs ({@code RemoteFilesystemSpec},
 * {@code SandboxFilesystemSpec}) are not affected.
 *
 * <p>Read operations are symmetric with writes. Workspace-metadata paths keep standard overlay
 * semantics (upper → lower) and never consult {@code projectFs}, mirroring their write routing.
 * Other paths resolve <em>upper (legacy/pre-projectWritable writes) → projectFs (namespace-scoped
 * project writes) → lower (raw project tree)</em>, so a non-workspace file written as
 * {@code hi.txt} (stored at {@code <project>/<userId>/hi.txt}) reads back under the same relative
 * path while pre-existing project files remain visible. Directory listings and searches merge the
 * layers; entries the non-namespaced {@code lower} layer sees inside projectFs's namespace folder
 * are dropped so the raw {@code <userId>/} prefix never leaks. Shell {@code execute()} delegates to
 * the upper layer as before.
 */
public class ProjectAwareOverlay extends OverlayFilesystem implements AbstractSandboxFilesystem {

    private static final Set<String> WORKSPACE_PREFIXES =
            Set.of(
                    "MEMORY.md",
                    "memory",
                    "AGENTS.md",
                    "agents",
                    "skills",
                    "knowledge",
                    "rules",
                    "tools.json",
                    "subagents",
                    "plans",
                    ".index",
                    ".skills-cache",
                    "large_tool_results");

    private final AbstractSandboxFilesystem shellBackend;
    private final LocalFilesystem projectFs;
    private final Path workspaceRoot;

    /**
     * @param upper shell-capable workspace filesystem (read-write, workspace root)
     * @param lower read-only project filesystem (overlay fallback)
     * @param projectFs writable project filesystem for non-workspace writes
     * @param workspaceRoot absolute path of the workspace, used to classify absolute paths
     */
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot) {
        super(upper, lower);
        this.shellBackend = upper;
        this.projectFs = projectFs;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return shellBackend.id();
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        return shellBackend.execute(runtimeContext, command, timeoutSeconds);
    }

    // ==================== Write routing ====================

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (isWorkspacePath(filePath)) {
            return upper().write(runtimeContext, filePath, content);
        }
        return projectFs.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (isWorkspacePath(filePath)) {
            return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        if (projectFs.exists(runtimeContext, filePath)) {
            return projectFs.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        // Fallback: file may exist only in upper (written before projectWritable was enabled)
        return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        if (isWorkspacePath(path)) {
            return super.delete(runtimeContext, path);
        }
        if (projectFs.exists(runtimeContext, path)) {
            return projectFs.delete(runtimeContext, path);
        }
        return super.delete(runtimeContext, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<Map.Entry<String, byte[]>> workspaceFiles = new ArrayList<>();
        List<Map.Entry<String, byte[]>> projectFiles = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            if (isWorkspacePath(entry.getKey())) {
                workspaceFiles.add(entry);
            } else {
                projectFiles.add(entry);
            }
        }
        List<FileUploadResponse> results = new ArrayList<>();
        if (!workspaceFiles.isEmpty()) {
            results.addAll(upper().uploadFiles(runtimeContext, workspaceFiles));
        }
        if (!projectFiles.isEmpty()) {
            results.addAll(projectFs.uploadFiles(runtimeContext, projectFiles));
        }
        return results;
    }

    // ==================== Read routing ====================
    //
    // OverlayFilesystem only knows about (upper, lower); its read-family operations would miss
    // files written through projectFs, which is namespace-scoped. We override them here so reads
    // stay symmetric with write routing:
    //   - workspace-metadata paths: upper -> lower (projectFs is never a write target for them,
    //     so it must not participate in their read precedence either);
    //   - everything else: upper (legacy, pre-projectWritable writes) -> projectFs -> lower.
    // For directory listings and searches the layers are merged; entries the non-namespaced
    // {@code lower} sees inside projectFs's namespace folder (e.g. {@code u1/hi.txt}) are dropped,
    // since projectFs already surfaces them de-namespaced and the raw prefix must not leak.

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        if (isWorkspacePath(filePath)) {
            return super.read(runtimeContext, filePath, offset, limit);
        }
        if (upper().exists(runtimeContext, filePath)) {
            return upper().read(runtimeContext, filePath, offset, limit);
        }
        if (projectFs.exists(runtimeContext, filePath)) {
            return projectFs.read(runtimeContext, filePath, offset, limit);
        }
        return lower().read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (isWorkspacePath(path)) {
            return super.exists(runtimeContext, path);
        }
        return upper().exists(runtimeContext, path)
                || projectFs.exists(runtimeContext, path)
                || lower().exists(runtimeContext, path);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        if (isWorkspaceQueryPath(path)) {
            return super.ls(runtimeContext, path);
        }
        LsResult lowerResult = lower().ls(runtimeContext, path);
        LsResult projectResult = projectFs.ls(runtimeContext, path);
        LsResult upperResult = upper().ls(runtimeContext, path);

        if (!lowerResult.isSuccess() && !projectResult.isSuccess() && !upperResult.isSuccess()) {
            return upperResult;
        }

        // Merge with increasing precedence: raw project tree (minus projectFs's namespace folder),
        // then namespaced project writes, then workspace entries override on path collision.
        List<String> ns = namespace(runtimeContext);
        Map<String, FileInfo> merged = new LinkedHashMap<>();
        mergeEntries(merged, lowerResult, ns);
        mergeEntries(merged, projectResult, List.of());
        mergeEntries(merged, upperResult, List.of());
        return LsResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        if (isWorkspaceQueryPath(path)) {
            return super.grep(runtimeContext, pattern, path, glob);
        }
        GrepResult lowerResult = lower().grep(runtimeContext, pattern, path, glob);
        GrepResult projectResult = projectFs.grep(runtimeContext, pattern, path, glob);
        GrepResult upperResult = upper().grep(runtimeContext, pattern, path, glob);

        if (!lowerResult.isSuccess() && !projectResult.isSuccess() && !upperResult.isSuccess()) {
            return upperResult;
        }

        List<String> ns = namespace(runtimeContext);
        Map<String, GrepMatch> merged = new LinkedHashMap<>();
        mergeMatches(merged, lowerResult, ns);
        mergeMatches(merged, projectResult, List.of());
        mergeMatches(merged, upperResult, List.of());
        return GrepResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        if (isWorkspaceQueryPath(path)) {
            return super.glob(runtimeContext, pattern, path);
        }
        GlobResult lowerResult = lower().glob(runtimeContext, pattern, path);
        GlobResult projectResult = projectFs.glob(runtimeContext, pattern, path);
        GlobResult upperResult = upper().glob(runtimeContext, pattern, path);

        if (!lowerResult.isSuccess() && !projectResult.isSuccess() && !upperResult.isSuccess()) {
            return upperResult;
        }

        List<String> ns = namespace(runtimeContext);
        Map<String, FileInfo> merged = new LinkedHashMap<>();
        mergeGlobMatches(merged, lowerResult, ns);
        mergeGlobMatches(merged, projectResult, List.of());
        mergeGlobMatches(merged, upperResult, List.of());
        return GlobResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>();
        for (String path : paths) {
            if (isWorkspacePath(path)) {
                results.addAll(super.downloadFiles(runtimeContext, List.of(path)));
            } else if (upper().exists(runtimeContext, path)) {
                results.addAll(upper().downloadFiles(runtimeContext, List.of(path)));
            } else if (projectFs.exists(runtimeContext, path)) {
                results.addAll(projectFs.downloadFiles(runtimeContext, List.of(path)));
            } else {
                results.addAll(lower().downloadFiles(runtimeContext, List.of(path)));
            }
        }
        return results;
    }

    /** Active namespace tuple for {@code projectFs} (empty when no namespace is configured). */
    private List<String> namespace(RuntimeContext runtimeContext) {
        if (projectFs.getNamespaceFactory() == null) {
            return List.of();
        }
        List<String> ns = projectFs.getNamespaceFactory().getNamespace(runtimeContext);
        return ns != null ? ns : List.of();
    }

    /**
     * True when {@code path} falls inside projectFs's namespace folder (e.g. {@code u1} or
     * {@code u1/hi.txt}). Such entries are projectFs's domain and must not leak through the raw,
     * non-namespaced {@code lower} layer with their prefix exposed.
     */
    private static boolean isUnderNamespace(String path, List<String> ns) {
        if (ns.isEmpty() || path == null) {
            return false;
        }
        String prefix = String.join("/", ns);
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.equals(prefix) || p.startsWith(prefix + "/");
    }

    private static void mergeEntries(
            Map<String, FileInfo> merged, LsResult result, List<String> nsToDrop) {
        if (result.isSuccess() && result.entries() != null) {
            for (FileInfo fi : result.entries()) {
                if (isUnderNamespace(fi.path(), nsToDrop)) {
                    continue;
                }
                merged.put(fi.path(), fi);
            }
        }
    }

    private static void mergeGlobMatches(
            Map<String, FileInfo> merged, GlobResult result, List<String> nsToDrop) {
        if (result.isSuccess() && result.matches() != null) {
            for (FileInfo fi : result.matches()) {
                if (isUnderNamespace(fi.path(), nsToDrop)) {
                    continue;
                }
                merged.put(fi.path(), fi);
            }
        }
    }

    private static void mergeMatches(
            Map<String, GrepMatch> merged, GrepResult result, List<String> nsToDrop) {
        if (result.isSuccess() && result.matches() != null) {
            for (GrepMatch m : result.matches()) {
                if (isUnderNamespace(m.path(), nsToDrop)) {
                    continue;
                }
                merged.put(m.path() + ":" + m.line(), m);
            }
        }
    }

    private boolean isWorkspaceQueryPath(String path) {
        return path != null && !path.isBlank() && isWorkspacePath(path);
    }

    // ==================== Path classification ====================

    boolean isWorkspacePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return true;
        }
        String normalized = filePath.replace('\\', '/').strip();

        if (Path.of(normalized).isAbsolute()) {
            return Path.of(normalized).normalize().startsWith(workspaceRoot);
        }

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        for (String prefix : WORKSPACE_PREFIXES) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
