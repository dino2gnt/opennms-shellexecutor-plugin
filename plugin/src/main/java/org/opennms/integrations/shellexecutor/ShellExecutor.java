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

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.opennms.integration.api.v1.alarms.AlarmLifecycleListener;
import org.opennms.integration.api.v1.config.events.AlarmType;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.model.DatabaseEvent;
import org.opennms.integration.api.v1.model.EventParameter;
import org.opennms.integration.api.v1.model.Severity;
import org.opennms.integration.api.v1.model.immutables.ImmutableEventParameter;
import org.opennms.integration.api.v1.model.immutables.ImmutableInMemoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class ShellExecutor implements AlarmLifecycleListener, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(ShellExecutor.class);

    private static final String SE_UEI_PREFIX = "uei.opennms.org/shellexecutor";
    private static final String SEND_EVENT_FAILED_UEI = SE_UEI_PREFIX + "/executionFailed";
    private static final String SEND_EVENT_SUCCESSFUL_UEI = SE_UEI_PREFIX + "/executionSuccessful";

    private EventForwarder eventForwarder;
    private final ShellExecPluginConfig pluginConfig;
    private final ShellExecServiceConfig serviceConfig;
    private final JexlExpression jexlFilterExpression;
    private final DelayQueue<ShellExecutorTask> taskQueue;
    private final ExecutorService executor;

    /**
     * Used to track alarms that were filtered and not forwarded to PD.
     */
    private final Set<Integer> alarmIdsFiltered = new ConcurrentSkipListSet<>();

    public ShellExecutor(EventForwarder eventForwarder, ShellExecPluginConfig pluginConfig, ShellExecServiceConfig serviceConfig) {
        this.eventForwarder = Objects.requireNonNull(eventForwarder);
        this.pluginConfig = Objects.requireNonNull(pluginConfig);
        this.serviceConfig = Objects.requireNonNull(serviceConfig);
        taskQueue = new DelayQueue<>();
        executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("ShellExecutor-executor-" + serviceConfig.getPid() + "-%d").build());
        executor.submit(new TaskConsumer());

        if (!Strings.isNullOrEmpty(serviceConfig.getJexlFilter())) {
            JexlEngine jexl = new JexlBuilder().create();
            jexlFilterExpression = jexl.createExpression(serviceConfig.getJexlFilter());
        } else {
            jexlFilterExpression = null;
        }
    }

    private boolean shouldProcess(Alarm alarm) {
        if (alarm.getReductionKey().startsWith(SE_UEI_PREFIX)) {
            // Never forward alarms that the plugin itself creates
            return false;
        }
        if (jexlFilterExpression == null) {
            LOG.info("No JEXL expression found, not evaluating alarm.");
            return false;
        }
        return testAlarmAgainstExpression(jexlFilterExpression, alarm);
    }

    @Override
    public void handleAlarmSnapshot(List<Alarm> list) {
        // TODO
    }

    @Override
    public void handleNewOrUpdatedAlarm(Alarm alarm) {
        if (!shouldProcess(alarm)) {
            // Remember the alarms that were filtered & not processed, so that we can skip
            // the deletes as well when we get callbacks for these
            alarmIdsFiltered.add(alarm.getId());
            return;
        }
        // We may of previously filtered the alarm, but decided to process it now
        alarmIdsFiltered.remove(alarm.getId());

        Map<String, String> envAlarm = toEnvironment(alarm);

        String reductionKey = alarm.getReductionKey();
        switch (envAlarm.get("action")) {
            case "TRIGGER":
                enqueueTask(envAlarm, reductionKey);
                break;
            case "ACKNOWLEDGE":
                try {
                    ackEvent(envAlarm, reductionKey);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
                break;
            case "RESOLVE":
                try {
                    resolveEvent(envAlarm, reductionKey);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
                break;
        }
    }

    @Override
    public void handleDeletedAlarm(int alarmId, String reductionKey) {
        if (alarmIdsFiltered.remove(alarmId)) {
            // This alarm was filtered out and no command executed
            return;
        }
        if (dequeueTasks(reductionKey)) {
            return;
        }

        Map<String,String> e = new LinkedHashMap<>();
        e.put("action", "DELETED");
        e.put("reductionKey", reductionKey);
        LOG.info("Sending clear for deleted alarm with reduction-key: {}", reductionKey);
        boolean exitCode = false;
        try {
            exitCode = shellExecute(reductionKey, e);
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
        if (!exitCode) {
            LOG.warn("Executing command for deleted alarm with reduction-key: {} failed.", reductionKey);
        } else {
            LOG.info("Command executed successfully for deleted alarm with reduction-key: {}", reductionKey);
        }
    }

    private void enqueueTask(Map<String, String> envAlarm, String reductionKey) {
        Duration holdDownDelay = serviceConfig.getHoldDownDelay();
        LOG.debug("Scheduling task to send event for alarm with reduction-key: {}, delay: {}", reductionKey, holdDownDelay);
        ShellExecutorTask task = new ShellExecutorTask(Instant.now().plus(holdDownDelay), reductionKey, envAlarm);
        taskQueue.offer(task);
    }

    private void resolveEvent(Map<String, String> envAlarm, String reductionKey) throws IOException, InterruptedException {
        LOG.debug("Resolving alarm with reduction-key: {}", reductionKey);
        if (dequeueTasks(reductionKey)) {
            return;
        }
        shellExecute(reductionKey, envAlarm);
    }

    private void ackEvent(Map<String, String> envAlarm, String reductionKey) throws IOException, InterruptedException {
        LOG.debug("Acknowledging alarm with reduction-key: {}", reductionKey);
        if (dequeueTasks(reductionKey)) {
            return;
        }
        shellExecute(reductionKey, envAlarm);
    }

    private boolean dequeueTasks(String reductionKey) {
        if (taskQueue.removeIf(t -> t.getReductionKey().equals(reductionKey))) {
            // This alarm wasn't executed yet, and we've now cancelled that task
            LOG.debug("Task removed from queue for reduction-key: {}", reductionKey);
            return true;
        }
        return false;
    }


    public Map<String, String> toEnvironment(Alarm alarm) {
        final Map<String, String> e = new LinkedHashMap<>();
        e.put("client", pluginConfig.getClient());
        e.put("clientUrl", String.format(pluginConfig.getAlarmDetailsUrlPattern(), alarm.getId()));
        e.put("command", serviceConfig.getCommand());
        e.putAll(doPayload(alarm));
        return e;
    }

    public static Map<String, String> doPayload(Alarm alarm) {
        final Map<String, String> e = new LinkedHashMap<>();
        e.put("reductionKey", alarm.getReductionKey());

        if (Severity.CLEARED.equals(alarm.getSeverity()) || AlarmType.RESOLUTION.equals(alarm.getType())) {
            e.put("action", "RESOLVE");
        } else if (alarm.isAcknowledged()) {
            e.put("action", "ACKNOWLEDGE");
        } else {
            e.put("action", "TRIGGER");
        }

        // ID
        e.put("id", alarm.getId().toString());
        // Log message
        e.put("logmessage", alarm.getLogMessage().trim());
        // Severity -> Severity
        e.put("severity", alarm.getSeverity().toString());
        // Use the node label as the source if available
        if (alarm.getNode() != null) {
            e.put("source", alarm.getNode().getLabel());
            // Add the event's nodelabel to details
            if (alarm.getNode().getLabel() != null && !e.containsKey("nodeLabel")) {
                e.put("nodeLabel", alarm.getNode().getLabel());
            }
            // node asset
            if (alarm.getNode().getAssetRecord().toString() != null && !e.containsKey("nodeAsset")) {
                e.put("nodeAsset", alarm.getNode().getAssetRecord().toString());
            }
            // Node meta
            if (alarm.getNode().getMetaData().toString() != null && !e.containsKey("nodeMetaData")) {
                e.put("nodeMetaData", alarm.getNode().getMetaData().toString());
            }
            // Add categories
            if (alarm.getNode().getCategories() != null && !e.containsKey("node_categories")) {
                e.put("node_categories", alarm.getNode().getCategories().toString());
            }
            //Add the first IP address
            if (alarm.getNode().getIpInterfaces().get(0).getIpAddress() != null && !e.containsKey("node_ipAddress")) {
                e.put("node_ipAddress", alarm.getNode().getIpInterfaces().get(0).getIpAddress().toString());
            }
        } else {
            e.put("source", "unknown");
        }
        // Add all of the event parameters as custom details
        final DatabaseEvent dbEvent = alarm.getLastEvent();
        if (dbEvent != null) {
            e.putAll(eparmsToMap(dbEvent.getParameters()));
        }
        return e;
    }

    protected static Map<String, String> eparmsToMap(List<EventParameter> eparms) {
        final Map<String, String> map = new LinkedHashMap<>();
        if (eparms == null) {
            return map;
        }
        eparms.forEach(p -> map.put(p.getName(), p.getValue()));
        return map;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public static boolean testAlarmAgainstExpression(JexlExpression expression, Alarm alarm) {
        final JexlContext jc = new MapContext();
        jc.set("alarm", alarm);
        jc.get("alarm").toString();
        return (boolean)expression.evaluate(jc);
    }

    private class TaskConsumer implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    LOG.debug("Waiting for a task to become available...");
                    ShellExecutorTask task = taskQueue.take();
                    LOG.debug("Received ShellExecutorTask: {}", task);
                    shellExecute(task.getReductionKey(), task.getEnvAlarm());
                } catch (InterruptedException | IOException e) {
                    LOG.info("TaskConsumer interrupted. Stopping.");
                    break;
                }
            }
        }

    }

    private boolean shellExecute(String reductionKey, Map<String, String> envAlarm) throws IOException, InterruptedException {
        LOG.info("Executing command for alarm with reduction-key: {}", reductionKey);
        String command = serviceConfig.getCommand();
        int timeout = serviceConfig.getTimeout();
        ProcessBuilder pb = new ProcessBuilder(command);
        // clear the existing environment
        pb.environment().clear();
        Map<String, String> env = pb.environment();
        // Put the envAlarm into the process environment
        env.putAll(envAlarm);
        pb.directory(new File(System.getProperty("user.dir") + "/etc/shellExecScripts"));
        pb.redirectErrorStream(true);
        boolean isFinished = false;
        Process p = null;
        StringBuilder commandOutput = new StringBuilder();
        try {
            p = pb.start();
            isFinished = p.waitFor(timeout, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            LOG.error("ShellExecutor command '{}' failed to run: {}", command, e.toString());
        }
        if (p != null && !isFinished) {
            LOG.error("ShellExecutor command '{}' did not run in the allotted {} second timeout and will be killed.", command, timeout);
            p.destroyForcibly();
        }
        if (p != null && isFinished) {
            BufferedReader processInputReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = processInputReader.readLine()) != null) {
                commandOutput.append(line);
                commandOutput.append("\n");
            }
            if (p.exitValue() != 0) {
                LOG.error("ShellExecutor command '{}' exited with nonzero exit code and output: '{}'", command, commandOutput);
            } else {
                LOG.info("ShellExecutor command '{}' executed successfully. Output: '{}'", command, commandOutput);
            }
        }
        sendEvent(reductionKey, envAlarm, isFinished, commandOutput);
        return isFinished;
    }

    private void sendEvent(String reductionKey, Map<String, String> envAlarm, boolean success, StringBuilder commandOutput) {
        if (!success) {
            eventForwarder.sendAsync(ImmutableInMemoryEvent.newBuilder()
                    .setUei(SEND_EVENT_FAILED_UEI)
                    .setSource(ShellExecutor.class.getName())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("reductionKey")
                            .setValue(reductionKey)
                            .build())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("commandOutput")
                            .setValue(commandOutput.toString())
                            .build())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("command")
                            .setValue(serviceConfig.getCommand())
                            .build())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("pid")
                            .setValue(serviceConfig.getPid())
                            .build())
                    .build());
        }
        else {
            eventForwarder.sendAsync(ImmutableInMemoryEvent.newBuilder()
                    .setUei(SEND_EVENT_SUCCESSFUL_UEI)
                    .setSource(ShellExecutor.class.getName())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("reductionKey")
                            .setValue(reductionKey)
                            .build())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("commandOutput")
                            .setValue(commandOutput.toString())
                            .build())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("command")
                            .setValue(serviceConfig.getCommand())
                            .build())
                    .addParameter(ImmutableEventParameter.newBuilder()
                            .setName("pid")
                            .setValue(serviceConfig.getPid())
                            .build())
                    .build());
        }
    }
}
