package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.validation.ValidationResultViewVO;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.ModelDefinitionMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ValidationResultServiceImplDualModelTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowMapper workflows = mock(WorkflowMapper.class);
    private final DatasetAssetMapper datasets = mock(DatasetAssetMapper.class);
    private final ModelDefinitionMapper definitions = mock(ModelDefinitionMapper.class);
    private final LoginUserContext users = mock(LoginUserContext.class);
    private final ValidationImageCacheService cache = mock(ValidationImageCacheService.class);
    private ValidationResultServiceImpl service;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        PythonIntegrationProperties paths = new PythonIntegrationProperties();
        paths.setPythonResultRootPath(temp.resolve("results").toString());
        paths.setPythonSampleRootPath(temp.resolve("samples").toString());
        service = new ValidationResultServiceImpl(mock(StandaloneValidationMapper.class), workflows,
                mock(WorkflowStepMapper.class), mock(ModelAssetMapper.class), definitions, datasets,
                mock(SysUserMapper.class), users, mapper, paths, cache);
        workflow = new Workflow();
        workflow.setId(41L);
        workflow.setWorkflowCode("WF-41");
        workflow.setInitiatorUserId(1L);
        workflow.setServerUserId(2L);
        workflow.setModelDefinitionId(2L);
        workflow.setServerDatasetAssetId(15L);
        workflow.setPythonJobId("latest-job");
        workflow.setStatus("COMPLETED");
        when(workflows.selectById(41L)).thenReturn(workflow);
        SysUser user = new SysUser();
        user.setId(2L);
        user.setRoleCode("SERVER");
        when(users.getCurrentUser()).thenReturn(user);
    }

    @Test
    void mapsRealSegmentationResultAndProtectedSampleVariants() throws Exception {
        Path dataset = Files.createDirectories(temp.resolve("dataset"));
        Path image = Files.write(dataset.resolve("image.png"), new byte[] {1});
        Path sampleDir = Files.createDirectories(temp.resolve("samples/latest-job"));
        Path mask = Files.write(sampleDir.resolve("sample_000_prediction.png"), new byte[] {2});
        Path rendered = Files.write(sampleDir.resolve("sample_000_rendered.jpg"), new byte[] {3});
        DatasetAsset datasetAsset = new DatasetAsset();
        datasetAsset.setFilePath(dataset.toString());
        datasetAsset.setAssetName("小麦验证集");
        when(datasets.selectById(15L)).thenReturn(datasetAsset);
        ModelDefinition definition = new ModelDefinition();
        definition.setCode("INTERNIMAGE_T_UPERNET_WHEAT_V1");
        definition.setDisplayName("InternImage-T 小麦分割");
        definition.setModelFamily("INTERNIMAGE");
        definition.setTaskType("SEMANTIC_SEGMENTATION");
        definition.setRuntimeProfileId("INTERNIMAGE_RUNTIME_V1");
        when(definitions.selectById(2L)).thenReturn(definition);
        workflow.setResultFilePath(writeResult("latest-job", """
                {"taskType":"SEMANTIC_SEGMENTATION","metricsMode":"GROUND_TRUTH",
                 "mIoU":0.7,"mAcc":0.8,"aAcc":0.9,
                 "perClass":[{"classId":0,"className":"background","iou":0.7,"accuracy":0.8},
                             {"classId":1,"className":"healthy_wheat","iou":0.8,"accuracy":0.95},
                             {"classId":2,"className":"lodged_wheat","iou":null,"accuracy":null}]}
                """, image, rendered, mask));
        ValidationImageCacheService.CachedValidationImageManifest manifest = new ValidationImageCacheService.CachedValidationImageManifest();
        manifest.setJobId("latest-job");
        ValidationImageCacheService.CachedValidationImageEntry entry = new ValidationImageCacheService.CachedValidationImageEntry();
        entry.setSampleIndex(0);
        manifest.setEntries(java.util.List.of(entry));
        when(cache.cacheValidationResultImages(anyString(), anyLong(), anyString(), anyLong(), anyString(), anyList()))
                .thenReturn(manifest);

        ValidationResultViewVO view = service.getWorkflowResultView(41L);
        assertEquals("INTERNIMAGE", view.getAlgorithmType());
        assertEquals("SEMANTIC_SEGMENTATION", view.getTaskType());
        assertEquals("INTERNIMAGE_RUNTIME_V1", view.getRuntimeProfileId());
        assertEquals(0.7, view.getMIoU());
        assertNull(view.getMap50());
        assertNull(view.getAccuracy());
        assertEquals(0.95, view.getSegmentationPerClass().get(1).getAccuracy());
        assertNull(view.getSegmentationPerClass().get(2).getAccuracy());
        assertEquals("/api/validation-results/workflows/41/samples/0/source", view.getSampleResults().get(0).getSourceImageUrl());
        assertEquals("/api/validation-results/workflows/41/samples/0/mask", view.getSampleResults().get(0).getPredictionMaskUrl());
        assertEquals(image.toRealPath(), service.getWorkflowSampleArtifactPath(41L, 0, "source").toRealPath());
        assertEquals(mask.toRealPath(), service.getWorkflowSampleArtifactPath(41L, 0, "mask").toRealPath());
        assertThrows(RuntimeException.class, () -> service.getWorkflowSampleArtifactPath(41L, 0, "other"));

        SysUser outsider = new SysUser();
        outsider.setId(99L);
        outsider.setRoleCode("CLIENT");
        when(users.getCurrentUser()).thenReturn(outsider);
        assertThrows(RuntimeException.class, () -> service.getWorkflowSampleArtifactPath(41L, 0, "source"));
        outsider.setId(1L);
        assertEquals(image.toRealPath(), service.getWorkflowSampleArtifactPath(41L, 0, "source").toRealPath());
    }

    @Test
    void keepsYoloDetectionMetricAndRejectsStaleJobArtifact() throws Exception {
        ModelDefinition definition = new ModelDefinition();
        definition.setCode("YOLOV8N_SHEEP_V1");
        definition.setModelFamily("YOLO");
        definition.setTaskType("DETECTION");
        definition.setRuntimeProfileId("YOLO_RUNTIME_V1");
        when(definitions.selectById(2L)).thenReturn(definition);
        Path dataset = Files.createDirectories(temp.resolve("dataset"));
        Path image = Files.write(dataset.resolve("image.jpg"), new byte[] {1});
        Path sampleDir = Files.createDirectories(temp.resolve("samples/latest-job"));
        Path rendered = Files.write(sampleDir.resolve("sample_000_rendered.jpg"), new byte[] {2});
        DatasetAsset datasetAsset = new DatasetAsset();
        datasetAsset.setFilePath(dataset.toString());
        when(datasets.selectById(15L)).thenReturn(datasetAsset);
        workflow.setResultFilePath(writeResult("latest-job", "{\"taskType\":\"DETECTION\",\"map50\":0.91,\"fallback\":false}", image, rendered, null));
        ValidationImageCacheService.CachedValidationImageManifest manifest = new ValidationImageCacheService.CachedValidationImageManifest();
        manifest.setJobId("latest-job");
        ValidationImageCacheService.CachedValidationImageEntry entry = new ValidationImageCacheService.CachedValidationImageEntry();
        entry.setSampleIndex(0);
        manifest.setEntries(java.util.List.of(entry));
        when(cache.cacheValidationResultImages(anyString(), anyLong(), anyString(), anyLong(), anyString(), anyList()))
                .thenReturn(manifest);
        ValidationResultViewVO view = service.getWorkflowResultView(41L);
        assertEquals("YOLO", view.getAlgorithmType());
        assertEquals("DETECTION", view.getTaskType());
        assertEquals(0.91, view.getMap50());
        assertNull(view.getMIoU());
        assertEquals("/api/validation-results/workflows/41/samples/0/image", view.getSampleResults().get(0).getImageUrl());
        assertEquals(1, view.getSampleResults().get(0).getPredictions().size());
        assertEquals("sheep", view.getSampleResults().get(0).getPredictions().get(0).getLabel());
        workflow.setPythonJobId("new-job");
        ValidationImageCacheService.CachedValidationImageManifest stale = new ValidationImageCacheService.CachedValidationImageManifest();
        stale.setJobId("latest-job");
        stale.setEntries(java.util.List.of(entry));
        when(cache.getCachedManifest(anyString(), anyLong(), anyString(), anyLong())).thenReturn(stale);
        ValidationResultViewVO retried = service.getWorkflowResultView(41L);
        assertFalse(retried.getResultFileAvailable());
        assertTrue(retried.getSampleResults().isEmpty());
        assertThrows(RuntimeException.class, () -> service.getWorkflowSampleArtifactPath(41L, 0, "source"));
    }

    private String writeResult(String jobId, String metricsJson, Path image, Path rendered, Path mask) throws Exception {
        Path result = temp.resolve("result.json");
        var root = mapper.createObjectNode();
        root.put("jobId", jobId);
        root.put("taskType", metricsJson.contains("DETECTION") ? "DETECTION" : "SEMANTIC_SEGMENTATION");
        root.set("metrics", mapper.readTree(metricsJson));
        var sample = mapper.createObjectNode();
        sample.put("imageName", image.getFileName().toString());
        sample.put("imagePath", image.toString());
        sample.put("annotatedImagePath", rendered.toString());
        if (mask != null) sample.put("predictionMaskPath", mask.toString());
        var predictions = mapper.createArrayNode();
        if (metricsJson.contains("DETECTION")) {
            predictions.addObject().put("label", "sheep").put("confidence", 0.9)
                    .putArray("bbox").add(1).add(2).add(3).add(4);
        }
        sample.set("predictions", predictions);
        root.putArray("sampleResults").add(sample);
        mapper.writeValue(result.toFile(), root);
        return result.toString();
    }
}
