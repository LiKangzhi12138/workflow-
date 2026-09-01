package com.workflow.dto.python;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonCallbackResultFile {

    private String fileName;

    @JsonAlias({"path"})
    private String filePath;
}
