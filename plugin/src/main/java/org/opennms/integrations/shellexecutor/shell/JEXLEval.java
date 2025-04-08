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

package org.opennms.integrations.shellexecutor.shell;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.integration.api.v1.dao.AlarmDao;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integrations.shellexecutor.ShellExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

@Command(scope = "opennms-shellexecutor", name = "eval-jexl", description = "Evaluate a JEXL expression for the Shell Executor plugin")
@Service
public class JEXLEval implements Action {

    @Reference
    private AlarmDao alarmDao;

    @Argument(required = true)
    private String expression;

    @Option(name = "-p", aliases = "--topayload", description = "Also display matched alarms as shell environment payload", required = false, multiValued = false)
    Boolean toPayload = false;

    @Option(name = "-c", aliases = "--count", description = "Only show the number of matching alarms, without alarm data", required = false, multiValued = false)
    Boolean onlyCount = false;

    @Override
    public Object execute() {
        JexlEngine jexl = new JexlBuilder().create();
        JexlExpression e = jexl.createExpression(expression);

        int numAlarmsProcessed = 0;
        boolean didMatchAtLeastOneAlarm = false;
        int matchedAlarmCount = 0;

        if (toPayload && onlyCount) {
            System.out.println("Options '-p' and '-c' are mutually exclusive, ignoring '-p' ");
        }

        for (Alarm alarm : alarmDao.getAlarms()) {
            numAlarmsProcessed++;
            boolean didMatch = ShellExecutor.testAlarmAgainstExpression(e, alarm);
            if (didMatch) {
                if (!onlyCount) {
                    System.out.println("MATCHED: " + alarm);
                    if (toPayload) {
                        Map<String,String> payload = ShellExecutor.doPayload(alarm);
                        System.out.println("Environment payload: ");
                        for(Map.Entry<String, String> en: payload.entrySet()) {
                            System.out.print("    " + en.getKey() + " = ");
                            System.out.println("\"" + en.getValue() + "\"");
                        }
                    }
                }
                matchedAlarmCount++;
                didMatchAtLeastOneAlarm = true;
            }
        }

        if (numAlarmsProcessed < 1) {
            System.out.println("No alarms present.");
        } else if (!didMatchAtLeastOneAlarm) {
            System.out.printf("No alarms matched (out of %d alarms.)\n", numAlarmsProcessed);
        } else if (didMatchAtLeastOneAlarm && matchedAlarmCount > 0) {
            System.out.printf("Matched %d alarms (out of %d alarms.)\n", matchedAlarmCount, numAlarmsProcessed);
        }
        return null;
    }
}

