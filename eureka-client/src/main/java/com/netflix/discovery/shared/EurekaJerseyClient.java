package com.netflix.discovery.shared;

import javax.ws.rs.client.Client;

/**
 * @author David Liu
 */
public interface EurekaJerseyClient {

    Client getClient();

    /**
     * Clean up resources.
     */
    void destroyResources();
}
