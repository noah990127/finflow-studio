package com.finflow.studio.knowledge;

import com.finflow.studio.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:native-document;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "finflow.storage.root=./target/native-document-storage", "finflow.ai.enabled=false", "finflow.worker.base-url=http://127.0.0.1:1"})
class NativeDocumentTest {
    @Autowired NativeDocumentController documents;
    @Autowired KnowledgeService files;
    @Autowired ProjectService projects;
    String create(String name, String content) {
        return files.upload(projects.create("native", "").id(), null,
                new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8))).id();
    }
    @Test void fullSourceRoundTripsAndRetainsOldVersion() throws Exception {
        var original = "# 标题\n\n|列|值|\n|---|---|\n|a|b|\n" + "长文本\n".repeat(10000);
        var id = create("report.md", original);
        assertThat(documents.read("files", id).content()).isEqualTo(original);
        var saved = documents.save("files", id, new NativeDocumentController.Save("# 修改\n", 1));
        assertThat(saved.version()).isEqualTo(2);
        assertThat(Files.readString(files.filePath(id, 1))).isEqualTo(original);
        assertThat(documents.read("files", id).content()).isEqualTo("# 修改\n");
        assertThat(documents.save("files", id, new NativeDocumentController.Save("# 修改\n", 2)).version()).isEqualTo(2);
    }
    @Test void rejectsStaleVersionWithoutOverwriting() throws Exception {
        var id = create("report.markdown", "initial");
        documents.save("files", id, new NativeDocumentController.Save("agent edit", 1));
        assertThatThrownBy(() -> documents.save("files", id, new NativeDocumentController.Save("stale edit", 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(documents.read("files", id).content()).isEqualTo("agent edit");
    }
    @Test void historicalReadIsExactReadOnlyAndBounded() throws Exception {
        var id = create("history.md", "original");
        documents.save("files", id, new NativeDocumentController.Save("changed", 1));
        var old = documents.readVersion("files", id, 1);
        assertThat(old.content()).isEqualTo("original");
        assertThat(old.editable()).isFalse();
        assertThat(documents.read("files", id).editable()).isTrue();
        assertThatThrownBy(() -> documents.readVersion("files", id, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> documents.readVersion("files", id, 3)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void supportsEmptyDocumentAndRejectsUnsupportedOrOversize() throws Exception {
        var id = create("empty.md", "original");
        assertThat(documents.save("files", id, new NativeDocumentController.Save("", 1)).content()).isEmpty();
        assertThatThrownBy(() -> documents.save("files", id, new NativeDocumentController.Save("x".repeat(2097153), 2)))
                .isInstanceOf(IllegalArgumentException.class);
        var other = create("file.html", "<h1>not markdown</h1>");
        assertThatThrownBy(() -> documents.read("files", other)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentSavesCannotOverwriteTheSameVersion() throws Exception {
        var id = create("concurrent.md", "original");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var text : java.util.List.of("first", "second")) tasks.add(pool.submit(() -> {
                gate.await();
                try { files.createTextVersion(id, 1, text.getBytes(StandardCharsets.UTF_8)); return true; }
                catch (IllegalStateException conflict) { return false; }
            }));
            gate.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(documents.read("files", id).version()).isEqualTo(2);
            assertThat(Files.readString(files.filePath(id, 1))).isEqualTo("original");
        }
    }
}
