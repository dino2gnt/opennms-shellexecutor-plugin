# OpenNMS Shell Executor Plugin

## Overview

This plugin allows you to execute external binaries, scripts, or commands in response to alarm triggers.  Alarm data is added as environment variables to environment under which the target command is run, which can be read by your custom command.

This plugin is compatible with OpenNMS Horizon 33.1.4 or higher.

## Usage

#### Copy the plugin's .kar file into your OpenNMS deploy directory i.e.:
```
sudo cp opennms-shellexecutor-plugin.kar /opt/opennms/deploy/
```

#### Configure the plugin to be installed when OpenNMS starts:
```
echo 'opennms-plugins-shellexecutor wait-for-kar=opennms-shellexecutor-plugin' | sudo tee /opt/opennms/etc/featuresBoot.d/shellexecutor.boot
```

Access the [Karaf shell](https://opennms.discourse.group/t/karaf-cli-cheat-sheet/149) and install the feature manually to avoid having to restart:
```
feature:install opennms-plugins-shellexecutor
```
#### Script Location

Create a directory `$OPENNMS_HOME/etc/shellExecScripts` for your executable scripts.  This is the working directory for everything executed by the shellexecutor plugin. Commands must exist at this location and be executable by the user as which OpenNMS runs (typically `opennms`).

#### Configure global options (affects all services for this instance):
```
config:edit org.opennms.plugins.shellexecutor
property-set alarmDetailsUrlPattern 'http://"YOUR-OPENNMS-FQDN"/opennms/alarm/detail.htm?id=%d'
config:update
```
> Use the IP address or hostname of your OpenNMS server (e.g., 127.0.0.1:8980).

#### Configure services:
```
config:edit --alias runSomething --factory org.opennms.plugins.shellexecutor.services
property-set jexlFilter 'alarm == true'
property-set shellCommand '/opt/opennms/etc/shellExecScripts/doEcho.sh'
property-set commandTimeout 3
config:update
```

> Use a JEXL expression to filter the alarms that will trigger an execution. For example,`property-set jexlFilter 'alarm.reductionKey =~ ".*trigger.*"'` will forward only alarms with the label "trigger" to ShellExecutor. No alarms will forward to ShellExecutor until a JEXL expression is configured.

The plugin supports handling multiple services simultaneously - use a different `alias` for each of these when configuring.

### Alarm filtering

We currently only support JEXL expressions for controlling which alarms get forwarded to a given service.

You can use the `opennms-shellexecutor:eval-jexl` command to help test expressions before committing them to configuration i.e.:
```
admin@opennms()> opennms-shellexecutor:eval-jexl --help
DESCRIPTION
        opennms-shellexecutor:eval-jexl

        Evaluate a JEXL expression for the Shell Executor plugin

SYNTAX
        opennms-shellexecutor:eval-jexl [options] expression

ARGUMENTS
        expression

                (required)

OPTIONS
        --help
                Display this help message
        -p, --topayload
                Also display matched alarms as shell environment payload
        -c, --count
                Only show the number of matching alarms, without alarm data
          
admin@opennms()> opennms-shellexecutor:eval-jexl -p 'alarm.reductionKey =~ "uei.opennms.org/threshold/lowThresholdExceeded.*"'
MATCHED: ImmutableAlarm{reductionKey='uei.opennms.org/threshold/lowThresholdExceeded::12:192.168.69.59:memAvailSwap / memTotalSwap * 100.0:10.0:2:15.0:node', id=2368, node=ImmutableNode{id=12, foreignSource='TEST', foreignId='12', label='kafka.test.example.com', location='Default', assetRecord=ImmutableNodeAssetRecord{vendor='null', modelNumber='null', description='null', assetNumber='null', operatingSystem='null', region='null', division='null', department='null', building='TEST', floor='null', geolocation=null}, ipInterfaces=[ImmutableIpInterface{ipAddress=/192.168.69.59, snmpInterface=Optional[ImmutableSnmpInterface{ifDescr='enp1s0', ifName='enp1s0', ifIndex=2}], metaData=[], monitoredService=[ImmutableIpInterface{name=ICMP, metaData=[], status=true}, ImmutableIpInterface{name=SNMP, metaData=[], status=true}, ImmutableIpInterface{name=SSH, metaData=[], status=true}]}], snmpInterfaces=[ImmutableSnmpInterface{ifDescr='lo', ifName='lo', ifIndex=1}, ImmutableSnmpInterface{ifDescr='enp1s0', ifName='enp1s0', ifIndex=2}], metaData=[], categories=[Jokes, Kafka, Servers, VMs]}, managedObjectInstance='null', managedObjectType='null', type=PROBLEM, severity=WARNING, attributes={}, relatedAlarms=[], logMessage='Low threshold exceeded for service SNMP metric memAvailSwap / memTotalSwap * 100.0 on interface node/192.168.69.59', description='Low threshold for the following metric exceeded: label=&#34;node&#34; ds=&#34;memAvailSwap / memTotalSwap * 100.0&#34; description=&#34;Trigger an alert when the amount of available swap space reaches or goes below 10% of the total amount of swap space for two consecutive measurement intervals (only for systems that have a total swap space value defined)&#34; value=&#34;8.38&#34; instance=&#34;node&#34; instanceLabel=&#34;node&#34; resourceType=&#34;node&#34; resourceId=&#34;nodeSource[TEST:12].nodeSnmp[]&#34; threshold=&#34;10.0&#34; trigger=&#34;2&#34; rearm=&#34;15.0&#34;', lastEventTime=2025-04-07 20:31:11.33, firstEventTime=2025-04-07 20:31:11.33, lastEvent=ImmutableDatabaseEvent{uei='uei.opennms.org/threshold/lowThresholdExceeded', id=15630, parameters=[ImmutableEventParameter{name='label', value='node'}, ImmutableEventParameter{name='ds', value='memAvailSwap / memTotalSwap * 100.0'}, ImmutableEventParameter{name='description', value='Trigger an alert when the amount of available swap space reaches or goes below 10% of the total amount of swap space for two consecutive measurement intervals (only for systems that have a total swap space value defined)'}, ImmutableEventParameter{name='value', value='8.38'}, ImmutableEventParameter{name='instance', value='node'}, ImmutableEventParameter{name='instanceLabel', value='node'}, ImmutableEventParameter{name='resourceType', value='node'}, ImmutableEventParameter{name='resourceId', value='nodeSource[TEST:12].nodeSnmp[]'}, ImmutableEventParameter{name='threshold', value='10.0'}, ImmutableEventParameter{name='trigger', value='2'}, ImmutableEventParameter{name='rearm', value='15.0'}]}, acknowledged=false, ticketId=null, ticketState=null}
Environment payload:
    reductionKey = "uei.opennms.org/threshold/lowThresholdExceeded::12:192.168.69.59:memAvailSwap / memTotalSwap * 100.0:10.0:2:15.0:node"
    action = "TRIGGER"
    logmessage = "Low threshold exceeded for service SNMP metric memAvailSwap / memTotalSwap * 100.0 on interface node/192.168.69.59"
    severity = "WARNING"
    source = "kafka.test.example.com"
    nodeLabel = "kafka.test.example.com"
    node_categories = "[Jokes, Kafka, Servers, VMs]"
    node_ipAddress = "/192.168.69.59"
    label = "node"
    ds = "memAvailSwap / memTotalSwap * 100.0"
    description = "Trigger an alert when the amount of available swap space reaches or goes below 10% of the total amount of swap space for two consecutive measurement intervals (only for systems that have a total swap space value defined)"
    value = "8.38"
    instance = "node"
    instanceLabel = "node"
    resourceType = "node"
    resourceId = "nodeSource[TEST:12].nodeSnmp[]"
    threshold = "10.0"
    trigger = "2"
    rearm = "15.0"
Matched 1 alarms (out of 4 alarms.)      
```

#### JEXL Expression Examples

The OpenNMS Shell Executor integration leverages [Apache Commons JEXL Syntax](https://commons.apache.org/proper/commons-jexl/reference/syntax.html) to allow filtering the alarms that get passed to Shell Executor.

Each expression will have a single `alarm` variable set, which is an [Alarm](https://github.com/OpenNMS/opennms-integration-api/blob/master/api/src/main/java/org/opennms/integration/api/v1/model/Alarm.java) object with details about the alarm. If the expression evaluates to `true`, then a Shell Executor event is created for this alarm.

##### All alarms for a nodes with "Test" and "Servers" categories assigned

```
admin@opennms> property-set jexlFilter '"Servers" =~ alarm.node.categories and "Test" =~ alarm.node.categories'
```

This leverages the `=~` operator to mean "'Servers' is in the alarm's node's categories" and "'Test' is in the alarm's node's categories".

#### Excluding some alarms from the above

```
admin@opennms> property-set jexlFilter '"Servers" =~ alarm.node.categories and "Test" =~ alarm.node.categories and alarm.reductionKey !~ "^uei\.opennms\.org/generic/traps/SNMP_Authen_Failure:.*"'
```

This leverages the `!~` operator to mean "the alarm reduction key does not match the given regex", in addition to the "in" sense of `=~` as shown above.

#### Only Alarms That Can Auto-Resolve

```
admin@opennms> property-set jexlFilter '"Servers" =~ alarm.node.categories and "Test" =~ alarm.node.categories and alarm.type.name == "PROBLEM"'
```

This limits to only alarms for certain categories of nodes that have a resolution. Some alarms have no "clearing" event, so they would stay present in Shell Executor forever unless manual action is taken, or certain special configuration is used within Shell Executor to expire the events.

### Hold-Down Timer (Delayed Command Execution)

Some alarms may quick resolve themselves, especially occasional brief outages from the OpenNMS service pollers.

To be able to get the full benefits of the OpenNMS [Downtime Model](https://docs.opennms.org/opennms/releases/latest/guide-admin/guide-admin.html#ga-service-assurance-downtime-model),
you can specify a hold-down timer of at least a few minutes, to trade off instant command execution for
reduced false positives (issues that resolved themselves before you were able to look at them).

Similar to configuring the `jexlFilter` above, you can edit a specific service's configuration. To find
the specific configuration to edit, use `config:list` as shown below, then use `config:edit` to edit the Pid of that specific
service's configuration:

```
admin@opennms()> config:list '(service.factoryPid=org.opennms.plugins.shellexecutor.services)'
----------------------------------------------------------------
Pid:            org.opennms.plugins.shellexecutor.services~doEcho
FactoryPid:     org.opennms.plugins.shellexecutor.services
BundleLocation: ?
Properties:
   commandTimeout = 3
   felix.fileinstall.filename = file:/opt/opennms/etc/org.opennms.plugins.shellexecutor.services-doEcho.cfg
   jexlFilter = alarm == true
   service.factoryPid = org.opennms.plugins.shellexecutor.services
   service.pid = org.opennms.plugins.shellexecutor.services~doEcho
   shellCommand = /opt/opennms/etc/shellExecScripts/doEcho.sh
admin@opennms> config:edit org.opennms.plugins.shellexecutor.services.~doEcho
admin@opennms> property-set holdDownDelay "PT5M"
admin@opennms> config:update
```

The `holdDownDelay` property should be a string that follows ISO-8601 duration format, as supported
by [`java.time.Duration.parse()`](https://docs.oracle.com/javase/8/docs/api/java/time/Duration.html#parse-java.lang.CharSequence-).

### Handling notification failures

In cases where forwarding an alarm to Shell Executor fails, the plugin will generate a `uei.opennms.org/shellexecutor/executionFailed` locally that will trigger an alarm.
The event contains details on the error that occurred. You could use it to trigger alternate notification mechanisms or further troubleshoot the failure.

## Developing

Build and install the plugin into your local Maven repository using:
```
mvn clean install
cp ./assembly/kar/target/opennms-shellexecutor-plugin.kar /opt/opennms/deploy/
```

From the OpenNMS Karaf shell:
```
feature:install opennms-plugins-shellexecutor
```

### Debugging
`opennms-plugins-shellexecutor` logs successful executions and outputs at INFO, and failures at ERROR.  You can adjust the log level of your Karaf instance by editing `$OPENNMS_HOME/etc/org.ops4j.pax.logging.cfg` and changing:
```
# OPENNMS: Display all WARN logs for our code
log4j2.logger.opennms.name = org.opennms
log4j2.logger.opennms.level = WARN
```
... to INFO.

