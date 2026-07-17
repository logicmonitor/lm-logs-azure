package com.logicmonitor.logs.azure;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import com.logicmonitor.sdk.data.Configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LMDataSdkConfigurationTest {

    private static Configuration createConfig(String company, String accessId, String accessKey,
            String bearerToken) {
        if (StringUtils.isNoneBlank(accessKey, accessId)) {
            return new Configuration(company, accessId, accessKey, null, null);
        }
        return new Configuration(company, null, null, bearerToken, null);
    }

    @Test
    public void testNoAuthSpecified() {
        assertThrows(IllegalArgumentException.class,
            () -> createConfig("companyName", "", "", ""));
    }

    @Test
    public void testAccessKeyIdSpecifiedBearerTokenNotSpecified() {
        assertDoesNotThrow(() -> createConfig("companyName", "id", "keyy", ""));
    }

    @Test
    public void testAccessKeyIdNotSpecifiedBearerTokenSpecified() {
        assertDoesNotThrow(() -> createConfig("companyName", "", "", "token"));
    }

    @Test
    public void testAccessKeyIdBearerTokenAllSpecified() {
        assertDoesNotThrow(() -> createConfig("companyName", "id", "key", "token"));
    }

    @Test
    public void testAccessKeyIdPartiallySpecifiedBearerTokenNotSpecified() {
        assertThrows(IllegalArgumentException.class,
            () -> createConfig("companyName", "id", "", ""));
        assertThrows(IllegalArgumentException.class,
            () -> createConfig("companyName", "", "key", ""));
    }

}
