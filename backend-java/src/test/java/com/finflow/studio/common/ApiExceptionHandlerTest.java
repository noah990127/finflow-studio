package com.finflow.studio.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    void returnsActionableMessageWhenUploadExceedsServerLimit() {
        var response = new ApiExceptionHandler().uploadTooLarge(
                new MaxUploadSizeExceededException(500L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("code", "UPLOAD_TOO_LARGE");
        assertThat(response.getBody()).containsEntry("message", "文件超过服务允许的上传大小（最大 500 MB）");
    }
}
