package com.workflow.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Service
@Slf4j
public class ControlledArtifactDeletionService {

    public DeleteResult deleteRegularFile(String rawPath, Path allowedRoot) {
        Inspection inspection = inspectRegularFile(rawPath, allowedRoot, false);
        if (inspection.status() == InspectionStatus.MISSING) {
            return DeleteResult.alreadyMissing(inspection.path());
        }
        if (inspection.status() != InspectionStatus.VALID) {
            return DeleteResult.rejected(inspection.path(), inspection.reason());
        }

        try {
            Files.delete(inspection.path());
            return DeleteResult.deleted(inspection.path());
        } catch (IOException | SecurityException ex) {
            log.warn("Controlled artifact deletion failed: path={}, reason={}", inspection.path(), ex.getMessage());
            return DeleteResult.failed(inspection.path(), ex.getMessage());
        }
    }

    public Inspection inspectReadableRegularFile(String rawPath, Path allowedRoot) {
        return inspectRegularFile(rawPath, allowedRoot, true);
    }

    private Inspection inspectRegularFile(String rawPath, Path allowedRoot, boolean requireReadable) {
        if (!StringUtils.hasText(rawPath) || allowedRoot == null) {
            return Inspection.rejected(null, "文件路径或受控根目录为空");
        }

        try {
            Path root = allowedRoot.toAbsolutePath().normalize();
            Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
            if (candidate.equals(root) || !candidate.startsWith(root)) {
                return Inspection.rejected(candidate, "文件路径不在受控目录内");
            }
            if (containsSymbolicLink(root, candidate)) {
                return Inspection.rejected(candidate, "文件路径包含符号链接");
            }
            if (!isRealPathWithinRoot(root, candidate)) {
                return Inspection.rejected(candidate, "文件真实路径不在受控目录内");
            }
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Inspection.missing(candidate);
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Inspection.rejected(candidate, "目标不是普通文件");
            }
            if (requireReadable && !Files.isReadable(candidate)) {
                return Inspection.rejected(candidate, "目标文件不可读取");
            }
            return Inspection.valid(candidate);
        } catch (InvalidPathException | IOException | SecurityException ex) {
            return Inspection.rejected(null, "文件路径无效：" + ex.getMessage());
        }
    }

    private boolean isRealPathWithinRoot(Path root, Path candidate) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        Path rootRealPath = root.toRealPath();
        Path probe = candidate;
        while (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS) && !probe.equals(root)) {
            probe = probe.getParent();
            if (probe == null || !probe.startsWith(root)) {
                return false;
            }
        }
        Path probeRealPath = probe.toRealPath();
        return probeRealPath.equals(rootRealPath) || probeRealPath.startsWith(rootRealPath);
    }

    private boolean containsSymbolicLink(Path root, Path candidate) {
        Path current = root;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            return true;
        }
        Path relative = root.relativize(candidate);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    public enum InspectionStatus {
        VALID,
        MISSING,
        REJECTED
    }

    public record Inspection(InspectionStatus status, Path path, String reason) {

        public static Inspection valid(Path path) {
            return new Inspection(InspectionStatus.VALID, path, null);
        }

        public static Inspection missing(Path path) {
            return new Inspection(InspectionStatus.MISSING, path, null);
        }

        public static Inspection rejected(Path path, String reason) {
            return new Inspection(InspectionStatus.REJECTED, path, reason);
        }

        public boolean valid() {
            return status == InspectionStatus.VALID;
        }
    }

    public enum DeleteStatus {
        DELETED,
        ALREADY_MISSING,
        REJECTED,
        FAILED
    }

    public record DeleteResult(DeleteStatus status, Path path, String reason) {

        public static DeleteResult deleted(Path path) {
            return new DeleteResult(DeleteStatus.DELETED, path, null);
        }

        public static DeleteResult alreadyMissing(Path path) {
            return new DeleteResult(DeleteStatus.ALREADY_MISSING, path, null);
        }

        public static DeleteResult rejected(Path path, String reason) {
            return new DeleteResult(DeleteStatus.REJECTED, path, reason);
        }

        public static DeleteResult failed(Path path, String reason) {
            return new DeleteResult(DeleteStatus.FAILED, path, reason);
        }

        public boolean removedOrMissing() {
            return status == DeleteStatus.DELETED || status == DeleteStatus.ALREADY_MISSING;
        }
    }
}
