package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.validation.ValidationResultClassVO;
import com.workflow.dto.validation.ValidationResultPredictionVO;
import com.workflow.dto.validation.ValidationResultSampleVO;
import com.workflow.dto.validation.ValidationResultViewVO;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowStep;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ValidationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationResultServiceImpl implements ValidationResultService {

    private static final String MODE_STANDALONE = "STANDALONE";
    private static final String MODE_WORKFLOW = "WORKFLOW";

    private final StandaloneValidationMapper standaloneValidationMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowStepMapper workflowStepMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final DatasetAssetMapper datasetAssetMapper;
    private final SysUserMapper sysUserMapper;
    private final LoginUserContext loginUserContext;
    private final ObjectMapper objectMapper;
    private final PythonIntegrationProperties pythonIntegrationProperties;
    private final ValidationImageCacheService validationImageCacheService;

    @Override
    public ValidationResultViewVO getStandaloneResultView(Long validationId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        log.info("Loading standalone validation result view: validationId={}, userId={}", validationId, currentUser.getId());

        StandaloneValidation validation = resolveOwnedValidation(validationId, currentUser);
        ModelAsset modelAsset = validation.getModelAssetId() == null ? null : modelAssetMapper.selectById(validation.getModelAssetId());
        DatasetAsset datasetAsset = validation.getDatasetAssetId() == null ? null : datasetAssetMapper.selectById(validation.getDatasetAssetId());
        JsonNode resultRoot = loadResultFileNode(
                validation.getResultFilePath(),
                validation.getPythonJobId(),
                MODE_STANDALONE,
                validation.getId()
        );
        Path datasetRoot = normalizePath(validation.getDatasetPath());
        if (datasetRoot == null) {
            datasetRoot = normalizePath(resultRoot.path("sourceSummary").path("datasetPath").asText(null));
        }
        JsonNode metricsNode = parseJsonNode(validation.getMetricsJson());
        JsonNode resultMetricsNode = resultRoot.path("metrics");

        ValidationResultViewVO view = new ValidationResultViewVO();
        view.setValidationMode(MODE_STANDALONE);
        view.setValidationId(validation.getId());
        view.setValidationCode(validation.getValidationCode());
        view.setModelName(resolveAssetName(modelAsset == null ? null : modelAsset.getAssetName(), validation.getModelPath()));
        view.setDatasetName(resolveAssetName(datasetAsset == null ? null : datasetAsset.getAssetName(), validation.getDatasetPath()));
        view.setAlgorithmType(validation.getAlgorithmType());
        view.setStatus(validation.getStatus());
        view.setProgress(validation.getProgress());
        view.setPythonJobId(validation.getPythonJobId());
        view.setDatasetSampleCount(datasetAsset == null ? null : datasetAsset.getSampleCount());
        view.setDatasetImageCount(firstInteger(datasetAsset == null ? null : datasetAsset.getImageCount(), readInteger(metricsNode, resultMetricsNode, "totalImages", "total_images")));
        view.setAccuracy(readDouble(metricsNode, resultMetricsNode, "accuracy"));
        view.setPrecision(readDouble(metricsNode, resultMetricsNode, "precision"));
        view.setRecall(readDouble(metricsNode, resultMetricsNode, "recall"));
        view.setMap50(readDouble(metricsNode, resultMetricsNode, "map50", "mAP"));
        view.setMap50_95(readDouble(metricsNode, resultMetricsNode, "map50_95"));
        view.setFallback(readBoolean(metricsNode, resultRoot, "fallback"));
        view.setFallbackReason(readText(metricsNode, resultRoot, "fallbackReason", "fallback_reason"));
        view.setQualityLabel(validation.getQualityLabel());
        view.setErrorMessage(validation.getErrorMessage());
        view.setResultFileAvailable(!resultRoot.isMissingNode());
        view.setVisualizationMode(resolveVisualizationMode(resultRoot));
        view.setResultRetentionStatus("LATEST_ONLY");
        view.setStartedAt(validation.getCreatedAt());
        view.setFinishedAt(validation.getFinishedAt());
        view.setClassResults(buildClassResults(metricsNode, resultMetricsNode));
        view.setSampleResults(buildSampleResults(
                resultRoot,
                MODE_STANDALONE,
                validation.getId(),
                datasetRoot,
                validation.getPythonJobId(),
                currentUser.getRoleCode(),
                currentUser.getId()
        ));
        view.setEmptyVisualizationReason(resolveEmptyVisualizationReason(view.getStatus(), view.getResultFileAvailable(), view.getSampleResults()));

        log.info(
                "Standalone validation result view assembled: validationId={}, status={}, accuracy={}, map50={}, sampleCount={}, resultFileAvailable={}",
                validationId,
                view.getStatus(),
                view.getAccuracy(),
                view.getMap50(),
                view.getSampleResults() == null ? 0 : view.getSampleResults().size(),
                view.getResultFileAvailable()
        );
        return view;
    }

    @Override
    public ValidationResultViewVO getWorkflowResultView(Long workflowId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        log.info("Loading workflow validation result view: workflowId={}, userId={}", workflowId, currentUser.getId());

        Workflow workflow = resolveReadableWorkflow(workflowId, currentUser);
        ModelAsset modelAsset = workflow.getClientModelAssetId() == null ? null : modelAssetMapper.selectById(workflow.getClientModelAssetId());
        DatasetAsset datasetAsset = workflow.getServerDatasetAssetId() == null ? null : datasetAssetMapper.selectById(workflow.getServerDatasetAssetId());
        JsonNode resultRoot = loadResultFileNode(
                workflow.getResultFilePath(),
                workflow.getPythonJobId(),
                MODE_WORKFLOW,
                workflow.getId()
        );
        Path datasetRoot = normalizePath(datasetAsset == null ? null : datasetAsset.getFilePath());
        if (datasetRoot == null) {
            datasetRoot = normalizePath(resultRoot.path("sourceSummary").path("datasetPath").asText(null));
        }
        JsonNode metricsNode = parseJsonNode(workflow.getMetricsJson());
        JsonNode resultMetricsNode = resultRoot.path("metrics");

        ValidationResultViewVO view = new ValidationResultViewVO();
        view.setValidationMode(MODE_WORKFLOW);
        view.setWorkflowId(workflow.getId());
        view.setValidationCode(workflow.getWorkflowCode());
        view.setModelName(resolveAssetName(modelAsset == null ? null : modelAsset.getAssetName(), modelAsset == null ? null : modelAsset.getFilePath()));
        view.setDatasetName(resolveAssetName(datasetAsset == null ? null : datasetAsset.getAssetName(), datasetAsset == null ? null : datasetAsset.getFilePath()));
        view.setAlgorithmType(workflow.getYoloVersion());
        view.setStatus(workflow.getStatus());
        view.setProgress(workflow.getProgress());
        view.setPythonJobId(workflow.getPythonJobId());
        view.setDatasetSampleCount(datasetAsset == null ? null : datasetAsset.getSampleCount());
        view.setDatasetImageCount(firstInteger(datasetAsset == null ? null : datasetAsset.getImageCount(), readInteger(metricsNode, resultMetricsNode, "totalImages", "total_images")));
        view.setAccuracy(readDouble(metricsNode, resultMetricsNode, "accuracy"));
        view.setPrecision(readDouble(metricsNode, resultMetricsNode, "precision"));
        view.setRecall(readDouble(metricsNode, resultMetricsNode, "recall"));
        view.setMap50(readDouble(metricsNode, resultMetricsNode, "map50", "mAP"));
        view.setMap50_95(readDouble(metricsNode, resultMetricsNode, "map50_95"));
        view.setFallback(readBoolean(metricsNode, resultRoot, "fallback"));
        view.setFallbackReason(readText(metricsNode, resultRoot, "fallbackReason", "fallback_reason"));
        view.setQualityLabel(null);
        view.setErrorMessage(workflow.getErrorMessage());
        view.setResultFileAvailable(!resultRoot.isMissingNode());
        view.setVisualizationMode(resolveVisualizationMode(resultRoot));
        view.setResultRetentionStatus(StringUtils.hasText(workflow.getResultRetentionStatus())
                ? workflow.getResultRetentionStatus()
                : ResultArtifactCleanupService.WORKFLOW_RESULT_TEMPORARY);
        view.setStartedAt(resolveWorkflowValidationStartedAt(workflow.getId(), workflow.getCreatedAt()));
        view.setFinishedAt(isTerminalStatus(workflow.getStatus()) ? workflow.getUpdatedAt() : null);
        view.setClassResults(buildClassResults(metricsNode, resultMetricsNode));
        view.setSampleResults(buildSampleResults(
                resultRoot,
                MODE_WORKFLOW,
                workflow.getId(),
                datasetRoot,
                workflow.getPythonJobId(),
                currentUser.getRoleCode(),
                currentUser.getId()
        ));
        view.setEmptyVisualizationReason(resolveEmptyVisualizationReason(view.getStatus(), view.getResultFileAvailable(), view.getSampleResults()));

        log.info(
                "Workflow validation result view assembled: workflowId={}, status={}, accuracy={}, map50={}, sampleCount={}, resultFileAvailable={}",
                workflowId,
                view.getStatus(),
                view.getAccuracy(),
                view.getMap50(),
                view.getSampleResults() == null ? 0 : view.getSampleResults().size(),
                view.getResultFileAvailable()
        );
        return view;
    }

    @Override
    public void warmStandaloneResultCache(Long validationId) {
        StandaloneValidation validation = standaloneValidationMapper.selectById(validationId);
        if (validation == null || Objects.equals(validation.getIsDeleted(), 1)) {
            log.warn("Skip warming standalone validation cache because record does not exist: validationId={}", validationId);
            return;
        }

        ModelAsset modelAsset = validation.getModelAssetId() == null ? null : modelAssetMapper.selectById(validation.getModelAssetId());
        DatasetAsset datasetAsset = validation.getDatasetAssetId() == null ? null : datasetAssetMapper.selectById(validation.getDatasetAssetId());
        String roleCode = resolveStandaloneRoleCode(validation, modelAsset, datasetAsset);
        if (!StringUtils.hasText(roleCode) || validation.getUserId() == null) {
            log.warn(
                "Skip warming standalone validation cache because roleCode or userId is missing: validationId={}, roleCode={}, userId={}",
                    validationId,
                    roleCode,
                    validation.getUserId()
            );
            return;
        }

        JsonNode resultRoot = loadResultFileNode(
                validation.getResultFilePath(),
                validation.getPythonJobId(),
                MODE_STANDALONE,
                validation.getId()
        );
        Path datasetRoot = normalizePath(validation.getDatasetPath());
        if (datasetRoot == null) {
            datasetRoot = normalizePath(resultRoot.path("sourceSummary").path("datasetPath").asText(null));
        }
        ValidationImageCacheService.CachedValidationImageManifest manifest = ensureCachedSampleImages(
                resultRoot,
                MODE_STANDALONE,
                validation.getId(),
                datasetRoot,
                validation.getPythonJobId(),
                null,
                roleCode,
                validation.getUserId()
        );
        log.info(
                "Warm standalone validation cache completed: validationId={}, roleCode={}, userId={}, cacheDir={}, cachedImageCount={}",
                validationId,
                roleCode,
                validation.getUserId(),
                manifest == null ? null : manifest.getCacheDir(),
                manifest == null || manifest.getEntries() == null ? 0 : manifest.getEntries().size()
        );
    }

    @Override
    public void warmWorkflowResultCache(Long workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || Objects.equals(workflow.getIsDeleted(), 1)) {
            log.warn("Skip warming workflow validation cache because workflow does not exist: workflowId={}", workflowId);
            return;
        }

        DatasetAsset datasetAsset = workflow.getServerDatasetAssetId() == null ? null : datasetAssetMapper.selectById(workflow.getServerDatasetAssetId());
        JsonNode resultRoot = loadResultFileNode(
                workflow.getResultFilePath(),
                workflow.getPythonJobId(),
                MODE_WORKFLOW,
                workflow.getId()
        );
        Path datasetRoot = normalizePath(datasetAsset == null ? null : datasetAsset.getFilePath());
        if (datasetRoot == null) {
            datasetRoot = normalizePath(resultRoot.path("sourceSummary").path("datasetPath").asText(null));
        }

        warmWorkflowResultCacheForOwner(workflow, workflow.getServerUserId(), "SERVER", resultRoot, datasetRoot);
        warmWorkflowResultCacheForOwner(workflow, workflow.getInitiatorUserId(), "CLIENT", resultRoot, datasetRoot);
    }

    @Override
    public Path getStandaloneSampleImagePath(Long validationId, int sampleIndex) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        StandaloneValidation validation = resolveOwnedValidation(validationId, currentUser);
        Path cachedPath = validationImageCacheService.resolveCachedSampleImage(
                currentUser.getRoleCode(),
                currentUser.getId(),
                MODE_STANDALONE,
                validation.getId(),
                sampleIndex
        );
        if (cachedPath != null) {
            return cachedPath;
        }
        JsonNode resultRoot = loadResultFileNode(
                validation.getResultFilePath(),
                validation.getPythonJobId(),
                MODE_STANDALONE,
                validation.getId()
        );
        Path datasetRoot = normalizePath(validation.getDatasetPath());
        if (datasetRoot == null) {
            datasetRoot = normalizePath(resultRoot.path("sourceSummary").path("datasetPath").asText(null));
        }
        ensureCachedSampleImages(
                resultRoot,
                MODE_STANDALONE,
                validation.getId(),
                datasetRoot,
                validation.getPythonJobId(),
                null,
                currentUser.getRoleCode(),
                currentUser.getId()
        );
        cachedPath = validationImageCacheService.resolveCachedSampleImage(
                currentUser.getRoleCode(),
                currentUser.getId(),
                MODE_STANDALONE,
                validation.getId(),
                sampleIndex
        );
        if (cachedPath != null) {
            log.info(
                    "Resolved standalone validation sample image from cache: validationId={}, sampleIndex={}, cachePath={}",
                    validationId,
                    sampleIndex,
                    cachedPath
            );
            return cachedPath;
        }
        return resolveSampleImagePath(resultRoot, sampleIndex, datasetRoot, MODE_STANDALONE, validation.getId());
    }

    @Override
    public Path getWorkflowSampleImagePath(Long workflowId, int sampleIndex) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        Workflow workflow = resolveReadableWorkflow(workflowId, currentUser);
        Path cachedPath = validationImageCacheService.resolveCachedSampleImage(
                currentUser.getRoleCode(),
                currentUser.getId(),
                MODE_WORKFLOW,
                workflow.getId(),
                sampleIndex
        );
        if (cachedPath != null) {
            return cachedPath;
        }
        DatasetAsset datasetAsset = workflow.getServerDatasetAssetId() == null ? null : datasetAssetMapper.selectById(workflow.getServerDatasetAssetId());
        JsonNode resultRoot = loadResultFileNode(
                workflow.getResultFilePath(),
                workflow.getPythonJobId(),
                MODE_WORKFLOW,
                workflow.getId()
        );
        Path datasetRoot = normalizePath(datasetAsset == null ? null : datasetAsset.getFilePath());
        if (datasetRoot == null) {
            datasetRoot = normalizePath(resultRoot.path("sourceSummary").path("datasetPath").asText(null));
        }
        ensureCachedSampleImages(
                resultRoot,
                MODE_WORKFLOW,
                workflow.getId(),
                datasetRoot,
                workflow.getPythonJobId(),
                null,
                currentUser.getRoleCode(),
                currentUser.getId()
        );
        cachedPath = validationImageCacheService.resolveCachedSampleImage(
                currentUser.getRoleCode(),
                currentUser.getId(),
                MODE_WORKFLOW,
                workflow.getId(),
                sampleIndex
        );
        if (cachedPath != null) {
            log.info(
                    "Resolved workflow validation sample image from cache: workflowId={}, sampleIndex={}, cachePath={}",
                    workflowId,
                    sampleIndex,
                    cachedPath
            );
            return cachedPath;
        }
        return resolveSampleImagePath(resultRoot, sampleIndex, datasetRoot, MODE_WORKFLOW, workflow.getId());
    }

    private StandaloneValidation resolveOwnedValidation(Long validationId, SysUser currentUser) {
        StandaloneValidation validation = standaloneValidationMapper.selectOne(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getId, validationId)
                        .eq(StandaloneValidation::getUserId, currentUser.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (validation == null) {
            throw new RuntimeException("Standalone validation does not exist or access is denied.");
        }
        return validation;
    }

    private Workflow resolveReadableWorkflow(Long workflowId, SysUser currentUser) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || Objects.equals(workflow.getIsDeleted(), 1)) {
            throw new RuntimeException("Workflow does not exist.");
        }
        if ("CLIENT".equalsIgnoreCase(currentUser.getRoleCode())) {
            if (!Objects.equals(workflow.getInitiatorUserId(), currentUser.getId())) {
                throw new RuntimeException("Workflow does not exist or access is denied.");
            }
            return workflow;
        }
        if ("SERVER".equalsIgnoreCase(currentUser.getRoleCode())) {
            if (!Objects.equals(workflow.getServerUserId(), currentUser.getId())) {
                throw new RuntimeException("Workflow does not exist or access is denied.");
            }
            return workflow;
        }
        throw new RuntimeException("Unknown role. Access denied.");
    }

    private JsonNode loadResultFileNode(String rawResultFilePath, String pythonJobId, String mode, Long ownerId) {
        Path resultFilePath = resolveResultFilePath(rawResultFilePath, pythonJobId);
        if (resultFilePath == null) {
            log.info("Validation result file path is empty: mode={}, ownerId={}", mode, ownerId);
            return MissingNode.getInstance();
        }

        if (!Files.exists(resultFilePath) || !Files.isRegularFile(resultFilePath)) {
            log.warn("Validation result file does not exist: mode={}, ownerId={}, resultFilePath={}", mode, ownerId, resultFilePath);
            return MissingNode.getInstance();
        }

        try {
            log.info("Reading validation result file: mode={}, ownerId={}, resultFilePath={}", mode, ownerId, resultFilePath);
            return objectMapper.readTree(resultFilePath.toFile());
        } catch (Exception ex) {
            log.error("Failed to parse validation result file: mode={}, ownerId={}, resultFilePath={}", mode, ownerId, resultFilePath, ex);
            return MissingNode.getInstance();
        }
    }

    private Path resolveResultFilePath(String rawResultFilePath, String pythonJobId) {
        String candidate = rawResultFilePath == null ? null : rawResultFilePath.trim();
        if (StringUtils.hasText(candidate) && candidate.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(candidate);
                candidate = node.path("filePath").asText(null);
            } catch (Exception ex) {
                log.warn("Failed to parse persisted resultFilePath json payload: {}", rawResultFilePath, ex);
                candidate = null;
            }
        }

        if (StringUtils.hasText(candidate)) {
            return Paths.get(candidate).toAbsolutePath().normalize();
        }

        if (!StringUtils.hasText(pythonJobId) || !StringUtils.hasText(pythonIntegrationProperties.getPythonResultRootPath())) {
            return null;
        }

        Path fallbackPath = Paths.get(pythonIntegrationProperties.getPythonResultRootPath())
                .resolve(pythonJobId + "_result.json")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(fallbackPath) && Files.isRegularFile(fallbackPath)) {
            log.info("Resolved validation result file from pythonJobId fallback: pythonJobId={}, fallbackPath={}", pythonJobId, fallbackPath);
            return fallbackPath;
        }
        return null;
    }

    private JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("Failed to parse metrics json", ex);
            return MissingNode.getInstance();
        }
    }

    private String resolveAssetName(String assetName, String path) {
        if (StringUtils.hasText(assetName)) {
            return assetName;
        }
        if (!StringUtils.hasText(path)) {
            return null;
        }
        return new File(path).getName();
    }

    private Integer firstInteger(Integer preferred, Integer fallback) {
        return preferred != null ? preferred : fallback;
    }

    private Double readDouble(JsonNode primaryNode, JsonNode fallbackNode, String... fieldNames) {
        JsonNode node = findNode(primaryNode, fieldNames);
        if (node.isMissingNode() && fallbackNode != null) {
            node = findNode(fallbackNode, fieldNames);
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer readInteger(JsonNode primaryNode, JsonNode fallbackNode, String... fieldNames) {
        JsonNode node = findNode(primaryNode, fieldNames);
        if (node.isMissingNode() && fallbackNode != null) {
            node = findNode(fallbackNode, fieldNames);
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean readBoolean(JsonNode primaryNode, JsonNode fallbackNode, String... fieldNames) {
        JsonNode node = findNode(primaryNode, fieldNames);
        if (node.isMissingNode() && fallbackNode != null) {
            node = findNode(fallbackNode, fieldNames);
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return Boolean.parseBoolean(node.asText());
        }
        return null;
    }

    private String readText(JsonNode primaryNode, JsonNode fallbackNode, String... fieldNames) {
        JsonNode node = findNode(primaryNode, fieldNames);
        if (node.isMissingNode() && fallbackNode != null) {
            node = findNode(fallbackNode, fieldNames);
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private JsonNode findNode(JsonNode root, String... fieldNames) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return MissingNode.getInstance();
        }
        for (String fieldName : fieldNames) {
            if (root.has(fieldName)) {
                return root.get(fieldName);
            }
        }
        return MissingNode.getInstance();
    }

    private String resolveVisualizationMode(JsonNode resultRoot) {
        if (resultRoot == null || resultRoot.isMissingNode()) {
            return "METRICS_ONLY";
        }
        String value = resultRoot.path("visualizationMode").asText(null);
        return StringUtils.hasText(value) ? value : "METRICS_ONLY";
    }

    private List<ValidationResultClassVO> buildClassResults(JsonNode metricsNode, JsonNode resultMetricsNode) {
        JsonNode source = findNode(metricsNode, "perClassResults");
        if (source.isMissingNode()) {
            source = findNode(resultMetricsNode, "perClassResults");
        }

        List<ValidationResultClassVO> classResults = new ArrayList<>();
        if (source == null || source.isMissingNode() || !source.isObject()) {
            return classResults;
        }

        Iterator<String> fieldNames = source.fieldNames();
        while (fieldNames.hasNext()) {
            String className = fieldNames.next();
            JsonNode node = source.path(className);
            ValidationResultClassVO item = new ValidationResultClassVO();
            item.setClassName(className);
            item.setCategory(node.path("category").asText("other"));
            item.setCount(node.path("count").asInt(0));
            item.setAvgConfidence(readDouble(node, null, "avgConfidence", "avg_confidence"));
            classResults.add(item);
        }

        classResults.sort(Comparator.comparing(ValidationResultClassVO::getCount, Comparator.nullsLast(Comparator.reverseOrder())));
        return classResults;
    }

    private List<ValidationResultSampleVO> buildSampleResults(JsonNode resultRoot,
                                                              String mode,
                                                              Long ownerId,
                                                              Path datasetRoot,
                                                              String pythonJobId,
                                                              String roleCode,
                                                              Long userId) {
        List<ValidationResultSampleVO> sampleResults = new ArrayList<>();
        ValidationImageCacheService.CachedValidationImageManifest existingManifest =
                validationImageCacheService.getCachedManifest(roleCode, userId, mode, ownerId);
        if (resultRoot == null || resultRoot.isMissingNode()) {
            if (existingManifest != null) {
                sampleResults.addAll(buildSampleResultsFromCacheManifest(existingManifest, mode, ownerId));
            }
            return sampleResults;
        }

        ArrayNode source = resolveSampleResultNodes(resultRoot, pythonJobId, mode, ownerId);
        if ((source == null || source.isEmpty()) && existingManifest != null) {
            sampleResults.addAll(buildSampleResultsFromCacheManifest(existingManifest, mode, ownerId));
            log.info(
                    "Prepared validation sample results from existing cache manifest: mode={}, ownerId={}, roleCode={}, userId={}, sampleCount={}",
                    mode,
                    ownerId,
                    roleCode,
                    userId,
                    sampleResults.size()
            );
            return sampleResults;
        }
        if (source == null || source.isEmpty()) {
            return sampleResults;
        }

        List<ValidationImageCacheService.SourceSampleImage> cacheSourceImages = new ArrayList<>();
        int imageUrlCount = 0;
        for (int index = 0; index < source.size(); index++) {
            JsonNode node = source.get(index);
            ValidationResultSampleVO item = new ValidationResultSampleVO();
            item.setSampleIndex(index);
            item.setImageName(readText(node, null, "imageName", "image_name"));
            item.setPredictionCount(readInteger(node, null, "predictionCount", "prediction_count"));

            Path annotatedPath = normalizePath(readText(node, null, "annotatedImagePath", "annotated_image_path"));
            boolean annotatedImageAvailable = isSafeSamplePath(annotatedPath, datasetRoot);
            item.setIsAnnotated(annotatedImageAvailable);
            item.setAnnotatedImageAvailable(annotatedImageAvailable);
            item.setPredictions(buildPredictions(node.path("predictions")));
            sampleResults.add(item);

            Path displayPath = resolveDisplaySamplePath(node, datasetRoot);
            if (displayPath == null) {
                log.warn(
                        "Validation sample image is unavailable: mode={}, ownerId={}, sampleIndex={}, rawImagePath={}, rawAnnotatedPath={}",
                        mode,
                        ownerId,
                        index,
                        readText(node, null, "imagePath", "image_path"),
                        readText(node, null, "annotatedImagePath", "annotated_image_path", "renderedImagePath", "rendered_image_path")
                );
                continue;
            }

            ValidationImageCacheService.SourceSampleImage sourceImage = new ValidationImageCacheService.SourceSampleImage();
            sourceImage.setSampleIndex(index);
            sourceImage.setImageName(item.getImageName());
            sourceImage.setSourcePath(displayPath);
            sourceImage.setAnnotatedImage(annotatedImageAvailable);
            cacheSourceImages.add(sourceImage);
        }

        ValidationImageCacheService.CachedValidationImageManifest manifest = ensureCachedSampleImages(
                resultRoot,
                mode,
                ownerId,
                datasetRoot,
                pythonJobId,
                cacheSourceImages,
                roleCode,
                userId
        );
        if (manifest == null) {
            manifest = existingManifest;
        }
        Set<Integer> cachedSampleIndexes = new HashSet<>();
        if (manifest != null && manifest.getEntries() != null) {
            for (ValidationImageCacheService.CachedValidationImageEntry entry : manifest.getEntries()) {
                if (entry.getSampleIndex() != null) {
                    cachedSampleIndexes.add(entry.getSampleIndex());
                }
            }
        }

        for (ValidationResultSampleVO item : sampleResults) {
            if (cachedSampleIndexes.contains(item.getSampleIndex())) {
                item.setImageUrl(buildSampleImageUrl(mode, ownerId, item.getSampleIndex()));
                imageUrlCount++;
                log.info(
                        "Prepared validation sample image url: mode={}, ownerId={}, sampleIndex={}, imageUrl={}",
                        mode,
                        ownerId,
                        item.getSampleIndex(),
                        item.getImageUrl()
                );
            } else {
                log.warn(
                        "Validation sample imageUrl is empty after cache lookup: mode={}, ownerId={}, sampleIndex={}, roleCode={}, userId={}, imageName={}",
                        mode,
                        ownerId,
                        item.getSampleIndex(),
                        roleCode,
                        userId,
                        item.getImageName()
                );
            }
        }

        log.info(
                "Prepared validation sample results: mode={}, ownerId={}, roleCode={}, userId={}, sampleCount={}, cacheSourceCount={}, imageUrlCount={}",
                mode,
                ownerId,
                roleCode,
                userId,
                sampleResults.size(),
                cacheSourceImages.size(),
                imageUrlCount
        );
        return sampleResults;
    }

    private ValidationImageCacheService.CachedValidationImageManifest ensureCachedSampleImages(JsonNode resultRoot,
                                                                                                String mode,
                                                                                                Long ownerId,
                                                                                                Path datasetRoot,
                                                                                                String pythonJobId,
                                                                                                List<ValidationImageCacheService.SourceSampleImage> sourceImages,
                                                                                                String roleCode,
                                                                                                Long userId) {
        if (!StringUtils.hasText(roleCode) || userId == null) {
            return null;
        }

        List<ValidationImageCacheService.SourceSampleImage> effectiveSources = sourceImages;
        if (effectiveSources == null) {
            effectiveSources = extractCacheSourceImages(resultRoot, datasetRoot, pythonJobId, mode, ownerId);
        }
        if (effectiveSources == null || effectiveSources.isEmpty()) {
            log.info(
                    "No validation sample images available for cache: mode={}, ownerId={}, roleCode={}, userId={}, pythonJobId={}",
                    mode,
                    ownerId,
                    roleCode,
                    userId,
                    pythonJobId
            );
            return null;
        }

        log.info(
                "Validation source images resolved for cache: mode={}, ownerId={}, roleCode={}, userId={}, pythonJobId={}, sourceImages={}",
                mode,
                ownerId,
                roleCode,
                userId,
                pythonJobId,
                effectiveSources.stream()
                        .map(item -> String.format(
                                Locale.ROOT,
                                "{sampleIndex=%d,imageName=%s,sourcePath=%s,exists=%s,annotated=%s}",
                                item.getSampleIndex(),
                                item.getImageName(),
                                item.getSourcePath(),
                                item.getSourcePath() != null && Files.exists(item.getSourcePath()),
                                item.getAnnotatedImage()
                        ))
                        .collect(Collectors.toList())
        );

        return validationImageCacheService.cacheValidationResultImages(
                roleCode,
                userId,
                mode,
                ownerId,
                effectiveSources
        );
    }

    private List<ValidationImageCacheService.SourceSampleImage> extractCacheSourceImages(JsonNode resultRoot,
                                                                                         Path datasetRoot,
                                                                                         String pythonJobId,
                                                                                         String mode,
                                                                                         Long ownerId) {
        ArrayNode sourceNodes = resolveSampleResultNodes(resultRoot, pythonJobId, mode, ownerId);
        List<ValidationImageCacheService.SourceSampleImage> sourceImages = new ArrayList<>();
        for (int index = 0; index < sourceNodes.size(); index++) {
            JsonNode node = sourceNodes.get(index);
            Path displayPath = resolveDisplaySamplePath(node, datasetRoot);
            if (displayPath == null) {
                continue;
            }

            Path annotatedPath = normalizePath(readText(node, null, "annotatedImagePath", "annotated_image_path"));
            ValidationImageCacheService.SourceSampleImage sourceImage = new ValidationImageCacheService.SourceSampleImage();
            sourceImage.setSampleIndex(index);
            sourceImage.setImageName(readText(node, null, "imageName", "image_name"));
            sourceImage.setSourcePath(displayPath);
            sourceImage.setAnnotatedImage(isSafeSamplePath(annotatedPath, datasetRoot));
            sourceImages.add(sourceImage);
        }
        return sourceImages;
    }

    private ArrayNode resolveSampleResultNodes(JsonNode resultRoot,
                                               String pythonJobId,
                                               String mode,
                                               Long ownerId) {
        if (resultRoot != null && !resultRoot.isMissingNode() && resultRoot.path("sampleResults").isArray()) {
            JsonNode directNode = resultRoot.path("sampleResults");
            log.info(
                    "Resolved sampleResults from result file: mode={}, ownerId={}, pythonJobId={}, sampleResults={}",
                    mode,
                    ownerId,
                    pythonJobId,
                    directNode.toString()
            );
            if (directNode.size() > 0 && directNode instanceof ArrayNode) {
                return (ArrayNode) directNode;
            }
        }

        ArrayNode fallbackNodes = objectMapper.createArrayNode();
        Path fallbackSampleDir = resolvePythonSampleDirectory(pythonJobId);
        if (fallbackSampleDir == null || !Files.exists(fallbackSampleDir) || !Files.isDirectory(fallbackSampleDir)) {
            return fallbackNodes;
        }

        try (Stream<Path> stream = Files.list(fallbackSampleDir)) {
            List<Path> imageFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isImageFile)
                    .sorted()
                    .collect(Collectors.toList());

            List<Path> preferredFiles = imageFiles.stream()
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains("_rendered"))
                    .collect(Collectors.toList());
            List<Path> selectedFiles = preferredFiles.isEmpty() ? imageFiles : preferredFiles;

            int sampleIndex = 0;
            for (Path imageFile : selectedFiles) {
                ObjectNode sampleNode = objectMapper.createObjectNode();
                sampleNode.put("imageName", imageFile.getFileName().toString());
                sampleNode.put("predictionCount", 0);
                sampleNode.put("annotatedImagePath", imageFile.toString());
                sampleNode.set("predictions", objectMapper.createArrayNode());
                fallbackNodes.add(sampleNode);
                sampleIndex++;
                if (sampleIndex >= 10) {
                    break;
                }
            }

            if (!fallbackNodes.isEmpty()) {
                log.info(
                        "Resolved sample results from python sample directory fallback: mode={}, ownerId={}, pythonJobId={}, sampleDir={}, sampleCount={}, imageFiles={}",
                        mode,
                        ownerId,
                        pythonJobId,
                        fallbackSampleDir,
                        fallbackNodes.size(),
                        selectedFiles.stream().map(Path::toString).collect(Collectors.toList())
                );
            }
        } catch (Exception ex) {
            log.warn(
                    "Failed to resolve sample results from python sample directory fallback: mode={}, ownerId={}, pythonJobId={}, sampleDir={}",
                    mode,
                    ownerId,
                    pythonJobId,
                    fallbackSampleDir,
                    ex
            );
        }
        return fallbackNodes;
    }

    private String buildSampleImageUrl(String mode, Long ownerId, Integer sampleIndex) {
        if (MODE_STANDALONE.equals(mode)) {
            return "/api/validation-results/standalone/" + ownerId + "/samples/" + sampleIndex + "/image";
        }
        return "/api/validation-results/workflows/" + ownerId + "/samples/" + sampleIndex + "/image";
    }

    private void warmWorkflowResultCacheForOwner(Workflow workflow,
                                                 Long userId,
                                                 String roleCode,
                                                 JsonNode resultRoot,
                                                 Path datasetRoot) {
        if (userId == null || !StringUtils.hasText(roleCode)) {
            return;
        }
        ValidationImageCacheService.CachedValidationImageManifest manifest = ensureCachedSampleImages(
                resultRoot,
                MODE_WORKFLOW,
                workflow.getId(),
                datasetRoot,
                workflow.getPythonJobId(),
                null,
                roleCode,
                userId
        );
        log.info(
                "Warm workflow validation cache completed: workflowId={}, roleCode={}, userId={}, cacheDir={}, cachedImageCount={}",
                workflow.getId(),
                roleCode,
                userId,
                manifest == null ? null : manifest.getCacheDir(),
                manifest == null || manifest.getEntries() == null ? 0 : manifest.getEntries().size()
        );
    }

    private String resolveStandaloneRoleCode(StandaloneValidation validation, ModelAsset modelAsset, DatasetAsset datasetAsset) {
        if (modelAsset != null && StringUtils.hasText(modelAsset.getOwnerRoleCode())) {
            return modelAsset.getOwnerRoleCode();
        }
        if (datasetAsset != null && StringUtils.hasText(datasetAsset.getOwnerRoleCode())) {
            return datasetAsset.getOwnerRoleCode();
        }
        if (validation != null && validation.getUserId() != null) {
            SysUser user = sysUserMapper.selectById(validation.getUserId());
            if (user != null && StringUtils.hasText(user.getRoleCode())) {
                return user.getRoleCode();
            }
        }
        return null;
    }

    private List<ValidationResultPredictionVO> buildPredictions(JsonNode predictionNodes) {
        List<ValidationResultPredictionVO> predictions = new ArrayList<>();
        if (predictionNodes == null || !predictionNodes.isArray()) {
            return predictions;
        }

        for (JsonNode node : predictionNodes) {
            ValidationResultPredictionVO item = new ValidationResultPredictionVO();
            item.setLabel(node.path("label").asText("-"));
            item.setConfidence(readDouble(node, null, "confidence"));
            item.setCategory(node.path("category").asText("other"));
            item.setBbox(readDoubleList(node.path("bbox")));
            predictions.add(item);
        }
        return predictions;
    }

    private List<ValidationResultSampleVO> buildSampleResultsFromCacheManifest(ValidationImageCacheService.CachedValidationImageManifest manifest,
                                                                               String mode,
                                                                               Long ownerId) {
        List<ValidationResultSampleVO> sampleResults = new ArrayList<>();
        if (manifest == null || manifest.getEntries() == null) {
            return sampleResults;
        }
        manifest.getEntries().stream()
                .sorted(Comparator.comparing(ValidationImageCacheService.CachedValidationImageEntry::getSampleIndex))
                .forEach(entry -> {
                    ValidationResultSampleVO item = new ValidationResultSampleVO();
                    item.setSampleIndex(entry.getSampleIndex());
                    item.setImageName(entry.getImageName());
                    item.setImageUrl(buildSampleImageUrl(mode, ownerId, entry.getSampleIndex()));
                    item.setIsAnnotated(Boolean.TRUE.equals(entry.getAnnotatedImage()));
                    item.setAnnotatedImageAvailable(Boolean.TRUE.equals(entry.getAnnotatedImage()));
                    item.setPredictionCount(0);
                    item.setPredictions(new ArrayList<>());
                    sampleResults.add(item);
                });
        return sampleResults;
    }

    private String resolveEmptyVisualizationReason(String status,
                                                   Boolean resultFileAvailable,
                                                   List<ValidationResultSampleVO> sampleResults) {
        if (!isTerminalStatus(status)) {
            return "验证仍在进行中，识别样例会在本次验证结果完成整理后显示。";
        }
        if (!Boolean.TRUE.equals(resultFileAvailable)) {
            return "当前任务尚未生成可读取的结果文件，因此暂时只能显示指标信息。";
        }
        if (sampleResults == null || sampleResults.isEmpty()) {
            return "当前结果文件里还没有可展示的识别样例图片或图片级预测记录。";
        }
        return null;
    }

    private LocalDateTime resolveWorkflowValidationStartedAt(Long workflowId, LocalDateTime fallbackTime) {
        WorkflowStep step = workflowStepMapper.selectOne(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getWorkflowId, workflowId)
                        .eq(WorkflowStep::getStepCode, "START_PYTHON_JOB")
                        .orderByDesc(WorkflowStep::getCreatedAt)
                        .last("limit 1")
        );
        return step == null ? fallbackTime : step.getCreatedAt();
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    private Path resolveSampleImagePath(JsonNode resultRoot,
                                        int sampleIndex,
                                        Path datasetRoot,
                                        String mode,
                                        Long ownerId) {
        if (sampleIndex < 0) {
            throw new RuntimeException("Sample index must not be negative.");
        }
        ArrayNode source = resolveSampleResultNodes(resultRoot, null, mode, ownerId);
        if (resultRoot == null || resultRoot.isMissingNode() || source.isEmpty()) {
            throw new RuntimeException("No sample results are available.");
        }
        if (sampleIndex >= source.size()) {
            throw new RuntimeException("Sample result does not exist.");
        }

        Path displayPath = resolveDisplaySamplePath(source.get(sampleIndex), datasetRoot);
        if (displayPath == null) {
            throw new RuntimeException("Sample image file is not available.");
        }
        log.info("Resolved validation sample image path: mode={}, ownerId={}, sampleIndex={}, imagePath={}", mode, ownerId, sampleIndex, displayPath);
        return displayPath;
    }

    private Path resolveDisplaySamplePath(JsonNode sampleNode, Path datasetRoot) {
        Path annotatedPath = resolveSampleArtifactPath(
                readText(sampleNode, null, "annotatedImagePath", "annotated_image_path", "renderedImagePath", "rendered_image_path"),
                datasetRoot
        );
        if (isSafeSamplePath(annotatedPath, datasetRoot)) {
            return annotatedPath;
        }

        Path imagePath = resolveSampleArtifactPath(readText(sampleNode, null, "imagePath", "image_path"), datasetRoot);
        if (isSafeSamplePath(imagePath, datasetRoot)) {
            return imagePath;
        }
        return null;
    }

    private Path resolveSampleArtifactPath(String value, Path datasetRoot) {
        Path candidate = normalizePath(value);
        if (candidate != null && Files.exists(candidate) && Files.isRegularFile(candidate)) {
            return candidate;
        }

        if (!StringUtils.hasText(value) || datasetRoot == null) {
            return candidate;
        }

        Path directChild = datasetRoot.resolve(value).toAbsolutePath().normalize();
        if (Files.exists(directChild) && Files.isRegularFile(directChild)) {
            return directChild;
        }

        Path imageChild = datasetRoot.resolve("images").resolve(value).toAbsolutePath().normalize();
        if (Files.exists(imageChild) && Files.isRegularFile(imageChild)) {
            return imageChild;
        }

        return candidate;
    }

    private Path resolvePythonSampleDirectory(String pythonJobId) {
        if (!StringUtils.hasText(pythonJobId)) {
            return null;
        }
        Path sampleRoot = normalizePath(pythonIntegrationProperties.getPythonSampleRootPath());
        if (sampleRoot == null) {
            return null;
        }
        return sampleRoot
                .resolve(pythonJobId)
                .toAbsolutePath()
                .normalize();
    }

    private boolean isImageFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    private boolean isSafeSamplePath(Path candidate, Path datasetRoot) {
        if (candidate == null || !Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            return false;
        }

        List<Path> allowedRoots = new ArrayList<>();
        if (datasetRoot != null) {
            allowedRoots.add(datasetRoot);
        }

        Path pythonResultRoot = normalizePath(pythonIntegrationProperties.getPythonResultRootPath());
        if (pythonResultRoot != null) {
            allowedRoots.add(pythonResultRoot);
        }

        Path pythonSampleRoot = normalizePath(pythonIntegrationProperties.getPythonSampleRootPath());
        if (pythonSampleRoot != null) {
            allowedRoots.add(pythonSampleRoot);
        }

        if (allowedRoots.isEmpty()) {
            return true;
        }

        for (Path root : allowedRoots) {
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private Path normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Double> readDoubleList(JsonNode node) {
        List<Double> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item.isNumber()) {
                values.add(item.doubleValue());
            }
        }
        return values;
    }
}
