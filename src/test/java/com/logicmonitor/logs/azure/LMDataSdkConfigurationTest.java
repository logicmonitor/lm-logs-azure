package com.logicmonitor.logs.azure;

import org.junit.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LMDataSdkConfigurationTest {

    @Test
    public void testNoAuthSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"\", \"LM_ACCESS_KEY\" : \"\", \"LM_BEARER_TOKEN\" : \"\"}")
                .execute(() -> {
                    assertThrows(IllegalArgumentException.class, LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdSpecifiedBearerTokenNotSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"keyy\", \"LM_BEARER_TOKEN\" : \"\"}")
                .execute(() -> {
                    assertDoesNotThrow(LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdNotSpecifiedBearerTokenSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"\", \"LM_ACCESS_KEY\" : \"\", \"LM_BEARER_TOKEN\" : \"token\"}")
                .execute(() -> {
                    assertDoesNotThrow(LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdBearerTokenAllSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"key\", \"LM_BEARER_TOKEN\" : \"token\"}")
                .execute(() -> {
                    assertDoesNotThrow(LogEventForwarder::createDataSdkConfig);
                });

    }

    @Test
    public void testAccessKeyIdPartiallySpecifiedBearerTokenNotSpecified() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"\", \"LM_BEARER_TOKEN\" : \"\"}")
                .execute(() -> {
                    assertThrows(IllegalArgumentException.class, LogEventForwarder::createDataSdkConfig);
                });

        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "companyName")
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"\", \"LM_ACCESS_KEY\" : \"key\", \"LM_BEARER_TOKEN\" : \"\"}")
                .execute(() -> {
                    assertThrows(IllegalArgumentException.class, LogEventForwarder::createDataSdkConfig);
                });

    }

}
