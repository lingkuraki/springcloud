# Spring Cloud Eureka

## 服务治理

- 微服架构最为核心和基础的模块，主要用来实现各个微服务实例的自动化注册与发现。

> **服务注册**

每个服务单元向注册中心登记自己提供的服务，将主机与端口号、版本号、通信协议等一些附加信息告知注册中心，注册中心按**服务名**分类组织服务清单。

服务注册中心还需要以心跳的方式去监测清单中的服务是否可用，若不可用需要从服务清单中剔除，达到排除故障服务的效果。

> **服务发现**

服务间的调用不再指定具体的实例地址来实现，而是通过向服务名发起请求调用实现。所以，服务调用方在调用服务提供方接口的时候，并不知道具体的服务实例位置。

### Netflix Eureka

- 它既包含了服务端组件，也包含了客户端组件。
  - **服务端**，也称为服务的注册中心。它支持高可用配置，依托于强一致性提供良好的服务实例可用性。当集群中有分片出现故障时，那么`Eureka`就转入自我保护模式。它允许分片故障期间继续提供服务的发现和注册，当故障分片恢复运行时，集群中的其他分片会把它们的状态再次同步回来。不同可用区域的服务注册测定中心通过异步模式互相复制各自的状态。
  - **客户端**，处理服务的注册与发现。`Eureka`客户端向注册中心注册自身提供的服务并周期性地发送心跳来更新它的服务租约。同时，它也能从服务端查询当前注册的服务信息并把它们缓存到本地并周期性地刷新服务状态。

### 高可用注册中心

- 实质就是`Eureka Server`将自己作为服务注册到其它的服务中心中。

## Eureka详解

### 基础架构

- **服务注册中心**：`Euraka `提供的服务端。
- **服务提供者**：提供服务的应用，可以是`Spring Boot`应用，也可以是其它技术平台且遵循`Eureka`通信机制的应用。
- **服务消费者**：从服务注册中心获取服务列表，从而使消费者可以知道去何处调用其所需要的服务。

### 服务治理机制

> **服务提供者**

- **服务注册**：在启动的时候会发送`REST`请求的方式将自己注册到`Eureka Server`上，同时带上自身服务的一些元数据信息。元数据信息存储在一个双层结构`Map`中，其中第一层的`key`是服务层，第二层的`key`是具体服务的实例名。配置参数`eureka.client.register-with-eureka=false`，将不会启动注册操作。
- **服务同步**：服务注册中心之间互相注册服务，所以当服务提供者发送注册请求到一个服务注册中心时，也会将该请求转发给集群中相连的其他注册中心，从而实现注册中心之间的服务同步。通过服务同步，就可以在任意一台注册中心获取注册在其它注册中心的服务。
- **服务续约**：服务提供者会维护一个心跳来持续连接`Eureka Server`，以防止`Eureka Server`将该服务实例从服务列表中剔除。

> **服务消费者**

- **获取服务**：启动服务消费者时，会发送一个`REST`请求给服务注册中心，来获取上面注册的服务清单。`Eureka Server`会维护一份只读的服务清单来返回给客户端，同时该缓存清单会每隔`30`秒更新一次。
- **服务调用**：服务消费者在获取服务清单后，通过服务名可以获取具体提供服务的实例名和该实例的元数据信息。
  - 对于访问实例的选择，`Eureka`中有`Region`和`Zone`的概念，一个`Region`中可以包含多个`Zone`，每个服务客户端需要被注册到一个`Zone`中，所以每一个客户端都对应一个`Region`和一个`Zone`。
  - 在进行服务调用的时候，优先访问同处一个`Zone`中的服务提供方，若访问不到，就访问其它的`Zone`。
- **服务下线**：当服务实例进行正常的关闭操作时，它会触发一个服务下线的`REST`请求给`Eureka Server`，告诉服务注册中心它要下线了。服务端在接收到请求之后，将该服务状态设置为下线`DOWN`，并把该事件传播出去。

> **服务注册中心**

- **失效剔除**：`Eureka Server`在启动的时候会创建一个定时任务，默认每隔一段时间`(default 60s)`将当前清单中超时`
  (default 90s)`没有续约的服务剔除出去。
- **自我保护**：`Eureka Server`会统计心跳失败的比例在`15`分钟之内是否低于`85%`。如果出现低于的情况，会将当前的实例注册信息保护起来，让这些实例不会过期，尽可能保护这些注册信息。
  - 但这就会引发另一个问题：如果这段保护期内实例若出现问题，那么客户端很可能拿到了实际上已经不存在的实例，就会出现调用失败的情况。所以**客户端必须要有容错机制**。
  - 可以配置参数`eureka.server.enable-self-preservation=false`来关闭自我保护机制，以确保注册中心可以将不可用的实例正确剔除。

### 源码分析

- 将一个`Spring Boot`应用注册到`Eureka Server`或是从`Eureka Server`中获取服务列表时，主要做了两件事：
  1. 在应用类中配置了`@EnableDiscoveryClient`注解；
  2. 在`application.properties`中用`eureka.client.service-url.defaultZone`参数指定了服务注册中心的位置。

> 对`URL`列表进行配置

- 在`EndpointUtils`类中，有如下方法。从该方法中可以看出，客户端一次加载了两个内容，第一个是`Region`，第二个是`Zone`。

```java
public static Map<String, List<String>> getServiceUrlsMapFromConfig(EurekaClientConfig 
								   clientConfig, String instanceZone, boolean preferSameZone) {
    Map<String, List<String>> orderedUrls = new LinkedHashMap<>();
    // 从配置读取了一个Region返回，所以一个微服务应用只可以属于一个Region
    String region = getRegion(clientConfig);
    // 一个region可以对应多个Zone
    String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion());
    if (availZones == null || availZones.length == 0) {
        availZones = new String[1];
        availZones[0] = DEFAULT_ZONE;
    }
    logger.debug("The availability zone for the given region {} are {}", region, availZones);
    int myZoneOffset = getZoneOffset(instanceZone, preferSameZone, availZones);
    String zone = availZones[myZoneOffset];
    // 获取服务路径集合
    List<String> serviceUrls = clientConfig.getEurekaServerServiceUrls(zone);
    if (serviceUrls != null) {
        orderedUrls.put(zone, serviceUrls);
    }
    //
    int currentOffset = myZoneOffset == (availZones.length - 1) ? 0 : (myZoneOffset + 1);
    // 判断是否处于同一个Zone中
    while (currentOffset != myZoneOffset) {
        zone = availZones[currentOffset];
        serviceUrls = clientConfig.getEurekaServerServiceUrls(zone);
        if (serviceUrls != null) {
            orderedUrls.put(zone, serviceUrls);
        }
        if (currentOffset == (availZones.length - 1)) {
            currentOffset = 0;
        } else {
            currentOffset++;
        }
    }
    if (orderedUrls.size() < 1) {
        throw new IllegalArgumentException("DiscoveryClient: invalid serviceUrl specified!");
    }
    return orderedUrls;
}
```

- `getRegion`方法如下

```java
public static String getRegion(EurekaClientConfig clientConfig) {
    String region = clientConfig.getRegion();
    if (region == null) {
        // 如果没有配置，则默认为"default"
        region = DEFAULT_REGION;
    }
    region = region.trim().toLowerCase();
    return region;
}
```

- `getAvailabilityZones`方法如下

```java
public String[] getAvailabilityZones(String region) {
    String value = this.availabilityZones.get(region);
    if (value == null) {
        value = DEFAULT_ZONE;
    }
    // 返回的String数组，所以Zone可以设置多个，并通过","分隔
    return value.split(",");
}
```

- `getEurekaServerService`方法如下

```java
@Override
public List<String> getEurekaServerServiceUrls(String myZone) {
	String serviceUrls = this.serviceUrl.get(myZone);
	if (serviceUrls == null || serviceUrls.isEmpty()) {
  		// 从"defaultZone"中获取serviceUrls     
		serviceUrls = this.serviceUrl.get(DEFAULT_ZONE);
	}
	if (!StringUtils.isEmpty(serviceUrls)) {
		final String[] serviceUrlsSplit = StringUtils.commaDelimitedListToStringArray(serviceUrls);
		List<String> eurekaServiceUrls = new ArrayList<>(serviceUrlsSplit.length);
		for (String eurekaServiceUrl : serviceUrlsSplit) {
			if (!endsWithSlash(eurekaServiceUrl)) {
				eurekaServiceUrl += "/";
			}
			eurekaServiceUrls.add(eurekaServiceUrl.trim());
		}
		return eurekaServiceUrls;
	}
	return new ArrayList<>();
}
```

> **服务注册**

- 查看`DiscoveryClient`类的构造函数，其中调用了如下的`initScheduledTasks`方法。

```java
private void initScheduledTasks() {
    // 服务的获取
    if (clientConfig.shouldFetchRegistry()) {
        // registry cache refresh timer
        int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
        int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
        scheduler.schedule(
                new TimedSupervisorTask(
                        "cacheRefresh",
                        scheduler,
                        cacheRefreshExecutor,
                        registryFetchIntervalSeconds,
                        TimeUnit.SECONDS,
                        expBackOffBound,
                        new CacheRefreshThread()
                ),
                registryFetchIntervalSeconds, TimeUnit.SECONDS);
    }
    // 服务注册与服务续约
    if (clientConfig.shouldRegisterWithEureka()) {
        int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
        int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
        logger.info("Starting heartbeat executor: " + "renew interval is: {}", renewalIntervalInSecs);
        // Heartbeat timer
        scheduler.schedule(
                new TimedSupervisorTask(
                        "heartbeat",
                        scheduler,
                        heartbeatExecutor,
                        renewalIntervalInSecs,
                        TimeUnit.SECONDS,
                        expBackOffBound,
                        new HeartbeatThread()
                ),
                renewalIntervalInSecs, TimeUnit.SECONDS);
        // InstanceInfoReplicator类实现了的Runnable接口
        instanceInfoReplicator = new InstanceInfoReplicator(
                this,
                instanceInfo,
                clientConfig.getInstanceInfoReplicationIntervalSeconds(),
                2); // burstSize
        statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
            @Override
            public String getId() {
                return "statusChangeListener";
            }
            @Override
            public void notify(StatusChangeEvent statusChangeEvent) {
                if (InstanceStatus.DOWN == statusChangeEvent.getStatus() ||
                        InstanceStatus.DOWN == statusChangeEvent.getPreviousStatus()) {
                    // log at warn level if DOWN was involved
                    logger.warn("Saw local status change event {}", statusChangeEvent);
                } else {
                    logger.info("Saw local status change event {}", statusChangeEvent);
                }
                instanceInfoReplicator.onDemandUpdate();
            }
        };
        if (clientConfig.shouldOnDemandUpdateStatusChange()) {
            applicationInfoManager.registerStatusChangeListener(statusChangeListener);
        }
 	// 启动该类的start方法
    instanceInfoReplicator.start(clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
    } else {
        logger.info("Not registering with Eureka server per configuration");
    }
}
```

- `InstanceInfoReplicator`类的`run`方法如下

```java
public void run() {
    try {
        discoveryClient.refreshInstanceInfo();
        Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
        if (dirtyTimestamp != null) {
            // 真正触发服务注册的地方
            discoveryClient.register();
            instanceInfo.unsetIsDirty(dirtyTimestamp);
        }
    } catch (Throwable t) {
        logger.warn("There was a problem with the instance info replicator", t);
    } finally {
        Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
        scheduledPeriodicRef.set(next);
    }
}
```

- `discoveryClient.register`方法

```java
boolean register() throws Throwable {
    logger.info(PREFIX + "{}: registering service...", appPathIdentifier);
    EurekaHttpResponse<Void> httpResponse;
    try {
        // instanceInfo对象就是注册时客户端给服务端的服务的元数据
        httpResponse = eurekaTransport.registrationClient.register(instanceInfo);
    } catch (Exception e) {
        logger.warn(PREFIX + "{} - registration failed {}", appPathIdentifier, e.getMessage(), e);
        throw e;
    }
    if (logger.isInfoEnabled()) {
        logger.info(PREFIX + "{} - registration status: {}", 
                    appPathIdentifier, httpResponse.getStatusCode());
    }
    // 说明注册操作是通过皮REST请求的方式进行的
    return httpResponse.getStatusCode() == 204;
}
```

> **服务获取与服务续约**

- 从`initScheduledTasks`函数中，服务获取与服务续约的相关代码如下：

```java
private void initScheduledTasks() {
    // 服务的获取
    if (clientConfig.shouldFetchRegistry()) {
        // registry cache refresh timer 注册表缓存的刷新时间
        // 默认为30s,配置参数eureka.client.registry-fetch-interval-seconds
        int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
        int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
        scheduler.schedule(
                new TimedSupervisorTask(
                        "cacheRefresh",
                        scheduler,
                        cacheRefreshExecutor,
                        registryFetchIntervalSeconds,
                        TimeUnit.SECONDS,
                        expBackOffBound,
                        new CacheRefreshThread()
                ),
                registryFetchIntervalSeconds, TimeUnit.SECONDS);
    }
    // 服务注册与服务续约
    if (clientConfig.shouldRegisterWithEureka()) {
        // 默认为30s，配置参数eureka.instance.lease-renewal-interval-in-seconds
        int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
        // 默认为90s,配置参数eureka.instance.lease-expiration-duration-in-seconds
        int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
        logger.info("Starting heartbeat executor: " + "renew interval is: {}", renewalIntervalInSecs);
        // Heartbeat timer
        scheduler.schedule(
                new TimedSupervisorTask(
                        "heartbeat",
                        scheduler,
                        heartbeatExecutor,
                        renewalIntervalInSecs,
                        TimeUnit.SECONDS,
                        expBackOffBound,
                        new HeartbeatThread()
                ),
                renewalIntervalInSecs, TimeUnit.SECONDS);
        ......
    }
}
```

- 服务续约的具体实现方法`renew`代码如下：

```java
boolean renew() {
    EurekaHttpResponse<InstanceInfo> httpResponse;
    try {
        httpResponse = eurekaTransport.registrationClient.sendHeartBeat(instanceInfo.getAppName(), instanceInfo.getId(), instanceInfo, null);
        logger.debug(PREFIX + "{} - Heartbeat status: {}", appPathIdentifier, httpResponse.getStatusCode());
        if (httpResponse.getStatusCode() == 404) {
            REREGISTER_COUNTER.increment();
            logger.info(PREFIX + "{} - Re-registering apps/{}", appPathIdentifier, instanceInfo.getAppName());
            long timestamp = instanceInfo.setIsDirtyWithTime();
            boolean success = register();
            if (success) {
                instanceInfo.unsetIsDirty(timestamp);
            }
            return success;
        }
        return httpResponse.getStatusCode() == 200;
    } catch (Throwable e) {
        logger.error(PREFIX + "{} - was unable to send heartbeat!", appPathIdentifier, e);
        return false;
    }
}
```

- 服务的获取则会根据是否是第一次获取发起不同的`REST`请求和相应的处理。

```java
private boolean fetchRegistry(boolean forceFullRegistryFetch) {
    Stopwatch tracer = FETCH_REGISTRY_TIMER.start();
    try {
        // If the delta is disabled or if it is the first time, get all
        // applications
        Applications applications = getApplications();
        if (clientConfig.shouldDisableDelta()
                || (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress()))
                || forceFullRegistryFetch
                || (applications == null)
                || (applications.getRegisteredApplications().size() == 0)
                || (applications.getVersion() == -1)) //Client application does not have latest library supporting delta
        {
            logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
            logger.info("Single vip registry refresh property : {}", clientConfig.getRegistryRefreshSingleVipAddress());
            logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
            logger.info("Application is null : {}", (applications == null));
            logger.info("Registered Applications size is zero : {}",
                    (applications.getRegisteredApplications().size() == 0));
            logger.info("Application version is -1: {}", (applications.getVersion() == -1));
            getAndStoreFullRegistry();
        } else {
            getAndUpdateDelta(applications);
        }
        applications.setAppsHashCode(applications.getReconcileHashCode());
        logTotalInstances();
    } catch (Throwable e) {
        logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier, e.getMessage(), e);
        return false;
    } finally {
        if (tracer != null) {
            tracer.stop();
        }
    }
    // Notify about cache refresh before updating the instance remote status
    onCacheRefreshed();
    // Update remote status based on refreshed data held in the cache
    updateInstanceRemoteStatus();
    // registry was fetched successfully, so return true
    return true;
}
```

> **服务注册中心处理**

- `Eureka Server`对于各类`REST`请求的定义都位于`com.netflix.eureka.resources`包下
- 以**服务注册**为例

```java
@POST
@Consumes({"application/json", "application/xml"})
public Response addInstance(InstanceInfo info,
                            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
    logger.debug("Registering instance {} (replication={})", info.getId(), isReplication);
    // validate that the instanceinfo contains all the necessary required fields
    // 对id，hostname等参数的一系列校验
    if (isBlank(info.getId())) {
        return Response.status(400).entity("Missing instanceId").build();
    } else if (isBlank(info.getHostName())) {
        return Response.status(400).entity("Missing hostname").build();
    } else if (isBlank(info.getIPAddr())) {
        return Response.status(400).entity("Missing ip address").build();
    } else if (isBlank(info.getAppName())) {
        return Response.status(400).entity("Missing appName").build();
    } else if (!appName.equals(info.getAppName())) {
        return Response.status(400).entity("Mismatched appName, expecting " + appName + " but was " + info.getAppName()).build();
    } else if (info.getDataCenterInfo() == null) {
        return Response.status(400).entity("Missing dataCenterInfo").build();
    } else if (info.getDataCenterInfo().getName() == null) {
        return Response.status(400).entity("Missing dataCenterInfo Name").build();
    }
    // handle cases where clients may be registering with bad DataCenterInfo with missing data
    DataCenterInfo dataCenterInfo = info.getDataCenterInfo();
    if (dataCenterInfo instanceof UniqueIdentifier) {
        String dataCenterInfoId = ((UniqueIdentifier) dataCenterInfo).getId();
        if (isBlank(dataCenterInfoId)) {
            boolean experimental = "true".equalsIgnoreCase(serverConfig.getExperimental("registration.validation.dataCenterInfoId"));
            if (experimental) {
                String entity = "DataCenterInfo of type " + dataCenterInfo.getClass() + " must contain a valid id";
                return Response.status(400).entity(entity).build();
            } else if (dataCenterInfo instanceof AmazonInfo) {
                AmazonInfo amazonInfo = (AmazonInfo) dataCenterInfo;
                String effectiveId = amazonInfo.get(AmazonInfo.MetaDataKey.instanceId);
                if (effectiveId == null) {
                    amazonInfo.getMetadata().put(AmazonInfo.MetaDataKey.instanceId.getName(), info.getId());
                }
            } else {
                logger.warn("Registering DataCenterInfo of type {} without an appropriate id", dataCenterInfo.getClass());
            }
        }
    }
    // 运行时调用的是InstanceRegistry对象的register方法
    registry.register(info, "true".equals(isReplication));
    return Response.status(204).build();  // 204 to be backwards compatible
}
```

- `registry`方法代码如下

```java
@Override
public void register(InstanceInfo info, int leaseDuration, boolean isReplication) {
   // 传播有新服务注册的事件方法
   handleRegistration(info, leaseDuration, isReplication);
   // 调用父类的register方法，将instanceInfo中的元数据信息存储在concurrentHahsMap对象中
   super.register(info, leaseDuration, isReplication);
}

private void handleRegistration(InstanceInfo info, int leaseDuration,
		boolean isReplication) {
	log("register " + info.getAppName() + ", vip " + info.getVIPAddress()
			+ ", leaseDuration " + leaseDuration + ", isReplication " + isReplication);
    // 将新服务注册的事件传播出去
	publishEvent(new EurekaInstanceRegisteredEvent(this, info, leaseDuration, isReplication));
}
```

- 注册中心存储了两层`Map`结构，第一层的`key`存储服务名：`InstanceInfo`中的`appName`属性；第二层的`key`存储实例名：`InstanceInfo`中的`instanceId`属性。

## 配置信息

- 在`Eureka`的服务治理体系中，主要分为服务端与客户端两个不同的角色，服务端为服务注册中心，而客户端为各个提供接口的微服务应用。
- 在实际使用`Spring Cloud Eureka`的过程中，所做的配置几乎都是对`Eureka`客户端配置进行的操作。分以下方面：
  - 服务注册相关的配置信息，包括服务注册中心的地址、服务获取的间隔时间、可用区域等。
  - 服务实例相关的配置信息，包括服务实例的名称、IP地址、端口号、健康检查路径等。









