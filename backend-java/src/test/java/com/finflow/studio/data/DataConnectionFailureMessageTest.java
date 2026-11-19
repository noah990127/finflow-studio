package com.finflow.studio.data;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DataConnectionFailureMessageTest {
    @Test
    void revealsTheUsefulCauseHiddenBehindThePostgresGenericMessage() {
        var failure = new SQLException("尝试连线已失败。", "08001",
                new ConnectException("Connection refused"));

        assertThat(DataConnectionService.diagnoseConnectionFailure(failure))
                .isEqualTo("目标主机拒绝连接，请检查主机地址、端口以及数据库服务是否已启动");
    }

    @Test
    void explainsDnsTimeoutAuthenticationAndMissingDatabaseFailures() {
        assertThat(DataConnectionService.diagnoseConnectionFailure(new UnknownHostException("db.internal")))
                .contains("无法解析主机地址");
        assertThat(DataConnectionService.diagnoseConnectionFailure(new SocketTimeoutException("connect timed out")))
                .contains("连接超时");
        assertThat(DataConnectionService.diagnoseConnectionFailure(
                new SQLException("password authentication failed for user analyst", "28P01")))
                .contains("账号或密码不正确");
        assertThat(DataConnectionService.diagnoseConnectionFailure(
                new SQLException("database does not exist", "3D000")))
                .contains("数据库不存在");
    }

    @Test
    void removesCredentialsFromFallbackDriverMessages() {
        var message = DataConnectionService.diagnoseConnectionFailure(
                new SQLException("vendor error password=very-secret token=abc123"));

        assertThat(message).contains("password=***", "token=***")
                .doesNotContain("very-secret", "abc123");
    }
}
