package com.logicmonitor.logs.azure;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class LogEntry {
    public static final String JSON_PROPERTY_MESSAGE = "message";
    private String message;

    public static final String JSON_PROPERTY_TIMESTAMP = "timestamp";
    private Long timestamp;

    public static final String JSON_PROPERTY_LM_RESOURCE_ID = "_lm.resourceId";
    private Map<String, String> lmResourceId = new HashMap<>();

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    private Map<String, String> metadata = new HashMap<>();


    public LogEntry message(String message) {
        this.message = message;
        return this;
    }

    /**
     * Get message
     * @return message
     **/
    public String getMessage() {
        return message;
    }


    public void setMessage(String message) {
        this.message = message;
    }


    public LogEntry timestamp(Long timestamp) {

        this.timestamp = timestamp;
        return this;
    }

    /**
     * Get timestamp
     * minimum: 0
     * @return timestamp
     **/
    public Long getTimestamp() {
        return timestamp;
    }


    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }


    public LogEntry lmResourceId(Map<String, String> lmResourceId) {

        this.lmResourceId = lmResourceId;
        return this;
    }

    public LogEntry putLmResourceIdItem(String key, String lmResourceIdItem) {
        this.lmResourceId.put(key, lmResourceIdItem);
        return this;
    }

    /**
     * Get lmResourceId
     * @return lmResourceId
     **/

    public Map<String, String> getLmResourceId() {
        return lmResourceId;
    }


    public void setLmResourceId(Map<String, String> lmResourceId) {
        this.lmResourceId = lmResourceId;
    }


    @Override
    public boolean equals(java.lang.Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LogEntry logEntry = (LogEntry) o;
        return Objects.equals(this.message, logEntry.message) &&
                Objects.equals(this.timestamp, logEntry.timestamp) &&
                Objects.equals(this.lmResourceId, logEntry.lmResourceId) ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, timestamp, lmResourceId);
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class LogEntry {\n");
        sb.append("    message: ").append(toIndentedString(message)).append("\n");
        sb.append("    timestamp: ").append(toIndentedString(timestamp)).append("\n");
        sb.append("    lmResourceId: ").append(toIndentedString(lmResourceId)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert the given object to string with each line indented by 4 spaces
     * (except the first line).
     */
    private String toIndentedString(java.lang.Object o) {
        if (o == null) {
            return "null";
        }
        return o.toString().replace("\n", "\n    ");
    }

}
