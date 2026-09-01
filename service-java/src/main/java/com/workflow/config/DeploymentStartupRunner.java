package com.workflow.config;

import com.workflow.service.impl.FederatedArtifactCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeploymentStartupRunner implements ApplicationRunner {

    private final WorkflowStorageProperties workflowStorageProperties;
    private final PythonIntegrationProperties pythonIntegrationProperties;
    private final WorkflowSecurityProperties workflowSecurityProperties;
    private final FederatedArtifactCleanupService federatedArtifactCleanupService;
    @Value("${server.address:0.0.0.0}")
    private String serverAddress;
    @Value("${server.port}")
    private Integer serverPort;
    @Value("${spring.servlet.multipart.location}")
    private String multipartLocation;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Path> requiredDirectories = List.of(
                workflowStorageProperties.rootDirPath(),
                workflowStorageProperties.tempDirPath(),
                workflowStorageProperties.modelUploadDirPath(),
                workflowStorageProperties.modelRootDirPath(),
                workflowStorageProperties.datasetRootDirPath(),
                workflowStorageProperties.validationCacheRootDirPath(),
                workflowStorageProperties.federatedModelRootDirPath(),
                Path.of(multipartLocation).toAbsolutePath().normalize(),
                Path.of(pythonIntegrationProperties.getPythonResultRootPath()).toAbsolutePath().normalize(),
                Path.of(pythonIntegrationProperties.getPythonSampleRootPath()).toAbsolutePath().normalize()
        );

        for (Path path : requiredDirectories) {
            Files.createDirectories(path);
        }

        log.info(
                "Deployment directories ready: runtimeMode={}, storageRoot={}, tempDir={}, modelUploadDir={}, modelRoot={}, datasetRoot={}, validationCacheRoot={}, federatedModelRoot={}, multipartTempDir={}, pythonResultRoot={}, pythonSampleRoot={}",
                workflowStorageProperties.getRuntimeMode(),
                workflowStorageProperties.rootDirPath(),
                workflowStorageProperties.tempDirPath(),
                workflowStorageProperties.modelUploadDirPath(),
                workflowStorageProperties.modelRootDirPath(),
                workflowStorageProperties.datasetRootDirPath(),
                workflowStorageProperties.validationCacheRootDirPath(),
                workflowStorageProperties.federatedModelRootDirPath(),
                Path.of(multipartLocation).toAbsolutePath().normalize(),
                pythonIntegrationProperties.getPythonResultRootPath(),
                pythonIntegrationProperties.getPythonSampleRootPath()
        );
        log.info(
                "Deployment integration summary: serverAddress={}, serverPort={}, pythonBaseUrl={}, javaBaseUrl={}, callbackSecret={}, algorithmType={}, serverPathImportEnabled={}, serverPathImportRoots={}, serverPathMappings={}, corsAllowedOrigins={}, corsAllowedOriginPatterns={}",
                serverAddress,
                serverPort,
                pythonIntegrationProperties.getPythonBaseUrl(),
                pythonIntegrationProperties.getJavaBaseUrl(),
                pythonIntegrationProperties.maskedCallbackSecret(),
                pythonIntegrationProperties.getAlgorithmType(),
                workflowStorageProperties.isServerPathImportEnabled(),
                workflowStorageProperties.resolvedServerImportRoots(),
                workflowStorageProperties.resolvedServerPathMappings(),
                workflowSecurityProperties.resolvedAllowedOrigins(),
                workflowSecurityProperties.resolvedAllowedOriginPatterns()
        );

        if (pythonIntegrationProperties.getCallbackSecret() == null || pythonIntegrationProperties.getCallbackSecret().isBlank()) {
            log.warn("Python callback secret is empty. Please set PYTHON_CALLBACK_SECRET before exposing the service publicly.");
        }

        try {
            federatedArtifactCleanupService.cleanupEligibleCompletedWorkflowsOnStartup();
        } catch (RuntimeException ex) {
            log.warn("Startup federated artifact cleanup was skipped: reason={}", ex.getMessage());
        }
    }
}
