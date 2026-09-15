package com.zhida.agent.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZhidaPropertiesTest {

    @Test
    void defaultsToThirtyToolCallsPerTask() {
        var properties = new ZhidaProperties();

        assertThat(properties.getExecution().getMaxToolCalls()).isEqualTo(30);
    }
}
