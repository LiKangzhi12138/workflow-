package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonCreateJobRequest;
import com.workflow.dto.python.PythonCreateJobResponse;
import com.workflow.dto.python.PythonCreateJobResponseData;
import com.workflow.dto.validation.CreateStandaloneValidationRequest;
import com.workflow.dto.validation.StandaloneCallbackRequest;
import com.workflow.dto.validation.StandaloneValidationVO;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.SysUser;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ValidationResultService;
import com.workflow.service.python.PythonJobClient;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationServiceImplStandaloneValidationTest {

    private Path tempDir;

    @Mock
    private StandaloneValidationMapper validationMapper;

    @Mock
    private ModelAssetMapper modelAssetMapper;

    @Mock
    private DatasetAssetMapper datasetAssetMapper;

    @Mock
    private LoginUserContext loginUserContext;

    @Mock
    private PythonJobClient pythonJobClient;

    @Mock
    private ValidationImageCacheService validationImageCacheService;

    @Mock
    private ValidationResultService validationResultService;

    @Mock
    private TemporaryValidationInputStorageService temporaryValidationInputStorageService;

    @Mock
    private ResultArtifactCleanupService resultArtifactCleanupService;

    private ValidationServiceImpl validationService;

    private AtomicReference<StandaloneValidation> storedValidation;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("standalone-validation-");

        PythonIntegrationProperties properties = new PythonIntegrationProperties();
        properties.setJavaBaseUrl("http://127.0.0.1:8080");
        properties.setPythonBaseUrl("http://127.0.0.1:8000");
        properties.setCallbackSecret("dev-secret");
        properties.setAlgorithmType("YOLOv10");

        validationService = new ValidationServiceImpl(
                validationMapper,
                modelAssetMapper,
                datasetAssetMapper,
                loginUserContext,
                pythonJobClient,
                properties,
                new ObjectMapper(),
                validationImageCacheService,
                validationResultService,
                temporaryValidationInputStorageService,
                resultArtifactCleanupService
        );

        SysUser currentUser = new SysUser();
        currentUser.setId(11L);
        currentUser.setRoleCode("CLIENT");
        lenient().when(loginUserContext.getCurrentUser()).thenReturn(currentUser);

        storedValidation = new AtomicReference<>();

        lenient().when(validationMapper.insert(any(StandaloneValidation.class))).thenAnswer(invocation -> {
            StandaloneValidation validation = copyValidation(invocation.getArgument(0));
            validation.setId(21L);
            storedValidation.set(validation);
            StandaloneValidation original = invocation.getArgument(0);
            original.setId(21L);
            return 1;
        });

        lenient().when(validationMapper.updateById(any(StandaloneValidation.class))).thenAnswer(invocation -> {
            storedValidation.set(copyValidation(invocation.getArgument(0)));
            return 1;
        });

        lenient().when(validationMapper.selectOne(any())).thenAnswer(invocation -> copyValidation(storedValidation.get()));
        lenient().when(validationMapper.selectById(anyLong())).thenAnswer(invocation -> copyValidation(storedValidation.get()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void shouldSubmitStandaloneValidationForOwnedReadyAssets() throws Exception {
        ModelAsset modelAsset = buildReadyModelAsset(tempDir.resolve("models").resolve("client-yolo.pt"));
        DatasetAsset datasetAsset = buildReadyDatasetAsset(tempDir.resolve("datasets").resolve("dataset-a"));

        when(modelAssetMapper.selectOne(any())).thenReturn(modelAsset);
        when(modelAssetMapper.selectById(5L)).thenReturn(modelAsset);
        when(datasetAssetMapper.selectOne(any())).thenReturn(datasetAsset);
        when(datasetAssetMapper.selectById(9L)).thenReturn(datasetAsset);
        when(pythonJobClient.createJob(any(PythonCreateJobRequest.class))).thenReturn(buildPythonResponse("py-standalone-1"));

        CreateStandaloneValidationRequest request = new CreateStandaloneValidationRequest();
        request.setModelAssetId(5L);
        request.setDatasetAssetId(9L);

        StandaloneValidationVO result = validationService.submitStandaloneValidation(request);

        assertNotNull(result);
        assertEquals("STANDALONE", result.getValidationMode());
        assertEquals("VALIDATING", result.getStatus());
        assertEquals("py-standalone-1", result.getPythonJobId());
        assertEquals("client-yolo", result.getModelAssetName());
        assertEquals("dataset-a", result.getDatasetAssetName());
        assertEquals(Integer.valueOf(128), result.getDatasetImageCount());
        assertEquals("YOLOv10", result.getAlgorithmType());

        ArgumentCaptor<PythonCreateJobRequest> requestCaptor = ArgumentCaptor.forClass(PythonCreateJobRequest.class);
        verify(pythonJobClient).createJob(requestCaptor.capture());
        assertEquals("STANDALONE_VALIDATION", requestCaptor.getValue().getJobType());
        assertEquals(null, requestCaptor.getValue().getWorkflowId());
        assertEquals(21L, requestCaptor.getValue().getStandaloneValidationId());
        assertEquals(modelAsset.getFilePath(), requestCaptor.getValue().getModelPath());
        assertEquals(datasetAsset.getFilePath(), requestCaptor.getValue().getDatasetPath());
    }

    @Test
    void shouldRejectStandaloneValidationWhenModelIsNotOwnedByCurrentUser() {
        when(modelAssetMapper.selectOne(any())).thenReturn(null);

        CreateStandaloneValidationRequest request = new CreateStandaloneValidationRequest();
        request.setModelAssetId(5L);
        request.setDatasetAssetId(9L);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> validationService.submitStandaloneValidation(request)
        );

        assertEquals("\u6240\u9009\u6A21\u578B\u4E0D\u5B58\u5728\u6216\u65E0\u6743\u8BBF\u95EE", error.getMessage());
        verify(validationMapper, never()).insert(any(StandaloneValidation.class));
    }

    @Test
    void shouldRejectWorkflowDecryptModelForNewStandaloneValidation() throws Exception {
        ModelAsset modelAsset = buildReadyModelAsset(tempDir.resolve("models").resolve("workflow-participant.pt"));
        modelAsset.setSourceType(AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT);
        when(modelAssetMapper.selectOne(any())).thenReturn(modelAsset);

        CreateStandaloneValidationRequest request = new CreateStandaloneValidationRequest();
        request.setModelAssetId(5L);
        request.setDatasetAssetId(9L);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> validationService.submitStandaloneValidation(request)
        );

        assertEquals("工作流解密中间模型不能用于新的独立验证，请使用浏览器上传、服务端导入或联邦聚合模型。", error.getMessage());
        verify(validationMapper, never()).insert(any(StandaloneValidation.class));
        verify(datasetAssetMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectStandaloneValidationWhenDatasetDirectoryIsMissing() throws Exception {
        ModelAsset modelAsset = buildReadyModelAsset(tempDir.resolve("models").resolve("client-yolo.pt"));
        DatasetAsset datasetAsset = buildReadyDatasetAsset(tempDir.resolve("datasets").resolve("missing-dataset"));
        Files.deleteIfExists(Path.of(datasetAsset.getFilePath()));

        when(modelAssetMapper.selectOne(any())).thenReturn(modelAsset);
        when(datasetAssetMapper.selectOne(any())).thenReturn(datasetAsset);

        CreateStandaloneValidationRequest request = new CreateStandaloneValidationRequest();
        request.setModelAssetId(5L);
        request.setDatasetAssetId(9L);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> validationService.submitStandaloneValidation(request)
        );

        assertEquals(
                "\u6570\u636E\u96C6\u76EE\u5F55\u5728\u670D\u52A1\u5668\u672C\u5730\u4E0D\u5B58\u5728\uFF1A" + datasetAsset.getFilePath(),
                error.getMessage()
        );
        verify(validationMapper, never()).insert(any(StandaloneValidation.class));
        verify(pythonJobClient, never()).createJob(any());
    }

    @Test
    void shouldNotCleanupTemporaryInputOnValidatingCallback() {
        StandaloneValidation validation = buildTemporaryStandaloneValidation("VALIDATING");
        storedValidation.set(copyValidation(validation));

        StandaloneCallbackRequest request = buildCallbackRequest(validation, "VALIDATING", 35, null);

        validationService.handleStandaloneCallback(request);

        verify(temporaryValidationInputStorageService, never()).cleanupInputRoot(any(), any());
        assertEquals("VALIDATING", storedValidation.get().getStatus());
        assertEquals(null, storedValidation.get().getInputCleanupStatus());
    }

    @Test
    void shouldCleanupTemporaryInputOnCompletedCallback() {
        StandaloneValidation validation = buildTemporaryStandaloneValidation("VALIDATING");
        storedValidation.set(copyValidation(validation));

        StandaloneCallbackRequest request = buildCallbackRequest(validation, "COMPLETED", 100, null);

        validationService.handleStandaloneCallback(request);

        verify(temporaryValidationInputStorageService).cleanupInputRoot(any(), any());
        assertEquals("COMPLETED", storedValidation.get().getStatus());
        assertEquals("COMPLETED", storedValidation.get().getInputCleanupStatus());
    }

    private ModelAsset buildReadyModelAsset(Path modelPath) throws Exception {
        Files.createDirectories(modelPath.getParent());
        Files.writeString(modelPath, "model-bytes");

        ModelAsset modelAsset = new ModelAsset();
        modelAsset.setId(5L);
        modelAsset.setAssetName("client-yolo");
        modelAsset.setOwnerUserId(11L);
        modelAsset.setOwnerRoleCode("CLIENT");
        modelAsset.setYoloVersion("YOLOv10");
        modelAsset.setStatus("READY");
        modelAsset.setFilePathValidated(1);
        modelAsset.setFilePath(modelPath.toString());
        modelAsset.setIsDeleted(0);
        return modelAsset;
    }

    private DatasetAsset buildReadyDatasetAsset(Path datasetPath) throws Exception {
        Files.createDirectories(datasetPath);

        DatasetAsset datasetAsset = new DatasetAsset();
        datasetAsset.setId(9L);
        datasetAsset.setAssetName("dataset-a");
        datasetAsset.setOwnerUserId(11L);
        datasetAsset.setOwnerRoleCode("CLIENT");
        datasetAsset.setStatus("READY");
        datasetAsset.setFilePathValidated(1);
        datasetAsset.setFilePath(datasetPath.toString());
        datasetAsset.setSampleCount(256);
        datasetAsset.setImageCount(128);
        datasetAsset.setIsDeleted(0);
        return datasetAsset;
    }

    private PythonCreateJobResponse buildPythonResponse(String jobId) {
        PythonCreateJobResponse response = new PythonCreateJobResponse();
        response.setCode("OK");
        response.setMessage("accepted");

        PythonCreateJobResponseData data = new PythonCreateJobResponseData();
        data.setJobId(jobId);
        data.setStatus("ACCEPTED");
        response.setData(data);
        return response;
    }

    private StandaloneValidation buildTemporaryStandaloneValidation(String status) {
        StandaloneValidation validation = new StandaloneValidation();
        validation.setId(31L);
        validation.setValidationCode("VALTEMP202604210001");
        validation.setUserId(11L);
        validation.setModelPath(tempDir.resolve("tmp").resolve("model.pt").toString());
        validation.setDatasetPath(tempDir.resolve("tmp").resolve("dataset").toString());
        validation.setAlgorithmType("YOLOv8");
        validation.setStatus(status);
        validation.setProgress(10);
        validation.setPythonJobId("job-temp-001");
        validation.setInputMode("TEMP_UPLOAD");
        validation.setRetainInput(0);
        validation.setInputRootPath(tempDir.resolve("tmp").toString());
        validation.setIsDeleted(0);
        validation.setCreatedAt(LocalDateTime.now());
        validation.setUpdatedAt(LocalDateTime.now());
        return validation;
    }

    private StandaloneCallbackRequest buildCallbackRequest(
            StandaloneValidation validation,
            String status,
            Integer progress,
            String errorMessage
    ) {
        StandaloneCallbackRequest request = new StandaloneCallbackRequest();
        request.setJobId(validation.getPythonJobId());
        request.setJobType("STANDALONE_VALIDATION");
        request.setStandaloneValidationId(validation.getId());
        request.setStatus(status);
        request.setProgress(progress);
        request.setMessage(status);
        request.setErrorMessage(errorMessage);
        request.setSign(signFor(request));
        return request;
    }

    private String signFor(StandaloneCallbackRequest request) {
        try {
            Map<String, Object> payload = new TreeMap<>();
            payload.put("errorMessage", request.getErrorMessage());
            payload.put("jobId", request.getJobId());
            payload.put("jobType", request.getJobType());
            payload.put("message", request.getMessage());
            payload.put("metrics", request.getMetrics());
            payload.put("progress", request.getProgress());
            payload.put("resultFile", request.getResultFile());
            payload.put("standaloneValidationId", request.getStandaloneValidationId());
            payload.put("status", request.getStatus());
            payload.put("workflowId", request.getWorkflowId());

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            String canonicalJson = mapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((canonicalJson + "dev-secret").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private StandaloneValidation copyValidation(StandaloneValidation source) {
        if (source == null) {
            return null;
        }
        StandaloneValidation copy = new StandaloneValidation();
        copy.setId(source.getId());
        copy.setValidationCode(source.getValidationCode());
        copy.setUserId(source.getUserId());
        copy.setModelAssetId(source.getModelAssetId());
        copy.setModelPath(source.getModelPath());
        copy.setDatasetAssetId(source.getDatasetAssetId());
        copy.setDatasetPath(source.getDatasetPath());
        copy.setAlgorithmType(source.getAlgorithmType());
        copy.setStatus(source.getStatus());
        copy.setProgress(source.getProgress());
        copy.setPythonJobId(source.getPythonJobId());
        copy.setMetricsJson(source.getMetricsJson());
        copy.setResultFilePath(source.getResultFilePath());
        copy.setQualityLabel(source.getQualityLabel());
        copy.setErrorMessage(source.getErrorMessage());
        copy.setInputMode(source.getInputMode());
        copy.setRetainInput(source.getRetainInput());
        copy.setInputRootPath(source.getInputRootPath());
        copy.setInputCleanupStatus(source.getInputCleanupStatus());
        copy.setInputCleanedAt(source.getInputCleanedAt());
        copy.setFinishedAt(source.getFinishedAt());
        copy.setIsDeleted(source.getIsDeleted());
        copy.setCreatedAt(source.getCreatedAt() == null ? LocalDateTime.now() : source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt() == null ? LocalDateTime.now() : source.getUpdatedAt());
        return copy;
    }
}
