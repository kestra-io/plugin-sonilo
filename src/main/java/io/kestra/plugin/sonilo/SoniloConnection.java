package io.kestra.plugin.sonilo;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;

/**
 * Connection settings shared by Sonilo tasks and the polling trigger.
 */
public interface SoniloConnection {
    Property<String> getApiToken();

    Property<String> getBaseUrl();

    HttpConfiguration getOptions();
}
