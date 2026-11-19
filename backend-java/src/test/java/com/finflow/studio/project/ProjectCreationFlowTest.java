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
    void createsProjectWithoutInventingAnEmptyWorkflow() {
        var project = creation.create("新项目", "工作流按用户任务创建");
        var result = workspace.get(project.id());

        assertThat(result.workflow()).isNull();
        assertThat(result.workflows()).isEmpty();
    }
}
