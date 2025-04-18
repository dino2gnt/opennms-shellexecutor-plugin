/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.integrations.shellexecutor;

import java.time.Duration;
import java.util.Objects;

public class ShellExecServiceConfig {
    private final String pid;
    private final String shellCommand;
    private final int commandTimeout;
    private final String jexlFilter;
    private final Duration holdDownDelay;

    public ShellExecServiceConfig(String pid, String shellCommand, int commandTimeout, String jexlFilter, Duration holdDownDelay) {
        this.pid = Objects.requireNonNull(pid, "pid is required");
        this.shellCommand = Objects.requireNonNull(shellCommand, "shellCommand is required");
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout is required");
        this.jexlFilter = jexlFilter;
        this.holdDownDelay = holdDownDelay == null ? Duration.ZERO : holdDownDelay;
    }

    public String getPid() {
        return pid;
    }

    public String getCommand() {
        return shellCommand;
    }

    public int getTimeout() { return commandTimeout; }

    public String getJexlFilter() {
        return jexlFilter;
    }

    public Duration getHoldDownDelay() {
        return holdDownDelay;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShellExecServiceConfig that = (ShellExecServiceConfig) o;
        return Objects.equals(pid, that.pid) &&
                Objects.equals(shellCommand, that.shellCommand) &&
                Objects.equals(commandTimeout, that.commandTimeout) &&
                Objects.equals(jexlFilter, that.jexlFilter) &&
                Objects.equals(holdDownDelay, that.holdDownDelay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pid, shellCommand, commandTimeout, jexlFilter, holdDownDelay);
    }

    @Override
    public String toString() {
        return "ShellExecServiceConfig{" +
                "pid='" + pid + '\'' +
                ", shellCommand='" + shellCommand + '\'' +
                ", commandTimeout='" + commandTimeout + '\'' +
                ", jexlFilter='" + jexlFilter + '\'' +
                ", holdDownDelay='" + holdDownDelay + '\'' +
                '}';
    }
}
