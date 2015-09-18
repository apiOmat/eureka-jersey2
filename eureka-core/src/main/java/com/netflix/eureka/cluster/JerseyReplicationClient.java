package com.netflix.eureka.cluster;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaIdentityHeaderFilter;
import com.netflix.discovery.shared.EurekaJerseyClient;
import com.netflix.discovery.shared.EurekaJerseyClientImpl.EurekaJerseyClientBuilder;
import com.netflix.discovery.shared.JerseyEurekaHttpClient;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.EurekaServerIdentity;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * @author Tomasz Bak
 */
public class JerseyReplicationClient extends JerseyEurekaHttpClient implements HttpReplicationClient {

    private static final Logger logger = LoggerFactory.getLogger(JerseyReplicationClient.class);

    private final EurekaJerseyClient jerseyClient;
    private final Client jerseyApacheClient;

    public JerseyReplicationClient(EurekaServerConfig config, String serviceUrl) {
        super(serviceUrl);
        String name = getClass().getSimpleName() + ": " + serviceUrl + "apps/: ";

        try {
            String hostname;
            try {
                hostname = new URL(serviceUrl).getHost();
            } catch (MalformedURLException e) {
                hostname = serviceUrl;
            }

            String jerseyClientName = "Discovery-PeerNodeClient-" + hostname;
            EurekaJerseyClientBuilder clientBuilder = new EurekaJerseyClientBuilder()
                    .withClientName(jerseyClientName)
                    .withUserAgent("Java-EurekaClient-Replication")
                    .withConnectionTimeout(config.getPeerNodeConnectTimeoutMs())
                    .withReadTimeout(config.getPeerNodeReadTimeoutMs())
                    .withMaxConnectionsPerHost(config.getPeerNodeTotalConnectionsPerHost())
                    .withMaxTotalConnections(config.getPeerNodeTotalConnections())
                    .withConnectionIdleTimeout(config.getPeerNodeConnectionIdleTimeoutSeconds());

            if (serviceUrl.startsWith("https://") &&
                    "true".equals(System.getProperty("com.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory"))) {
                clientBuilder.withSystemSSLConfiguration();
            }
            jerseyClient = clientBuilder.build();
            jerseyApacheClient = jerseyClient.getClient();
            // TODO: Fix this
//            jerseyApacheClient.addFilter(new DynamicGZIPContentEncodingFilter(config));
        } catch (Throwable e) {
            throw new RuntimeException("Cannot Create new Replica Node :" + name, e);
        }

        String ip = null;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logger.warn("Cannot find localhost ip", e);
        }
        EurekaServerIdentity identity = new EurekaServerIdentity(ip);
        jerseyApacheClient.register(new EurekaIdentityHeaderFilter(identity));
    }

    @Override
    protected Client getJerseyClient() {
        return jerseyApacheClient;
    }

    @Override
    protected void addExtraHeaders(Invocation.Builder webResource) {
        webResource.header(PeerEurekaNode.HEADER_REPLICATION, "true");
    }

    /**
     * Compared to regular heartbeat, in the replication channel the server may return a more up to date
     * instance copy.
     */
    @Override
    public HttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info, InstanceStatus overriddenStatus) {
        String urlPath = "apps/" + appName + '/' + id;
        Response response = null;
        try {
            WebTarget webTarget = getJerseyClient().target(serviceUrl)
                    .path(urlPath)
                    .queryParam("status", info.getStatus().toString())
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString());
            if (overriddenStatus != null) {
                webTarget = webTarget.queryParam("overriddenstatus", overriddenStatus.name());
            }
            Invocation.Builder requestBuilder = webTarget.request();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).put(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE));
            InstanceInfo infoFromPeer = null;
            if (response.getStatus() == Status.CONFLICT.getStatusCode() && response.hasEntity()) {
                infoFromPeer = response.readEntity(InstanceInfo.class);
            }
            return HttpResponse.responseWith(response.getStatus(), infoFromPeer);
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[heartbeat] Jersey HTTP PUT {}; statusCode={}", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<Void> statusUpdate(String asgName, ASGStatus newStatus) {
        Response response = null;
        try {
            String urlPath = "asg/" + asgName + "/status";
            response = jerseyApacheClient.target(serviceUrl)
                    .path(urlPath)
                    .queryParam("value", newStatus.name())
                    .request()
                    .header(PeerEurekaNode.HEADER_REPLICATION, "true")
                    .put(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE));
            return HttpResponse.responseWith(response.getStatus());
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<ReplicationListResponse> submitBatchUpdates(ReplicationList replicationList) {
        Response response = null;
        try {
            response = jerseyApacheClient.target(serviceUrl)
                    .path(PeerEurekaNode.BATCH_URL_PATH)
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.entity(replicationList, MediaType.APPLICATION_JSON_TYPE));
            if (!isSuccess(response.getStatus())) {
                return HttpResponse.responseWith(response.getStatus());
            }
            ReplicationListResponse batchResponse = response.readEntity(ReplicationListResponse.class);
            return HttpResponse.responseWith(response.getStatus(), batchResponse);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        jerseyClient.destroyResources();
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}
