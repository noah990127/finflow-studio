package com.finflow.studio.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileClassifierTest {
    @Test
    void classifiesParquetAsDataByExtensionOrMediaType() {
        assertThat(WorkspaceFileClassifier.resourceType("经营分析结果.parquet", "application/octet-stream"))
                .isEqualTo("DATA_FILE");
        assertThat(WorkspaceFileClassifier.resourceType("经营分析结果", "application/vnd.apache.parquet"))
                .isEqualTo("DATA_FILE");
        assertThat(WorkspaceFileClassifier.group("经营分析结果.parquet", "application/vnd.apache.parquet"))
                .isEqualTo("DATA");
    }

    @Test
    void preservesExistingOfficeAndKnowledgeClassification() {
        assertThat(WorkspaceFileClassifier.resourceType("经营分析.xlsx", "application/octet-stream"))
                .isEqualTo("DATA_FILE");
        assertThat(WorkspaceFileClassifier.resourceType("经营说明.docx", "application/octet-stream"))
                .isEqualTo("OFFICE_FILE");
        assertThat(WorkspaceFileClassifier.resourceType("经营说明.pdf", "application/pdf"))
                .isEqualTo("KNOWLEDGE_FILE");
    }
}
