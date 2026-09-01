package com.workflow.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ControlledArtifactDeletionServiceTest {

    @TempDir
    private Path tempDir;

    private final ControlledArtifactDeletionService service = new ControlledArtifactDeletionService();

    @Test
    void shouldDeleteRegularFileInsideControlledRoot() throws Exception {
        Path root = tempDir.resolve("root");
        Path file = root.resolve("workflow").resolve("model.enc");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "encrypted");

        var result = service.deleteRegularFile(file.toString(), root);

        assertEquals(ControlledArtifactDeletionService.DeleteStatus.DELETED, result.status());
        assertFalse(Files.exists(file));
    }

    @Test
    void shouldTreatMissingControlledFileAsIdempotentSuccess() throws Exception {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        Path file = root.resolve("missing.enc");

        var result = service.deleteRegularFile(file.toString(), root);

        assertEquals(ControlledArtifactDeletionService.DeleteStatus.ALREADY_MISSING, result.status());
        assertTrue(result.removedOrMissing());
    }

    @Test
    void shouldRejectPathOutsideControlledRoot() throws Exception {
        Path root = tempDir.resolve("root");
        Path outside = tempDir.resolve("outside.pt");
        Files.createDirectories(root);
        Files.writeString(outside, "model");

        var result = service.deleteRegularFile(outside.toString(), root);

        assertEquals(ControlledArtifactDeletionService.DeleteStatus.REJECTED, result.status());
        assertTrue(Files.exists(outside));
    }

    @Test
    void shouldRejectDirectoryAndControlledRootItself() throws Exception {
        Path root = tempDir.resolve("root");
        Path directory = root.resolve("directory");
        Files.createDirectories(directory);

        var directoryResult = service.deleteRegularFile(directory.toString(), root);
        var rootResult = service.deleteRegularFile(root.toString(), root);

        assertEquals(ControlledArtifactDeletionService.DeleteStatus.REJECTED, directoryResult.status());
        assertEquals(ControlledArtifactDeletionService.DeleteStatus.REJECTED, rootResult.status());
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void shouldRejectSymbolicLinkWhenEnvironmentSupportsIt() throws Exception {
        Path root = tempDir.resolve("root");
        Path target = tempDir.resolve("target.pt");
        Path link = root.resolve("linked.pt");
        Files.createDirectories(root);
        Files.writeString(target, "model");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "当前 Windows 权限或文件系统不支持创建符号链接");
        }

        var result = service.deleteRegularFile(link.toString(), root);

        assertEquals(ControlledArtifactDeletionService.DeleteStatus.REJECTED, result.status());
        assertTrue(Files.exists(target));
        assertTrue(Files.isSymbolicLink(link));
    }
}
