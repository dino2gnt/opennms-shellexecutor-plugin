package org.opennms.integrations.shellexecutor;

import java.util.List;

import org.opennms.integration.api.v1.config.events.EventConfExtension;
import org.opennms.integration.api.v1.config.events.EventDefinition;
import org.opennms.integration.api.xml.ClasspathEventDefinitionLoader;

public class PluginEventConfExtension implements EventConfExtension {

    private final ClasspathEventDefinitionLoader classpathEventDefinitionLoader = new ClasspathEventDefinitionLoader(
            PluginEventConfExtension.class,
            "shellexec.ext.events.xml"
    );

    @Override
    public List<EventDefinition> getEventDefinitions() {
        return classpathEventDefinitionLoader.getEventDefinitions();
    }
}
