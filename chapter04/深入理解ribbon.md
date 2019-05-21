# 深入理解ribbon

## 负载均衡器

### AbstractLoadBalancer

```java
public abstract class AbstractLoadBalancer implements ILoadBalancer {
    // 关于服务实例分组的枚举
    public enum ServerGroup{
        ALL,	// 所有服务的实例
        STATUS_UP,	// 正常服务的实例
        STATUS_NOT_UP	// 停止服务的实例        
    }
        
    public Server chooseServer() {
        // key为null，表示忽略key的条件判断
    	return chooseServer(null);
    }
    
    // 根据分组的类型来获取不同的服务实例列表
    public abstract List<Server> getServerList(ServerGroup serverGroup);
    
    // 用来存储负载均衡器中各个服务实例当前的属性和统计信息
    public abstract LoadBalancerStats getLoadBalancerStats();    
}
```

###BaseLoadBalancer

```java
public class BaseLoadBalancer extends AbstractLoadBalancer implements
        PrimeConnections.PrimeConnectionListener, IClientConfigAware {
    // 定义并维护了两张存储服务实例Server对象的列表
    // 存储所有服务实例的清单
	@Monitor(name = PREFIX + "AllServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> allServerList = Collections
            .synchronizedList(new ArrayList<Server>());
    // 存储正常服务的实例清单
    @Monitor(name = PREFIX + "UpServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> upServerList = Collections
            .synchronizedList(new ArrayList<Server>());
    // 用来存储负载均衡器各服务实例属性和统计信息的对象
    protected LoadBalancerStats lbStats;
    // 检查服务实例是否正常服务的IPing对象
    protected IPing ping = null;
    // 定义了检查服务实例操作的执行策略对象IPingStragegy
    protected IPingStrategy pingStrategy = DEFAULT_PING_STRATEGY;
    // 定义的静态内部类SerialPingStragegy
    private final static SerialPingStrategy DEFAULT_PING_STRATEGY = new SerialPingStrategy();
    
    /**
     * 该策略当IPing的实现速度不理想，或是Server列表过大时，可能会影响系统性能
     * 可以通过实现IPingStrategy接口并重写pingServers(IPing ping, Server[] servers)扩展ping的执行策略
     */
	private static class SerialPingStrategy implements IPingStrategy {

        @Override
        public boolean[] pingServers(IPing ping, Server[] servers) {
            int numCandidates = servers.length;
            boolean[] results = new boolean[numCandidates];

            if (logger.isDebugEnabled()) {
                logger.debug("LoadBalancer:  PingTask executing ["
                             + numCandidates + "] servers configured");
            }

            // 采用线性遍历ping服务实例的方式实现检查
            for (int i = 0; i < numCandidates; i++) {
                results[i] = false; /* Default answer is DEAD. */
                try {
                    if (ping != null) {
                        results[i] = ping.isAlive(servers[i]);
                    }
                } catch (Throwable t) {
                    logger.error("Exception while pinging Server:"
                                 + servers[i], t);
                }
            }
            return results;
        }
    }
    
    // 定义了负载均衡处理规则的IRule对象，可知默认采用的是轮询规则
    protected IRule rule = DEFAULT_RULE;
    private final static IRule DEFAULT_RULE = new RoundRobinRule();
    
    // 挑选一个具体的服务实例
    // 通过chooseServer方法可知，负载均衡器实际将服务选择任务委托给了IRule对象的choose方法来实现
	public Server chooseServer(Object key) {
        if (counter == null) {
            counter = createCounter();
        }
        counter.increment();
        if (rule == null) {
            return null;
        } else {
            try {
                return rule.choose(key);
            } catch (Throwable t) {
                return null;
            }
        }
    }
    
    public BaseLoadBalancer() {
        this.name = DEFAULT_NAME;
        this.ping = null;
        setRule(DEFAULT_RULE);
        // 启动ping任务，直接启动一个用于定时检查Server是否健康的任务。默认的执行间隔为10秒
        setupPingTask();
        lbStats = new LoadBalancerStats(DEFAULT_NAME);
    }
    
    void setupPingTask() {
        if (canSkipPing()) {
            return;
        }
        if (lbTimer != null) {
            lbTimer.cancel();
        }
        lbTimer = new ShutdownEnabledTimer("NFLoadBalancer-PingTimer-" + name,
                true);
        // 默认的执行间隔为10秒
        lbTimer.schedule(new PingTask(), 0, pingIntervalSeconds * 1000);
        forceQuickPing();
    }
    
    // 标记某个服务实例暂停服务
    public void markServerDown(Server server) {
        if (server == null) {
            return;
        }

        if (!server.isAlive()) {
            return;
        }

        logger.error("LoadBalancer:  markServerDown called on ["
                + server.getId() + "]");
        server.setAlive(false);
        // forceQuickPing();
        notifyServerStatusChangeListener(singleton(server));
    }
    
    @Override
    // 获取可用的服务实例列表
    public List<Server> getReachableServers() {
        return Collections.unmodifiableList(upServerList);
    }
    
    @Override
    // 获取所有的服务实例列表
    public List<Server> getAllServers() {
        return Collections.unmodifiableList(allServerList);
    }
}
```

###DynamicServerListLoadBalancer

- `DynamicServerListLoadBalancer`类继承于`BaseLoadBalancer`类，是对基础负载均衡器的扩展。
- 实现了服务实例清单在运行期间动态更新的能力。
- 还具备对服务实例清单过滤的功能，也就是说可以通过过滤器来选择性地获取一批服务实例清单。

```java
public class DynamicServerListLoadBalancer<T extends Server> extends BaseLoadBalancer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicServerListLoadBalancer.class);

    boolean isSecure = false;
    boolean useTunnel = false;

    // to keep track of modification of server lists
    protected AtomicBoolean serverListUpdateInProgress = new AtomicBoolean(false);
	// 这个泛型T必须是Server的子类，即代表了一个具体的服务实例的扩展类
    volatile ServerList<T> serverListImpl;
	
    volatile ServerListFilter<T> filter;

    // serverListUpdater，更新后的服务实例清单
    protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            updateListOfServers();
        }
    };

    protected volatile ServerListUpdater serverListUpdater;

    public DynamicServerListLoadBalancer() {
        super();
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping,
                                         ServerList<T> serverList, ServerListFilter<T> filter,
                                         ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping);
        this.serverListImpl = serverList;
        this.filter = filter;
        this.serverListUpdater = serverListUpdater;
        if (filter instanceof AbstractServerListFilter) {
            ((AbstractServerListFilter) filter).setLoadBalancerStats(getLoadBalancerStats());
        }
        restOfInit(clientConfig);
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig) {
        initWithNiwsConfig(clientConfig);
    }
    
    @Override
    public void setServersList(List lsrv) {
        super.setServersList(lsrv);
        List<T> serverList = (List<T>) lsrv;
        Map<String, List<Server>> serversInZones = new HashMap<String, List<Server>>();
        for (Server server : serverList) {
            // make sure ServerStats is created to avoid creating them on hot
            // path
            getLoadBalancerStats().getSingleServerStat(server);
            String zone = server.getZone();
            if (zone != null) {
                zone = zone.toLowerCase();
                List<Server> servers = serversInZones.get(zone);
                if (servers == null) {
                    servers = new ArrayList<Server>();
                    serversInZones.put(zone, servers);
                }
                servers.add(server);
            }
        }
        setServerListForZones(serversInZones);
    }

    protected void setServerListForZones(
            Map<String, List<Server>> zoneServersMap) {
        LOGGER.debug("Setting server list for zones: {}", zoneServersMap);
        getLoadBalancerStats().updateZoneServerMapping(zoneServersMap);
    }

    @VisibleForTesting
    public void updateListOfServers() {
        List<T> servers = new ArrayList<T>();
        if (serverListImpl != null) {
            servers = serverListImpl.getUpdatedListOfServers();
            LOGGER.debug("List of Servers for {} obtained from Discovery client: {}",
                    getIdentifier(), servers);

            if (filter != null) {
                servers = filter.getFilteredListOfServers(servers);
                LOGGER.debug("Filtered List of Servers for {} obtained from Discovery client: {}",
                        getIdentifier(), servers);
            }
        }
        updateAllServerList(servers);
    }

    protected void updateAllServerList(List<T> ls) {
        // other threads might be doing this - in which case, we pass
        if (serverListUpdateInProgress.compareAndSet(false, true)) {
            for (T s : ls) {
                s.setAlive(true); 
            }
            setServersList(ls);
            super.forceQuickPing();
            serverListUpdateInProgress.set(false);
        }
    }

    //  .......
}
```

> **`ServerList`**

- `ServerList`接口如下

```java
public interface ServerList<T extends Server> {

    // 用于获取初始化的服务实例清单
    public List<T> getInitialListOfServers();

    // 用于获取更新的服务实例清单
    public List<T> getUpdatedListOfServers();   
}
```

- 可在`EurekaRibbonClientConfiguration`类中找到配置`serverList`实例的内容：

```java
@Bean
@ConditionalOnMissingBean
public ServerList<?> ribbonServerList(IClientConfig config) {
	DiscoveryEnabledNIWSServerList discoveryServerList = 
        	new DiscoveryEnabledNIWSServerList(config);
	DomainExtractingServerList serverList = new DomainExtractingServerList(
			discoveryServerList, config, this.approximateZoneFromHostname);
	return serverList;
}
```

- `DomainExtractingServerList`源码如下，可以看出对`getInitialListOfServers`和`getUpdatedListOfServers`的具体实现，其实委托给了内部定义的`ServerList list`对象。
- 该对象通过构造函数传入的`DiscoveryEnabledNIWSServerList`实现。

```java
public class DomainExtractingServerList implements ServerList<DiscoveryEnabledServer> {

    // 内部定义了一个ServerList
	private ServerList<DiscoveryEnabledServer> list;
    
    public DomainExtractingServerList(ServerList<DiscoveryEnabledServer> list,
			IClientConfig clientConfig, boolean approximateZoneFromHostname) {
		this.list = list;
		this.clientConfig = clientConfig;
		this.approximateZoneFromHostname = approximateZoneFromHostname;
	}

	@Override
	public List<DiscoveryEnabledServer> getInitialListOfServers() {
		List<DiscoveryEnabledServer> servers = setZones(this.list
				.getInitialListOfServers());
		return servers;
	}

	@Override
	public List<DiscoveryEnabledServer> getUpdatedListOfServers() {
		List<DiscoveryEnabledServer> servers = setZones(this.list
				.getUpdatedListOfServers());
		return servers;
	}
	
    //  该方法主要将DiscoveryEnabledServer对象集合转换成DmainExtractingServer对象的集合
	private List<DiscoveryEnabledServer> setZones(List<DiscoveryEnabledServer> servers) {
		List<DiscoveryEnabledServer> result = new ArrayList<>();
		boolean isSecure = this.clientConfig.getPropertyAsBoolean(
				CommonClientConfigKey.IsSecure, Boolean.TRUE);
		boolean shouldUseIpAddr = this.clientConfig.getPropertyAsBoolean(
				CommonClientConfigKey.UseIPAddrForServer, Boolean.FALSE);
		for (DiscoveryEnabledServer server : servers) {
			result.add(new DomainExtractingServer(server, isSecure, shouldUseIpAddr,
					this.approximateZoneFromHostname));
		}
		return result;
	}
}
```

- `DiscoveryEnabledNIWSServerList`类中这两个方法的实现都是通过该类中的一个私有函数`obtainServerViaDiscovery`通过服务发现机制来实现服务实例的获取。

```java
@Override
public List<DiscoveryEnabledServer> getInitialListOfServers(){
    return obtainServersViaDiscovery();
}

@Override
public List<DiscoveryEnabledServer> getUpdatedListOfServers(){
    return obtainServersViaDiscovery();
}
```

- `obtainServerViaDiscovery`的实现逻辑如下

```java
private List<DiscoveryEnabledServer> obtainServersViaDiscovery() {
    List<DiscoveryEnabledServer> serverList = new ArrayList<DiscoveryEnabledServer>();
    if (eurekaClientProvider == null || eurekaClientProvider.get() == null) {
        logger.warn("EurekaClient has not been initialized yet, returning an empty list");
        return new ArrayList<DiscoveryEnabledServer>();
    }
    EurekaClient eurekaClient = eurekaClientProvider.get();
    if (vipAddresses != null){
        for (String vipAddress : vipAddresses.split(",")) {
            // 通过EurekaClient从服务注册中心获取具体的服务实例InstanceInfo列表，
            // 这里的vipAddress可理解为服务名，如user-service
            List<InstanceInfo> listOfInstanceInfo = eurekaClient.getInstancesByVipAddress(vipAddress, isSecure, targetRegion);
            // 遍历服务实例列表
            for (InstanceInfo ii : listOfInstanceInfo) {
                // 判断状态是否为UP，即是否是正常服务
                if (ii.getStatus().equals(InstanceStatus.UP)) {
                    if(shouldUseOverridePort){
                        if(logger.isDebugEnabled()){
                            logger.debug("Overriding port on client name: " + clientName + " to " + overridePort);
                        }
                        // copy is necessary since the InstanceInfo builder just uses the original reference,
                        // and we don't want to corrupt the global eureka copy of the object which may be
                        // used by other clients in our system
                        InstanceInfo copy = new InstanceInfo(ii);
                        if(isSecure) {
                            ii = new InstanceInfo.Builder(copy).
                                setSecurePort(overridePort).build();
                        } else {
                            ii = new InstanceInfo.Builder(copy).setPort(overridePort).build();
                        }
                    }
                    // 将正常服务转换成DiscoveryEnablerServer类对象
                    DiscoveryEnabledServer des = new DiscoveryEnabledServer(
                        ii, isSecure, shouldUseIpAddr);
                    des.setZone(DiscoveryClient.getZone(ii));
                    serverList.add(des);
                }
            }
            if (serverList.size() > 0 && prioritizeVipAddressBasedServers){
                break; // if the current vipAddress has servers, we dont use subsequent vipAddress based servers
            }
        }
    }
    // 返回这个服务列表集合
    return serverList;
}
```

- 然后调用`DomainExtractingServerList`类中的`setZones`方法进行对象映射转换，转换成内部定义的`DomainExtractingServer`对象，该类是`DiscoveryEnabledServer`的子类。在该对象的构造函数中将为服务实例对象设置一些必要的属性，如`id、zone、isAliveFalg、readyToServer`等信息。

> **`ServerListUpdater`**

```java
protected final ServerListUpdater.UpdateAction updateAction = 
    		new ServerListUpdater.UpdateAction() {
    			@Override
    			// 实现对serverList的更新操作
    			public void doUpdate() {
        			updateListOfServers();
    			}
			};

protected volatile ServerListUpdater serverListUpdater;
```
- `ServerListUpdater`接口定义如下。该接口有两个具体实现类：
  - `PollingServerListUpdater`：动态服务列表更新的默认策略，通过定时任务的方式进行服务列表的更新。
  - `EurekaNotificationServerListUpdater`：需要利用`Eureka`的事件监听器来驱动服务列表的更新操作。

```java
public interface ServerListUpdater {

    public interface UpdateAction {
        void doUpdate();
    }
	
    // 启动服务更新器，传入的updateAction对象为更新操作的具体实现
    void start(UpdateAction updateAction);

    // 停止服务更新器
    void stop();

    // 获取最近的更新时间戳
    String getLastUpdate();

    // 获取上一次更新到现在的时间间隔，单位为毫秒
    long getDurationSinceLastUpdateMs();

    // 获取错过的更新周期数
    int getNumberMissedCycles();

    // 获取核心线程数
    int getCoreThreads();
}
```

- `PollingServerListUpdater`部分源码如下：

```java
public class PollingServerListUpdater implements ServerListUpdater {

    private static final Logger logger = LoggerFactory.getLogger(PollingServerListUpdater.class);

    private static long LISTOFSERVERS_CACHE_UPDATE_DELAY = 1000; // msecs;
    private static int LISTOFSERVERS_CACHE_REPEAT_INTERVAL = 30 * 1000; // msecs;
    
    @Override
	public synchronized void start(final UpdateAction updateAction) {
    	if (isActive.compareAndSet(false, true)) {
        	final Runnable wrapperRunnable = new Runnable() {
            	@Override
            	public void run() {
                	if (!isActive.get()) {
                    	if (scheduledFuture != null) {
                        	scheduledFuture.cancel(true);
                    	}
                        return;
                    }
                    try {
                        updateAction.doUpdate();
                        lastUpdated = System.currentTimeMillis();
                    } catch (Exception e) {
                        logger.warn("Failed one update cycle", e);
                    }
                }
            };
            // 更新服务实例在初始化1秒后开始执行，并以30秒为周期重复执行
            scheduledFuture = getRefreshExecutor().scheduleWithFixedDelay(
                    wrapperRunnable,
                    initialDelayMs,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS
            );
        } else {
            logger.info("Already active, no-op");
        }
    }
}
```

> **`ServerListFilter`**

- 在`DynamicServerListLoadBalancer`中，它的实际实现委托给了`updateListOfServers`函数执行。

```java
@VisibleForTesting
public void updateListOfServers() {
    List<T> servers = new ArrayList<T>();
    if (serverListImpl != null) {
        servers = serverListImpl.getUpdatedListOfServers();
        LOGGER.debug("List of Servers for {} obtained from Discovery client: {}",
                getIdentifier(), servers);
		// 此前从未出现的新对象filter
        if (filter != null) {
            servers = filter.getFilteredListOfServers(servers);
            LOGGER.debug("Filtered List of Servers for {} obtained from Discovery client: {}",
                    getIdentifier(), servers);
        }
    }
    updateAllServerList(servers);
}
```

- `filter`，`ServerListFilter`接口实现类的对象，主要用于对服务实例列表的过滤，通过传入的服务实例清单，根据一些规则返回过滤后的服务实例清单。该接口实现类如下：

  - `AbstractServerListFilter`：一个抽象过滤器，定义过滤时需要的一个重要依据对象`LoadBalancerStates`。

  ```java
  public abstract class AbstractServerListFilter<T extends Server> 
  											implements ServerListFilter<T> {
  
      private volatile LoadBalancerStats stats;
      
      public void setLoadBalancerStats(LoadBalancerStats stats) {
          this.stats = stats;
      }
      
      public LoadBalancerStats getLoadBalancerStats() {
          return stats;
      }
  }
  ```

  - `ZoneAffinityServerListFilter`：基于**区域感知`(Zone Affinity)`**的方式实现服务实例的过滤。就是说，可以根据提供服务的实例所处的区域与消费者自身所处的区域进行比较，过滤掉那些不是同处一个区域的实例。

    ```java
    @Override
    public List<T> getFilteredListOfServers(List<T> servers) {
        if (zone != null && (zoneAffinity || zoneExclusive) && 
            							servers !=null && servers.size() > 0){
            // 过滤服务实例列表，判断依据比较服务实例与消费者的Zone比较
            List<T> filteredServers = Lists.newArrayList(Iterables.filter(
                    servers, this.zoneAffinityPredicate.getServerOnlyPredicate()));
            // 调用"区域感知"功能，若有一个条件符合，就不启用该功能过滤服务实例清单
            if (shouldEnableZoneAffinity(filteredServers)) {
                return filteredServers;
            } else if (zoneAffinity) {
                overrideCounter.increment();
            }
        }
        return servers;
    }
    ```

    - `blackOutServerPercentage:`故障实例百分比`(断路器断开数/实例数量) >= 0.8`
    - `activeRequestsPerServer:`实例平均负载 `>= 0.6`
    - `availableServers:`可用实例数`(实例数量 - 断路器断开数) < 2`

  ```java
  private boolean shouldEnableZoneAffinity(List<T> filtered) {    
      if (!zoneAffinity && !zoneExclusive) {
          return false;
      }
      if (zoneExclusive) {
          return true;
      }
      LoadBalancerStats stats = getLoadBalancerStats();
      if (stats == null) {
          return zoneAffinity;
      } else {
          logger.debug("Determining if zone affinity should be enabled with given server list: {}", filtered);
          ZoneSnapshot snapshot = stats.getZoneSnapshot(filtered);
          double loadPerServer = snapshot.getLoadPerServer();
          int instanceCount = snapshot.getInstanceCount();            
          int circuitBreakerTrippedCount = snapshot.getCircuitTrippedCount();
          // 若有一个条件符合，就不启用该功能过滤服务实例清单
          if (((double) circuitBreakerTrippedCount) / instanceCount >= blackOutServerPercentageThreshold.get() 
                  || loadPerServer >= activeReqeustsPerServerThreshold.get()
                  || (instanceCount - circuitBreakerTrippedCount) < availableServersThreshold.get()) {
              logger.debug("zoneAffinity is overriden. blackOutServerPercentage: {}, activeReqeustsPerServer: {}, availableServers: {}", 
                      new Object[] {(double) circuitBreakerTrippedCount / instanceCount,  loadPerServer, instanceCount - circuitBreakerTrippedCount})
              return false;
          } else {
              return true;
          }
          
      }
  }
  ```

  - `DefaultNIWSServerListFilter:`完全继承自`ZoneAffinityServerListFilter`，
    - 默认的`NIWS(Netflix Internal Web Service)`过滤器。
  - `ServerListSubsetFilter:`也继承自`ZoneAffinityServerListFilter`，非常适用于拥有大规模服务器集群的系统。
  - `ZonePreferenceServerListFilter:`也继承自`ZoneAffinityServerListFilter`，`Spring Cloud`整合时新增的过滤器。它实现了通过配置或者`Eureka`实例元数据的所属区域`(Zone)`来过滤出同区域的服务实例。

  ```java
  @Override
  public List<Server> getFilteredListOfServers(List<Server> servers) {
      // 通过父类的过滤器来获取"区域感知"的服务实例列表
  	List<Server> output = super.getFilteredListOfServers(servers);
      // 遍历这个结果
  	if (this.zone != null && output.size() == servers.size()) {
  		List<Server> local = new ArrayList<Server>();
  		for (Server server : output) {
               // 根据消费者配置预设的区域Zone来进行过滤
  			if (this.zone.equalsIgnoreCase(server.getZone())) {
  				local.add(server);
  			}
  		}
           // 如果过滤不为空，将消费者配置的Zone过滤后的结果返回
  		if (!local.isEmpty()) {
  			return local;
  		}
  	}
      // 如果过滤后结果为空，则直接将父类过滤的结果返回
  	return output;
  }
  ```

###ZoneAwareLoadBalancer

- 该负载均衡器是对`DynamicServerListLoadBalancer`的一个扩展。在`DynamicServerListLoadBalancer`中并没有重写选择具体服务实例的`chooseServer`函数，所以它使用的是`BaseLoadBalancer`父类中的`chooseServer`方法，以线性轮询的方式来选择调用的服务实例。没有**跨区域**的概念。
- 在`DynamicServerListLoadBalancer`中，有如下两个方法：

```java
@Override
public void setServersList(List lsrv) {
    super.setServersList(lsrv);
    List<T> serverList = (List<T>) lsrv;
    Map<String, List<Server>> serversInZones = new HashMap<String, List<Server>>();
    for (Server server : serverList) {
        // make sure ServerStats is created to avoid creating them on hot
        // path
        getLoadBalancerStats().getSingleServerStat(server);
        String zone = server.getZone();
        if (zone != null) {
            zone = zone.toLowerCase();
            List<Server> servers = serversInZones.get(zone);
            if (servers == null) {
                servers = new ArrayList<Server>();
                serversInZones.put(zone, servers);
            }
            servers.add(server);
        }
    }
    setServerListForZones(serversInZones);
}

protected void setServerListForZones(
    Map<String, List<Server>> zoneServersMap) {
    LOGGER.debug("Setting server list for zones: {}", zoneServersMap);
    getLoadBalancerStats().updateZoneServerMapping(zoneServersMap);
}
```

- 在`ZoneAwareLoadBalancer`中并没有重写`setServerList`方法，但是重写了`setServerListForZones`方法，如下：

```java
@Override
protected void setServerListForZones(Map<String, List<Server>> zoneServersMap) {
    // 先执行父类的setServerListForZones方法
    super.setServerListForZones(zoneServersMap);
    // balance，用来存储每一个Zone区域对应的负载均衡器
    if (balancers == null) {
        balancers = new ConcurrentHashMap<String, BaseLoadBalancer>();
    }
    
    // 第一个循环，完成具体的负载均衡器的创建吗，getLoadBalancer函数来完成
    for (Map.Entry<String, List<Server>> entry: zoneServersMap.entrySet()) {
    	String zone = entry.getKey().toLowerCase();
        getLoadBalancer(zone).setServersList(entry.getValue());
    }
   
    // 第二个循环，对Zone区域中实例清单的检查，看是否有Zone区域下已经没有实例了。
    // 是的话，就将balancers中对应的Zone区域的实例列表清空，该操作是为了后续选择节点时，防止过时的Zone
    // 区域统计信息干扰到具体的选择算法
    for (Map.Entry<String, BaseLoadBalancer> existingLBEntry: balancers.entrySet()) {
        if (!zoneServersMap.keySet().contains(existingLBEntry.getKey())) {
            existingLBEntry.getValue().setServersList(Collections.emptyList());
        }
    }
}    
```

- 接下来，查看重写的`chooseServer`方法

```java
@Override
public Server chooseServer(Object key) {
    // 当所属Zone区域的个数不大于1，依然使用父类的实现
    if (!ENABLED.get() || getLoadBalancerStats().getAvailableZones().size() <= 1) {
        logger.debug("Zone aware logic disabled or there is only one zone");
        return super.chooseServer(key);
    }
    Server server = null;
    try {
        LoadBalancerStats lbStats = getLoadBalancerStats();
        // 为当前负载均衡器所有的Zone区域分表创建快照，保存在map集合中
        Map<String, ZoneSnapshot> zoneSnapshot = ZoneAvoidanceRule.createSnapshot(lbStats);
        logger.debug("Zone snapshots: {}", zoneSnapshot);
        if (triggeringLoad == null) {
            triggeringLoad = DynamicPropertyFactory.getInstance().getDoubleProperty(
                    "ZoneAwareNIWSDiscoveryLoadBalancer." + this.getName() + ".triggeringLoadPerServerThreshold", 0.2d);
        }
        if (triggeringBlackoutPercentage == null) {
            triggeringBlackoutPercentage = DynamicPropertyFactory.getInstance().getDoubleProperty(
                    "ZoneAwareNIWSDiscoveryLoadBalancer." + this.getName() + ".avoidZoneWithBlackoutPercetage", 0.99999d);
        }
        // 获取可用的Zone区域集合，该函数中会通过Zone区域快照中的统计数据来实现可用区的挑选
        Set<String> availableZones = ZoneAvoidanceRule.getAvailableZones(zoneSnapshot, triggeringLoad.get(), triggeringBlackoutPercentage.get());
        logger.debug("Available zones: {}", availableZones);
        if (availableZones != null &&  availableZones.size() < zoneSnapshot.keySet().size()) {
            String zone = ZoneAvoidanceRule.randomChooseZone(zoneSnapshot, availableZones);
            logger.debug("Zone chosen: {}", zone);
            // 获得可用Zone区域不为空
            if (zone != null) {
                // 随机选择一个Zone区域
                BaseLoadBalancer zoneLoadBalancer = getLoadBalancer(zone);
                // 调用chooseServer来选择具体的服务实例。
                server = zoneLoadBalancer.chooseServer(key);
            }
        }
    } catch (Throwable e) {
        logger.error("Unexpected exception when choosing server using zone aware logic", e);
    }
    if (server != null) {
        return server;
    } else {
        logger.debug("Zone avoidance logic is not invoked.");
        return super.chooseServer(key);
    }
}
```

## 负载均衡策略

### AbstractLoadBalancerRule

- 获取到一些负载均衡器中维护的信息来作为分配依据，并以此来设计一些算法来实现针对特定场景的高效策略。

```java
public abstract class AbstractLoadBalancerRule implements IRule, IClientConfigAware {

    private ILoadBalancer lb;
        
    @Override
    public void setLoadBalancer(ILoadBalancer lb){
        this.lb = lb;
    }
    
    @Override
    public ILoadBalancer getLoadBalancer(){
        return lb;
    }      
}
```

### RandomRule

- 实现了从服务实例清单中随机选择一个服务实例的功能。

```java
public class RandomRule extends AbstractLoadBalancerRule {
    Random rand;

    public RandomRule() {
        rand = new Random();
    }
    
    @Override
	public Server choose(Object key) {
		return choose(getLoadBalancer(), key);
	}

    /**
     * Randomly choose from all living servers
     */
    public Server choose(ILoadBalancer lb, Object key) {
        // 如果负载均衡器为null，则直接返回null
        if (lb == null) {
            return null;
        }
        Server server = null;

        while (server == null) {
            // 如果此时线程被打断，则返回null
            if (Thread.interrupted()) {
                return null;
            }
            List<Server> upList = lb.getReachableServers();
            List<Server> allList = lb.getAllServers();

            // 获取所有一直服务的个数
            int serverCount = allList.size();
            if (serverCount == 0) {
                return null;// 服务个数为0，则返回null
            }
		   // 获取一个随机数，范围从0~serverCount
            int index = rand.nextInt(serverCount);
            // 获取服务
            server = upList.get(index);
		   // 如果server为null，继续循环
            if (server == null) {
                Thread.yield();
                continue;
            }
		   // 如果服务可用，返回server
            if (server.isAlive()) {
                return (server);
            }
			
            // 实际中不应该发生，但必须是
            server = null;
            Thread.yield();
        }
        return server;
    }

	@Override
	public void initWithNiwsConfig(IClientConfig clientConfig) {
		// TODO Auto-generated method stub
	}
}
```

### RoundRobinRule

- 实现了按照线性轮询的方式一次选择每个服务实例的功能。

```java
public class RoundRobinRule extends AbstractLoadBalancerRule {

    private AtomicInteger nextServerCyclicCounter;
    private static final boolean AVAILABLE_ONLY_SERVERS = true;
    private static final boolean ALL_SERVERS = false;

    private static Logger log = LoggerFactory.getLogger(RoundRobinRule.class);

    public RoundRobinRule() {
        nextServerCyclicCounter = new AtomicInteger(0);
    }

    public RoundRobinRule(ILoadBalancer lb) {
        this();
        setLoadBalancer(lb);
    }

    public Server choose(ILoadBalancer lb, Object key) {
        if (lb == null) {
            log.warn("no load balancer");
            return null;
        }

        Server server = null;
        // 记录获取server的次数
        int count = 0;
        // 如果一直选择不到server，且次数超过10次
        while (server == null && count++ < 10) {
            List<Server> reachableServers = lb.getReachableServers();
            List<Server> allServers = lb.getAllServers();
            int upCount = reachableServers.size();
            int serverCount = allServers.size();
		
            // 如果可用和所有服务数都为0
            if ((upCount == 0) || (serverCount == 0)) {
                log.warn("No up servers available from load balancer: " + lb);
                return null;
            }

            int nextServerIndex = incrementAndGetModulo(serverCount);
            server = allServers.get(nextServerIndex);

            if (server == null) {
                /* Transient. */
                Thread.yield();
                continue;
            }

            if (server.isAlive() && (server.isReadyToServe())) {
                return (server);
            }

            // Next.
            server = null;
        }

        if (count >= 10) {
            log.warn("No available alive servers after 10 tries from load balancer: " + lb);
        }
        return server;
    }

    private int incrementAndGetModulo(int modulo) {
        for (;;) {
            int current = nextServerCyclicCounter.get();
            int next = (current + 1) % modulo;
            if (nextServerCyclicCounter.compareAndSet(current, next))
                return next;
        }
    }

    @Override
    public Server choose(Object key) {
        return choose(getLoadBalancer(), key);
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
    }
}
```

### RetryRule

- 实现了一个具备重试机制的实力选择功能。

```java
public class RetryRule extends AbstractLoadBalancerRule {
    // 内部定义一个IRule对象，默认的是线性轮询规则
	IRule subRule = new RoundRobinRule();
	long maxRetryMillis = 500;

	public RetryRule() {
	}

	public RetryRule(IRule subRule) {
	   this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public RetryRule(IRule subRule, long maxRetryMillis) {
	   this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	   this.maxRetryMillis = (maxRetryMillis > 0) ? maxRetryMillis : 500;
	}

	public void setRule(IRule subRule) {
	   this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public IRule getRule() {
	   return subRule;
	}

	public void setMaxRetryMillis(long maxRetryMillis) {
	   if (maxRetryMillis > 0) {
	      this.maxRetryMillis = maxRetryMillis;
	   } else {
	      this.maxRetryMillis = 500;
	   }
	}

	public long getMaxRetryMillis() {
	   return maxRetryMillis;
	}

	@Override
	public void setLoadBalancer(ILoadBalancer lb) {       
	   super.setLoadBalancer(lb);
	   subRule.setLoadBalancer(lb);
	}

	public Server choose(ILoadBalancer lb, Object key) {
	   long requestTime = System.currentTimeMillis();
	   long deadline = requestTime + maxRetryMillis;

	   Server answer = null;
	   // 通过给定夏宁
	   answer = subRule.choose(key);
	   
        // 如果server不存在或者服务器不可用并且系统当前时间小于尝试结束的时间阈值
	   if (((answer == null) || (!answer.isAlive())) && (System.currentTimeMillis() < deadline)) {
		  // 获取定时任务执行的延迟时间改时间如果为0，则执行定时任务
	      InterruptTask task = new InterruptTask(deadline - System.currentTimeMillis());
		  // 线程没有被终止前，都进入此循环
	      while (!Thread.interrupted()) {
	         answer = subRule.choose(key);
			
	         if (((answer == null) || (!answer.isAlive()))
	               && (System.currentTimeMillis() < deadline)) {
	            /* pause and retry hoping it's transient */
	            Thread.yield();
	         } else {
	            break;
	         }
	      }

	      task.cancel();
	   }

	   if ((answer == null) || (!answer.isAlive())) {
	      return null;
	   } else {
	      return answer;
	   }
	}

	@Override
	public Server choose(Object key) {
	   return choose(getLoadBalancer(), key);
	}

	@Override
	public void initWithNiwsConfig(IClientConfig clientConfig) {
	}
}	
```

### WeightedResponseTimeRule

- 根据实例的运行情况计算权重，并根据权重来挑选实例，已达到更优的分配效果。
- 启动一个定时任务，为每个服务实例计算权重，默认`30`秒执行一次。
- 权重计算分为两个步骤：
  - 累加所有实例的平均响应时间，得到总平均响应时间`totalResponseTime`。
  - 为每个实例计算权重。计算规则`weightSoFar + totalResponseTime - 实例平均响应时间`，其中`weightSoFar`初始化值为零，并且每计算好一个权重需要累加给`weightSoFar`，共下一个实例权重计算时使用。
- 实际上来说，就是平均响应时间越短，权重区间的宽度就越大，则被选中的概率就越高。

```java
public void maintainWeights() {
    ILoadBalancer lb = getLoadBalancer();
    if (lb == null) {
        return;
    }
    if (serverWeightAssignmentInProgress.get()) {
        return; // Ping in progress - nothing to do
    } else {
        serverWeightAssignmentInProgress.set(true);
    }
    try {
        logger.info("Weight adjusting job started");
        AbstractLoadBalancer nlb = (AbstractLoadBalancer) lb;
        LoadBalancerStats stats = nlb.getLoadBalancerStats();
        if (stats == null) {
            return;
        }
        double totalResponseTime = 0;
        // find maximal 95% response time
        for (Server server : nlb.getAllServers()) {
            // this will automatically load the stats if not in cache
            ServerStats ss = stats.getSingleServerStat(server);
            totalResponseTime += ss.getResponseTimeAvg();
        }
		// 初始化值为0
        Double weightSoFar = 0.0;
        
        // create new list and hot swap the reference
        List<Double> finalWeights = new ArrayList<Double>();
        for (Server server : nlb.getAllServers()) {
            ServerStats ss = stats.getSingleServerStat(server);
            // 权重 = 总响应时间  - 实例平均响应时间
            double weight = totalResponseTime - ss.getResponseTimeAvg();
            // 权重累加给weightSoFar
            weightSoFar += weight;
            finalWeights.add(weightSoFar);   
        }
        setWeights(finalWeights);
    } catch (Throwable t) {
        logger.error("Exception while dynamically calculating server weights", t);
    } finally {
        serverWeightAssignmentInProgress.set(false);
    }
}
```

```java
@Override
public Server choose(ILoadBalancer lb, Object key) {
    if (lb == null) {
        return null;
    }
    Server server = null;
    while (server == null) {
        // get hold of the current reference in case it is changed from the other thread
        List<Double> currentWeights = accumulatedWeights;
        if (Thread.interrupted()) {
            return null;
        }
        List<Server> allList = lb.getAllServers();
        int serverCount = allList.size();
        if (serverCount == 0) {
            return null;
        }
        int serverIndex = 0;
        // 获取最后一个实例权重
        double maxTotalWeight = currentWeights.size() == 0 ? 0 : currentWeights.get(currentWeights.size() - 1); 
        // 如果最后一个实例权重值小于0.001，则调用父类的线性轮询策略
        if (maxTotalWeight < 0.001d) {
            server =  super.choose(getLoadBalancer(), key);
            if(server == null) {
                return server;
            }
        } else {
            // 获取一个随机数，界于0~maxTotalWeight之间。
            double randomWeight = random.nextDouble() * maxTotalWeight;
            // pick the server index based on the randomIndex
            int n = 0;
            // 遍历权重清单，如果权重≥随机值，则选择这个实例
            for (Double d : currentWeights) {
                if (d >= randomWeight) {
                    serverIndex = n;
                    break;
                } else {
                    n++;
                }
            }
            server = allList.get(serverIndex);
        }
        if (server == null) {
            /* Transient. */
            Thread.yield();
            continue;
        }
        if (server.isAlive()) {
            return (server);
        }
        // Next.
        server = null;
    }
    return server;
}
```

### ClientConfigEnabledRoundRobinRule

- 一般不直接使用它。
- 在它的内部定义了一个`RoundRobinRule`策略，而`choose`函数的实现则是`RoundRobinRule`的线性轮询机制。
- 通过实现该类的子类，重写`choose`方法，就可以做一些高级的策略。如果该策略无法实施，那么就调用父类的实现作为备选策略。

#### BestAvailableRule

- 通过遍历负载均衡器中维护的所有服务实例，过滤掉故障的实例，并找出发送请求数最小的一个。所以，该策略的特性是可以选出最空闲的实例。
- 该类的`choose`函数的核心算法依据的是统计对象`localBalancerState`，当其为空时该策略就无法执行。此时就会采用父类的轮询策略备用。

```java
public class BestAvailableRule extends ClientConfigEnabledRoundRobinRule {

    private LoadBalancerStats loadBalancerStats;
    
    @Override
    public Server choose(Object key) {
        if (loadBalancerStats == null) {
            return super.choose(key);
        }
        List<Server> serverList = getLoadBalancer().getAllServers();
        int minimalConcurrentConnections = Integer.MAX_VALUE;
        long currentTime = System.currentTimeMillis();
        Server chosen = null;
        for (Server server: serverList) {
            ServerStats serverStats = loadBalancerStats.getSingleServerStat(server);
            if (!serverStats.isCircuitBreakerTripped(currentTime)) {
                int concurrentConnections = serverStats.getActiveRequestsCount(currentTime);
                if (concurrentConnections < minimalConcurrentConnections) {
                    minimalConcurrentConnections = concurrentConnections;
                    chosen = server;
                }
            }
        }
        if (chosen == null) {
            return super.choose(key);
        } else {
            return chosen;
        }
    }

    @Override
    public void setLoadBalancer(ILoadBalancer lb) {
        super.setLoadBalancer(lb);
        if (lb instanceof AbstractLoadBalancer) {
            loadBalancerStats = ((AbstractLoadBalancer) lb).getLoadBalancerStats();            
        }
    }
}
```

#### PredicateBaseRule

- 这是一个抽象策略，基于`Predicate`实现的策略。
- 先通过子类中实现的`Predicate`逻辑来过滤一部分服务，然后再以线性轮询的方式从过滤后的实例清单中选出一个。
- 遵循的是**先过滤清单，再轮询选择**。

#### AvailabilityFilteringRule

- 继承自抽象策略`PredicateBaseRule`。也遵循**先过滤清单， 再轮询选择**。它过滤判断的依据如下：
  - 是否故障，即断路器是否生效已断开。
  - 实例的并发请求数大于阈值，默认为`2 ^ 32 -1` 。
- 满足一个就返回`false`。

#### ZoneAvoidanceRule

- 这是一个组合过滤条件。在其构造函数中，以`ZoneAvoidancePredicate`为主过滤条件，`AvailabilityPredicate`为次过滤条件。
- 它完全遵循**先过滤清单，再轮询选择**。
- 每次过滤之后（主从都过滤完算一次），都需要判断如下两个条件是否成立，若有一个符合，则不再进行过滤。将当前结果返回供线性轮询算法选择。
  - `过滤后的实例总数 ≥ 最小过滤实例数(默认为1)`
  - `过滤后的实例比例 > 最小过滤百分比(默认为0)`

