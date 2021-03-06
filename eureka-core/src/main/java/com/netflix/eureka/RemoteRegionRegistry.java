/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.netflix.eureka;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.discovery.EurekaIdentityHeaderFilter;
import com.netflix.discovery.TimedSupervisorTask;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.EurekaJerseyClient;
import com.netflix.discovery.shared.EurekaJerseyClientImpl.EurekaJerseyClientBuilder;
import com.netflix.discovery.shared.LookupService;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.glassfish.jersey.message.GZipEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles all registry operations that needs to be done on a eureka service running in an other region.
 * <p>
 * The primary operations include fetching registry information from remote region and fetching delta information
 * on a periodic basis.
 *
 * @author Karthik Ranganathan
 */
public class RemoteRegionRegistry implements LookupService<String> {
    private static final Logger logger = LoggerFactory.getLogger(RemoteRegionRegistry.class);

    private static final EurekaServerConfig EUREKA_SERVER_CONFIG =
            EurekaServerConfigurationManager.getInstance().getConfiguration();

    private final Client discoveryApacheClient;
    private final EurekaJerseyClient discoveryJerseyClient;
    private final com.netflix.servo.monitor.Timer fetchRegistryTimer;
    private final URL remoteRegionURL;

    private final ScheduledExecutorService scheduler;
    // monotonically increasing generation counter to ensure stale threads do not reset registry to an older version
    private final AtomicLong fullRegistryGeneration = new AtomicLong(0);
    private final AtomicLong deltaGeneration = new AtomicLong(0);

    private final AtomicReference<Applications> applications = new AtomicReference<Applications>();
    private final AtomicReference<Applications> applicationsDelta = new AtomicReference<Applications>();
    private volatile boolean readyForServingData;

    public RemoteRegionRegistry(String regionName, URL remoteRegionURL) {
        this.remoteRegionURL = remoteRegionURL;
        this.fetchRegistryTimer = Monitors.newTimer(this.remoteRegionURL.toString() + "_FetchRegistry");

        EurekaJerseyClientBuilder clientBuilder = new EurekaJerseyClientBuilder()
                .withUserAgent("Java-EurekaClient-RemoteRegion")
                .withConnectionTimeout(EUREKA_SERVER_CONFIG.getRemoteRegionConnectTimeoutMs())
                .withReadTimeout(EUREKA_SERVER_CONFIG.getRemoteRegionReadTimeoutMs())
                .withMaxConnectionsPerHost(EUREKA_SERVER_CONFIG.getRemoteRegionTotalConnectionsPerHost())
                .withMaxTotalConnections(EUREKA_SERVER_CONFIG.getRemoteRegionTotalConnections())
                .withConnectionIdleTimeout(EUREKA_SERVER_CONFIG.getRemoteRegionConnectionIdleTimeoutSeconds());

        if (remoteRegionURL.getProtocol().equals("http")) {
            clientBuilder.withClientName("Discovery-RemoteRegionClient-" + regionName);
        } else if ("true".equals(System.getProperty("com.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory"))) {
            clientBuilder.withClientName("Discovery-RemoteRegionSystemSecureClient-" + regionName)
                    .withSystemSSLConfiguration();
        } else {
            clientBuilder.withClientName("Discovery-RemoteRegionSecureClient-" + regionName)
                    .withTrustStoreFile(
                            EUREKA_SERVER_CONFIG.getRemoteRegionTrustStore(),
                            EUREKA_SERVER_CONFIG.getRemoteRegionTrustStorePassword()
                    );
        }
        discoveryJerseyClient = clientBuilder.build();
        discoveryApacheClient = discoveryJerseyClient.getClient();

        // should we enable GZip decoding of responses based on Response
        // Headers?
        if (EUREKA_SERVER_CONFIG.shouldGZipContentFromRemoteRegion()) {
            // compressed only if there exists a 'Content-Encoding' header
            // whose value is "gzip"
            discoveryApacheClient.register(new GZipEncoder());
        }

        String ip = null;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logger.warn("Cannot find localhost ip", e);
        }
        EurekaServerIdentity identity = new EurekaServerIdentity(ip);
        discoveryApacheClient.register(new EurekaIdentityHeaderFilter(identity));

        applications.set(new Applications());
        try {
            if (fetchRegistry()) {
                this.readyForServingData = true;
            } else {
                logger.warn("Failed to fetch remote registry. This means this eureka server is not ready for serving "
                        + "traffic.");
            }
        } catch (Throwable e) {
            logger.error("Problem fetching registry information :", e);
        }

        // remote region fetch
        Runnable remoteRegionFetchTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (fetchRegistry()) {
                        readyForServingData = true;
                    } else {
                        logger.warn("Failed to fetch remote registry. This means this eureka server is not "
                                + "ready for serving traffic.");
                    }
                } catch (Throwable e) {
                    logger.error(
                            "Error getting from remote registry :", e);
                }
            }
        };

        ThreadPoolExecutor remoteRegionFetchExecutor = new ThreadPoolExecutor(
                1, EUREKA_SERVER_CONFIG.getRemoteRegionFetchThreadPoolSize(), 0, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());  // use direct handoff

        scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("Eureka-RemoteRegionCacheRefresher_" + regionName + "-%d")
                        .setDaemon(true)
                        .build());

        scheduler.schedule(
                new TimedSupervisorTask(
                        "RemoteRegionFetch_" + regionName,
                        scheduler,
                        remoteRegionFetchExecutor,
                        EUREKA_SERVER_CONFIG.getRemoteRegionRegistryFetchInterval(),
                        TimeUnit.SECONDS,
                        5,  // exponential backoff bound
                        remoteRegionFetchTask
                ),
                EUREKA_SERVER_CONFIG.getRemoteRegionRegistryFetchInterval(), TimeUnit.SECONDS);
    }

    /**
     * Check if this registry is ready for serving data.
     *
     * @return true if ready, false otherwise.
     */
    public boolean isReadyForServingData() {
        return readyForServingData;
    }

    /**
     * Fetch the registry information from the remote region.
     *
     * @return true, if the fetch was successful, false otherwise.
     */
    private boolean fetchRegistry() {
        Response response = null;
        Stopwatch tracer = fetchRegistryTimer.start();

        try {
            // If the delta is disabled or if it is the first time, get all
            // applications
            if (EUREKA_SERVER_CONFIG.shouldDisableDeltaForRemoteRegions()
                    || (getApplications() == null)
                    || (getApplications().getRegisteredApplications().size() == 0)) {
                logger.info("Disable delta property : {}", EUREKA_SERVER_CONFIG
                        .shouldDisableDeltaForRemoteRegions());
                logger.info("Application is null : {}",
                        (getApplications() == null));
                logger.info(
                        "Registered Applications size is zero : {}",
                        (getApplications().getRegisteredApplications().size() == 0));
                response = storeFullRegistry();
            } else {
                long currDeltaGeneration = deltaGeneration.get();
                Applications delta = null;
                response = fetchRemoteRegistry(true);
                if (null != response) {
                    if (response.getStatus() == Status.OK.getStatusCode()) {
                        delta = response.readEntity(Applications.class);
                        if (delta == null) {
                            logger.error("The delta is null for some reason. Not storing this information");
                        } else if (deltaGeneration.compareAndSet(currDeltaGeneration, currDeltaGeneration + 1)) {
                            this.applicationsDelta.set(delta);
                        } else {
                            delta = null;  // set the delta to null so we don't use it
                            logger.warn("Not updating delta as another thread is updating it already");
                        }
                    }
                    if (delta == null) {
                        logger.warn("The server does not allow the delta revision to be applied because it is not "
                                + "safe. Hence got the full registry.");
                        this.closeResponse(response);
                        response = fetchRemoteRegistry(true);
                    } else {
                        updateDelta(delta);
                        String reconcileHashCode = getApplications()
                                .getReconcileHashCode();
                        // There is a diff in number of instances for some reason
                        if ((!reconcileHashCode.equals(delta.getAppsHashCode()))) {
                            response = reconcileAndLogDifference(response, delta, reconcileHashCode);

                        }
                    }
                }
            }
            logTotalInstances();

            logger.debug("Remote Registry Fetch Status : {}", null == response ? null : response.getStatus());
        } catch (Throwable e) {
            logger.error(
                    "Unable to fetch registry information from the remote registry "
                            + this.remoteRegionURL.toString(), e);
            return false;

        } finally {
            if (tracer != null) {
                tracer.stop();
            }
            closeResponse(response);
        }
        return null != response;
    }

    /**
     * Updates the delta information fetches from the eureka server into the
     * local cache.
     *
     * @param delta the delta information received from eureka server in the last
     *              poll cycle.
     */
    private void updateDelta(Applications delta) {
        int deltaCount = 0;
        for (Application app : delta.getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                ++deltaCount;
                if (ActionType.ADDED.equals(instance.getActionType())) {
                    Application existingApp = getApplications()
                            .getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        getApplications().addApplication(app);
                    }
                    logger.debug("Added instance {} to the existing apps ",
                            instance.getId());
                    getApplications().getRegisteredApplications(
                            instance.getAppName()).addInstance(instance);
                } else if (ActionType.MODIFIED.equals(instance.getActionType())) {
                    Application existingApp = getApplications()
                            .getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        getApplications().addApplication(app);
                    }
                    logger.debug("Modified instance {} to the existing apps ",
                            instance.getId());

                    getApplications().getRegisteredApplications(
                            instance.getAppName()).addInstance(instance);

                } else if (ActionType.DELETED.equals(instance.getActionType())) {
                    Application existingApp = getApplications()
                            .getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        getApplications().addApplication(app);
                    }
                    logger.debug("Deleted instance {} to the existing apps ",
                            instance.getId());
                    getApplications().getRegisteredApplications(
                            instance.getAppName()).removeInstance(instance);
                }
            }
        }
        logger.debug(
                "The total number of instances fetched by the delta processor : {}",
                deltaCount);

    }

    /**
     * Close HTTP response object and its respective resources.
     *
     * @param response the HttpResponse object.
     */
    private void closeResponse(Response response) {
        if (response != null) {
            try {
                response.close();
            } catch (Throwable th) {
                logger.error("Cannot release response resource :", th);
            }
        }
    }

    /**
     * Gets the full registry information from the eureka server and stores it
     * locally.
     *
     * @return the full registry information.
     */
    public Response storeFullRegistry() {
        long currentUpdateGeneration = fullRegistryGeneration.get();
        Response response = fetchRemoteRegistry(false);
        if (response == null) {
            logger.error("The response is null.");
            return null;
        }
        Applications apps = response.readEntity(Applications.class);
        if (apps == null) {
            logger.error("The application is null for some reason. Not storing this information");
        } else if (fullRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            applications.set(apps);
        } else {
            logger.warn("Not updating applications as another thread is updating it already");
        }
        logger.info("The response status is {}", response.getStatus());
        return response;
    }

    /**
     * Fetch registry information from the remote region.
     *
     * @param delta - true, if the fetch needs to get deltas, false otherwise
     * @return - response which has information about the data.
     */
    private Response fetchRemoteRegistry(boolean delta) {
        logger.info(
                "Getting instance registry info from the eureka server : {} , delta : {}",
                this.remoteRegionURL, delta);
        Response response = null;
        try {

            String urlPath = delta ? "apps/delta" : "apps/";

            response = discoveryApacheClient
                    .target(this.remoteRegionURL.toString() + urlPath)
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .get();
            int httpStatus = response.getStatus();
            if (httpStatus >= 200 && httpStatus < 300) {
                logger.debug("Got the data successfully : {}", httpStatus);
            } else {
                logger.warn("Cannot get the data from {} : {}", this.remoteRegionURL.toString(), httpStatus);
                return null; // To short circuit entity evaluation.
            }

        } catch (Throwable t) {
            logger.error("Can't get a response from " + this.remoteRegionURL, t);

        }
        return response;
    }

    /**
     * Reconciles the delta information fetched to see if the hashcodes match.
     *
     * @param response          - the response of the delta fetch.
     * @param delta             - the delta information fetched previously for reconcililation.
     * @param reconcileHashCode - the hashcode for comparison.
     * @return - response
     * @throws Throwable
     */
    private Response reconcileAndLogDifference(Response response,
                                               Applications delta, String reconcileHashCode) throws Throwable {
        logger.warn(
                "The Reconcile hashcodes do not match, client : {}, server : {}. Getting the full registry",
                reconcileHashCode, delta.getAppsHashCode());

        this.closeResponse(response);
        response = this.fetchRemoteRegistry(false);
        if (null == response) {
            logger.warn("Response is null while fetching remote registry during reconcile difference.");
            return null;
        }
        Applications serverApps = response.readEntity(Applications.class);
        Map<String, List<String>> reconcileDiffMap = getApplications()
                .getReconcileMapDiff(serverApps);
        String reconcileString = "";
        for (Map.Entry<String, List<String>> mapEntry : reconcileDiffMap
                .entrySet()) {
            reconcileString = reconcileString + mapEntry.getKey() + ": ";
            for (String displayString : mapEntry.getValue()) {
                reconcileString = reconcileString + displayString;
            }
            reconcileString = reconcileString + "\n";
        }
        logger.warn("The reconcile string is {}", reconcileString);
        applications.set(serverApps);
        applicationsDelta.set(serverApps);
        logger.warn(
                "The Reconcile hashcodes after complete sync up, client : {}, server : {}.",
                getApplications().getReconcileHashCode(),
                delta.getAppsHashCode());
        return response;
    }

    /**
     * Logs the total number of non-filtered instances stored locally.
     */
    private void logTotalInstances() {
        int totInstances = 0;
        for (Application application : getApplications().getRegisteredApplications()) {
            totInstances += application.getInstancesAsIsFromEureka().size();
        }
        logger.debug("The total number of all instances in the client now is {}", totInstances);
    }

    @Override
    public Applications getApplications() {
        return applications.get();
    }

    @Override
    public InstanceInfo getNextServerFromEureka(String arg0, boolean arg1) {
        return null;
    }

    @Override
    public Application getApplication(String appName) {
        return this.applications.get().getRegisteredApplications(appName);
    }

    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        List<InstanceInfo> list = Collections.emptyList();

        for (Application app : applications.get().getRegisteredApplications()) {
            InstanceInfo info = app.getByInstanceId(id);
            if (info != null) {
                list.add(info);
                return list;
            }
        }
        return list;
    }

    public Applications getApplicationDeltas() {
        return this.applicationsDelta.get();
    }

}
