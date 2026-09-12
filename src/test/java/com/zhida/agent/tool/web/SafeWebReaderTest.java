package com.zhida.agent.tool.web;

import com.zhida.agent.common.config.ZhidaProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeWebReaderTest {

    private final SafeWebReader reader = new SafeWebReader(new ZhidaProperties());

    @Test
    void shouldRejectFileProtocol() {
        assertThatThrownBy(() -> reader.validatePublicHttpUrl("file:///C:/Windows/win.ini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/HTTPS");
    }

    @Test
    void shouldRejectLoopbackAddress() {
        assertThatThrownBy(() -> reader.validatePublicHttpUrl("http://127.0.0.1:8080/private"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void shouldRejectLocalhost() {
        assertThatThrownBy(() -> reader.validatePublicHttpUrl("http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }
}
