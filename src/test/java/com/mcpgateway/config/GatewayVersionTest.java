package com.mcpgateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 版本号取自构建信息，不再由代码写死。
 */
class GatewayVersionTest {

    @Test
    @DisplayName("有 build-info 时取 pom 里的版本号")
    void readsVersionFromBuildInfo() {
        Properties properties = new Properties();
        properties.setProperty("version", "9.9.9");

        GatewayVersion version = new GatewayVersion(providerOf(new BuildProperties(properties)));

        assertThat(version.value()).isEqualTo("9.9.9");
    }

    @Test
    @DisplayName("绕开 Maven 直接跑（没有 build-info）时退化成 unknown，而不是启动失败")
    void fallsBackWhenBuildInfoMissing() {
        GatewayVersion version = new GatewayVersion(providerOf(null));

        assertThat(version.value()).isEqualTo(GatewayVersion.UNKNOWN);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> providerOf(BuildProperties build) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(build);
        return provider;
    }

}
