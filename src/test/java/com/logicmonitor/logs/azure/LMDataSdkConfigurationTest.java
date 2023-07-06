package com.logicmonitor.logs.azure;

import org.junit.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LMDataSdkConfigurationTest {

    @Test
    public void testNoAuthSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .execute(() -> {
                    assertThrows(IllegalArgumentException.class, LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdSpecifiedBearerTokenNotSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_ACCESS_ID, "id")
                .and(LogEventForwarder.PARAMETER_ACCESS_KEY, "key")
                .execute(() -> {
                    assertDoesNotThrow(LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdNotSpecifiedBearerTokenSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_BEARER_TOKEN, "token")
                .execute(() -> {
                    assertDoesNotThrow(LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdBearerTokenAllSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_ACCESS_ID, "id")
                .and(LogEventForwarder.PARAMETER_ACCESS_KEY, "key")
                .and(LogEventForwarder.PARAMETER_BEARER_TOKEN, "token")
                .execute(() -> {
                    assertDoesNotThrow(LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdPartiallySpecifiedBearerTokenNotSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_ACCESS_ID, "id")
                .execute(() -> {
                    assertThrows(IllegalArgumentException.class, LogEventForwarder::createDataSdkConfig);
                });

        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_ACCESS_KEY, "key")
                .execute(() -> {
                    assertThrows(IllegalArgumentException.class, LogEventForwarder::createDataSdkConfig);
                });

    }

}
