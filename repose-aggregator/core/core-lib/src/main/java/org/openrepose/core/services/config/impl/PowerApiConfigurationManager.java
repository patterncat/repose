package org.openrepose.core.services.config.impl;

import org.openrepose.commons.config.ConfigurationResourceException;
import org.openrepose.commons.config.manager.ConfigurationUpdateManager;
import org.openrepose.commons.config.manager.UpdateFailedException;
import org.openrepose.commons.config.manager.UpdateListener;
import org.openrepose.commons.config.parser.ConfigurationParserFactory;
import org.openrepose.commons.config.parser.common.ConfigurationParser;
import org.openrepose.commons.config.resource.ConfigurationResource;
import org.openrepose.commons.config.resource.ConfigurationResourceResolver;
import org.openrepose.core.jmx.ConfigurationInformation;
import org.openrepose.core.services.config.ConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * This class uses configuration info to subscribe and unsubscribe from filters.
 */

@Component("configurationManager")
public class PowerApiConfigurationManager implements ConfigurationService {

    private static final Logger LOG = LoggerFactory.getLogger(PowerApiConfigurationManager.class);
    private final Map<Class, WeakReference<ConfigurationParser>> parserLookaside;
    private ConfigurationUpdateManager updateManager;
    private ConfigurationResourceResolver resourceResolver;
    private ConfigurationInformation configurationInformation;

    @Override
    public ConfigurationInformation getConfigurationInformation() {
        return configurationInformation;
    }

    @Override
    public void setConfigurationInformation(ConfigurationInformation configurationInformation) {
        this.configurationInformation = configurationInformation;
    }


    @Autowired
    public PowerApiConfigurationManager(@Qualifier("reposeVersion") String version) {
        LOG.error("Repose Version: " + version);
        parserLookaside = new HashMap<Class, WeakReference<ConfigurationParser>>();
    }

    @Override
    public void destroy() {
        parserLookaside.clear();
        updateManager.destroy();
    }

    @Override
    public void setResourceResolver(ConfigurationResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @Override
    public ConfigurationResourceResolver getResourceResolver() {
        return this.resourceResolver;
    }
    
    @Override
    public void setUpdateManager(ConfigurationUpdateManager updateManager) {
        this.updateManager = updateManager;
    }
    
   @Override
    public <T> void subscribeTo(String configurationName,  UpdateListener<T> listener, Class<T> configurationClass) {
        subscribeTo("",configurationName, listener, getPooledJaxbConfigurationParser(configurationClass, null),true);
       
    }
    
    @Override
    public <T> void subscribeTo(String filterName,String configurationName,  UpdateListener<T> listener, Class<T> configurationClass) {
        subscribeTo(filterName,configurationName, listener, getPooledJaxbConfigurationParser(configurationClass, null),true);
       
    }
   
    @Override
    public <T> void subscribeTo(String configurationName, URL xsdStreamSource, UpdateListener<T> listener, Class<T> configurationClass) {
        subscribeTo("",configurationName, listener, getPooledJaxbConfigurationParser(configurationClass, xsdStreamSource),true);
       
        
    }

    @Override
    public <T> void subscribeTo(String filterName,String configurationName, URL xsdStreamSource, UpdateListener<T> listener, Class<T> configurationClass) {
        subscribeTo(filterName,configurationName, listener, getPooledJaxbConfigurationParser(configurationClass, xsdStreamSource),true);
       
        
    }
        

    @Override
    public <T> void subscribeTo(String filterName,String configurationName, UpdateListener<T> listener, ConfigurationParser<T> customParser) {
        subscribeTo(filterName,configurationName, listener, customParser, true);
    }

    @Override
    public <T> void subscribeTo(String filterName, String configurationName, UpdateListener<T> listener, ConfigurationParser<T> customParser, boolean sendNotificationNow) {
        final ConfigurationResource resource = resourceResolver.resolve(configurationName);
        updateManager.registerListener(listener, resource, customParser, filterName);
        if (sendNotificationNow) {
            // Initial load of the cfg object
            PowerApiConfigurationManager.loadConfig(filterName, configurationName, listener, customParser, getConfigurationInformation(), resource);
        }
    }

    /**
     * Refactored this into a static package-private (e.g. friendly) helper method
     * so that this logic was centralized and reusable.
     */
    static void loadConfig(String filterName,
                           String configurationName,
                           UpdateListener listener,
                           ConfigurationParser customParser,
                           ConfigurationInformation configurationInformation,
                           ConfigurationResource resource) {
        try {
            listener.configurationUpdated(customParser.read(resource));
            LOG.debug("Configuration Updated: " + resource.toString());
            if (filterName != null && !filterName.isEmpty() && listener.isInitialized()) {
                configurationInformation.setFilterLoadingInformation(filterName, listener.isInitialized(), resource);
            } else {
                configurationInformation.setFilterLoadingFailedInformation(filterName, resource, "Failed loading File");
            }
        } catch (Exception ex) {
            if (filterName != null && !filterName.isEmpty()) {
                configurationInformation.setFilterLoadingFailedInformation(filterName, resource, ex.getMessage());
            }
            if (ex.getCause() instanceof FileNotFoundException) {
                LOG.error("An I/O error has occurred while processing resource " + configurationName + " that is used by filter specified in system-model.cfg.xml - Reason: " + ex.getCause().getMessage());

            } else {
                LOG.error("Configuration update error. Reason: {}", ex.getLocalizedMessage());
                LOG.trace("", ex);
            }
            // In an effort to track down all the filters that were using the unchecked exceptions to elicit this behavior...
            // The ClassCastException is thrown by JaxbConfigurationParser.
            if (!(ex instanceof UpdateFailedException) && !(ex instanceof ClassCastException)) {
                LOG.error("<><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><>");
                LOG.error("Please send this stack trace to the Repose developers at: ReposeCore@Rackspace.com");
                LOG.error("<STACK_TRACE>", ex);
                LOG.error("</STACK_TRACE>");
                LOG.error("<><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><><>");
            }
        }
    }

    @Override
    public void unsubscribeFrom(String configurationName, UpdateListener listener) {
        updateManager.unregisterListener(listener, resourceResolver.resolve(configurationName));
    }

    public <T> ConfigurationParser<T> getPooledJaxbConfigurationParser(Class<T> configurationClass, URL xsdStreamSource) {
        final WeakReference<ConfigurationParser> parserReference = parserLookaside.get(configurationClass);
        ConfigurationParser<T> parser = parserReference != null ? parserReference.get() : null;

        if (parser == null) {
            try {
                parser = ConfigurationParserFactory.getXmlConfigurationParser(configurationClass, xsdStreamSource);
            } catch (ConfigurationResourceException cre) {
                throw new ConfigurationServiceException("Failed to create a JAXB context for a configuration parser. Reason: " + cre.getMessage(), cre);
            }

            parserLookaside.put(configurationClass, new WeakReference<ConfigurationParser>(parser));
        }

        return parser;
    }
   
}
