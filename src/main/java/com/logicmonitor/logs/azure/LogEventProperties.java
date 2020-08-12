package com.logicmonitor.logs.azure;

/**
 * POJO containing common Azure log properties.
 */
public class LogEventProperties {

    private String FluentdIngestTimestamp;
    private String Msg;

    public String getFluentdIngestTimestamp() {
        return FluentdIngestTimestamp;
    }

    public void setFluentdIngestTimestamp(String fluentdIngestTimestamp) {
        FluentdIngestTimestamp = fluentdIngestTimestamp;
    }

    public String getMsg() {
        return Msg;
    }

    public void setMsg(String msg) {
        Msg = msg;
    }

}
