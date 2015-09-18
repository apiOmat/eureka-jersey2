package com.netflix.discovery.shared;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

/**
 * @author Tomasz Bak
 */
public abstract class JerseyEurekaHttpClient implements EurekaHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(JerseyEurekaHttpClient.class);

    protected final String serviceUrl;

    protected JerseyEurekaHttpClient(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    protected abstract Client getJerseyClient();

    @Override
    public HttpResponse<Void> register(InstanceInfo info) {
        String urlPath = "apps/" + info.getAppName();
        Response response = null;
        try {
            Invocation.Builder builder = getJerseyClient().target(serviceUrl).path(urlPath).request();
            addExtraHeaders(builder);
            response = builder
                    .header("Accept-Encoding", "gzip")
                    .accept(MediaType.APPLICATION_JSON)
                    .post(Entity.entity(info, MediaType.APPLICATION_JSON_TYPE));
            return HttpResponse.responseWith(response.getStatus());
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[register] Jersey HTTP POST {} with instance {}; statusCode={}", urlPath, info.getId(),
                        response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<Void> cancel(String appName, String id) {
        String urlPath = "apps/" + appName + '/' + id;
        Response response = null;
        try {
            Invocation.Builder builder = getJerseyClient().target(serviceUrl).path(urlPath).request();
            addExtraHeaders(builder);
            response = builder.delete();
            return HttpResponse.responseWith(response.getStatus());
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[cancel] Jersey HTTP DELETE {}; statusCode={}", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info, InstanceStatus overriddenStatus) {
        String urlPath = "apps/" + appName + '/' + id;
        Response response = null;
        try {
            WebTarget webResource = getJerseyClient().target(serviceUrl)
                    .path(urlPath)
                    .queryParam("status", info.getStatus().toString())
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString());
            if (overriddenStatus != null) {
                webResource = webResource.queryParam("overriddenstatus", overriddenStatus.name());
            }
            Invocation.Builder requestBuilder = webResource.request();
            addExtraHeaders(requestBuilder);
            return HttpResponse.responseWith(response.getStatus());
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
    public HttpResponse<Void> statusUpdate(String appName, String id, InstanceStatus newStatus, InstanceInfo info) {
        String urlPath = "apps/" + appName + "/" + id + "/status";
        Response response = null;
        try {
            Invocation.Builder requestBuilder = getJerseyClient().target(serviceUrl)
                    .path(urlPath)
                    .queryParam("value", newStatus.name())
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString())
                    .request();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.put(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE));
            return HttpResponse.responseWith(response.getStatus());
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[statusUpdate] Jersey HTTP PUT {}; statusCode={}", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<Void> deleteStatusOverride(String appName, String id, InstanceInfo info) {
        String urlPath = "apps/" + appName + '/' + id + "/status";
        Response response = null;
        try {
            Invocation.Builder requestBuilder = getJerseyClient().target(serviceUrl)
                    .path(urlPath)
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString())
                    .request();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.delete();
            return HttpResponse.responseWith(response.getStatus());
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[statusOverrideDelete] Jersey HTTP DELETE {}; statusCode={}", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<Applications> getApplications() {
        String urlPath = "apps/";
        Response response = null;
        try {
            Invocation.Builder builder = getJerseyClient().target(serviceUrl).path(urlPath).request();
            addExtraHeaders(builder);
            response = builder
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .get();

            Applications applications = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.getEntity() != null) {
                applications = response.readEntity(Applications.class);
            }
            return HttpResponse.responseWith(response.getStatus(), applications);
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[getApplications] Jersey HTTP GET {}; statusCode=", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<Applications> getDelta() {
        String urlPath = "apps/delta";
        Response response = null;
        try {
            Invocation.Builder builder = getJerseyClient().target(serviceUrl).path(urlPath).request();
            addExtraHeaders(builder);
            response = builder.accept(MediaType.APPLICATION_JSON_TYPE).get(Response.class);

            Applications applications = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                applications = response.readEntity(Applications.class);
            }
            return HttpResponse.responseWith(response.getStatus(), applications);
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[getDelta] Jersey HTTP GET {}; statusCode=", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<InstanceInfo> getInstance(String appName, String id) {
        String urlPath = "apps/" + appName + '/' + id;
        Response response = null;
        try {
            Invocation.Builder builder = getJerseyClient().target(serviceUrl).path(urlPath).request();
            addExtraHeaders(builder);
            response = builder.accept(MediaType.APPLICATION_JSON_TYPE).get(Response.class);

            InstanceInfo infoFromPeer = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                infoFromPeer = response.readEntity(InstanceInfo.class);
            }
            return HttpResponse.responseWith(response.getStatus(), infoFromPeer);
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("[getInstance] Jersey HTTP GET {}; statusCode=", urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public void shutdown() {
        getJerseyClient().close();
    }

    protected void addExtraHeaders(Invocation.Builder webResource) {
        // No-op
    }
}
