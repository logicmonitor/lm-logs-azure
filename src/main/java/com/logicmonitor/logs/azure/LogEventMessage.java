package com.logicmonitor.logs.azure;

/**
 * POJO containing common Azure log structure.
 */
public class LogEventMessage {

    private String operationName;
    private String resourceId;
    private String time;
    private String category;
    private String level;
    private LogEventProperties properties;

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public LogEventProperties getProperties() {
        return properties;
    }

    public void setProperties(LogEventProperties properties) {
        this.properties = properties;
    }

}
