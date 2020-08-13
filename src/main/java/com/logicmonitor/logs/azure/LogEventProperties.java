/*
 * Copyright (C) 2020 LogicMonitor, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

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
