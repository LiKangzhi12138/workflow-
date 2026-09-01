package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryValidationInputStorageServiceTest {

    private Path storageRoot;
    private TemporaryValidationInputStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Files.createTempDirectory("temporary-validation-inputs-");

        WorkflowStorageProperties properties = new WorkflowStorageProperties();
        properties.setRootPath(storageRoot.toString());
        properties.setTempDir(storageRoot.resolve("tmp").toString());

        service = new TemporaryValidationInputStorageService(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storageRoot == null || !Files.exists(storageRoot)) {
            return;
        }
        try (var walk = Files.walk(storageRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void shouldPersistTemporaryStandaloneInputsIntoConfiguredStorageRoot() throws Exception {
        MockMultipartFile modelFile = new MockMultipartFile(
                "modelFile",
                "yolov8n.pt",
                "application/octet-stream",
                "model-bytes".getBytes()
        );
        MockMultipartFile datasetArchive = new MockMultipartFile(
                "datasetArchive",
                "dataset.zip",
                "application/zip",
                buildDatasetZip()
        );

        TemporaryValidationInputStorageService.PreparedStandaloneInput prepared =
                service.prepareStandaloneInput(modelFile, datasetArchive);

        assertTrue(prepared.getInputRootPath().startsWith(storageRoot.resolve("tmp")));
        assertTrue(Files.exists(prepared.getModelPath()));
        assertTrue(Files.isRegularFile(prepared.getModelPath()));
        assertTrue(Files.size(prepared.getModelPath()) > 0L);
        assertTrue(Files.exists(prepared.getDatasetPath()));
        assertTrue(Files.isDirectory(prepared.getDatasetPath()));
        assertTrue(Files.exists(prepared.getDatasetPath().resolve("images").resolve("sample.jpg")));
        assertEquals(1, prepared.getDatasetImageCount());
    }

    private byte[] buildDatasetZip() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("dataset/images/sample.jpg"));
            zip.write(new byte[]{1, 2, 3, 4});
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("dataset/labels/sample.txt"));
            zip.write("0 0.5 0.5 0.5 0.5".getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
