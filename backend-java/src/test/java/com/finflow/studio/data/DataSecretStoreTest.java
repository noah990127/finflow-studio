package com.finflow.studio.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSecretStoreTest {
    @TempDir Path tempDir;

    @Test
    void encryptsPasswordsAtRestAndDecryptsOnlyThroughResolver() {
        var store = new DataSecretStore(tempDir.resolve("connection.key").toString());
        var encrypted = store.encrypt("budget-db-password");

        assertThat(encrypted).startsWith("enc:v1:").doesNotContain("budget-db-password");
        assertThat(store.decrypt(encrypted)).isEqualTo("budget-db-password");
        assertThat(new SecretResolver(store).resolve(encrypted)).isEqualTo("budget-db-password");
    }

    @Test
    void rejectsTamperedCiphertext() {
        var store = new DataSecretStore(tempDir.resolve("connection.key").toString());
        var encrypted = store.encrypt("secret");

        assertThatThrownBy(() -> store.decrypt(encrypted + "broken"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重新填写");
    }
}
