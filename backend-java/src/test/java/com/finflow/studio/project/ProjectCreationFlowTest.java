package com.finflow.studio.project;

import com.finflow.studio.workspace.WorkspaceResourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finflow-project-creation-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=${java.io.tmpdir}/finflow-project-creation-test"
})
class ProjectCreationFlowTest {
    @Autowired ProjectCreationService creation;
    @Autowired WorkspaceResourceService workspace;

    @Test
    void createsProjectWithReadyToEditDefaultWorkflow() {
        var project = creation.create("新项目", "验证默认工作流");
        var result = workspace.get(project.id());

        assertThat(result.workflow()).isNotNull();
        assertThat(result.workflow().name()).isEqualTo("主工作流");
        assertThat(result.workflow().currentVersion()).isEqualTo(1);
        assertThat(result.workflows()).hasSize(1);
    }
}
