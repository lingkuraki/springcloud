# Spring Cloud Ribbon笔记总结01

- `Spring Cloud Ribbon`只是一个工具类框架，虽然不需要独立部署，但是几乎存在于每一个`Spring Cloud`构建的微服务和基础设施中。而且，`ribbon`实现的是**客户端**的负载均衡。

## 客户端负载均衡

- **负载均衡是对系统的高可用，网络压力的环节和处理能力扩容的重要手段之一**。分为硬件负载均衡和软件负载均衡。
  - 硬件负载均衡：通过服务器节点之间安装专门用于负载均衡的设备，如`F5`等。
  - 软件负载均衡：通过在服务器上安装一些具有负载功能或模块的软件来完成请求分发工作，如`Nginx`等。
- 无论是哪种负载均衡方式，它们都会**维护一个下挂可用的服务清单，通过心跳检测来剔除故障的服务端节点以保证清单中都是可以正常访问的服务端节点**。
- 而客户端负载均衡和服务端负载均衡最大的区别就在于这个服务清单所存在的位置。该清单来自于服务注册中心，并且客户端需要心跳去维护清单的健康性。

- 通过`Spring Cloud Ribbo`，使用客户端负载均衡调用只需要两步：
  - 服务提供者只需要启动多个服务实例并注册到一个注册中心或是多个相关的服务注册中心。
  - 服务消费者直接通过调用被`@LoadBalanced`注解修饰过的`RestTemplate`来实现而向服务的接口调用。

## RestTemplate详解

- `RestTemplate`，简单地说就是发送`rest`风格的`http`请求。分为`GET、POST、PUT、DELETE`四种请求方式。分为有以下两种方法名：
  - `×××ForEntity`方法，返回的是`ResponseEntity`对象。
  - `×××ForObjetc`方法，直接返回实体对象。

## 源码分析

- 先从`@LoadBalanced`注解下手，以下是`LoadBalancerClient`接口源码：

```java
public interface LoadBalancerClient {
	// 根据传入的服务id，从负载均衡器中挑选出一个对应服务的实例
	ServiceInstance choose(String serviceId);
	// 从负载均衡器中挑选出的服务实例来执行请求内容
	<T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException;
	// 为系统构建一个合适的host:port形式的URI
	URI reconstructURI(ServiceInstance instance, URI original);
}
```

- `LoadBalancerAutoConfiguration`自动化配置类源码如下。可知，`ribbon`实现负载均衡自动化配置需满足以下条件：
  - `@ConditionalOnClass(RestTemplate.class)`：`RestTemplate`类必须存在当前工程的环境中；
  - `@ConditionalOnBean(LoadBalancerClient.class)`：在`Spring`的`Bean`工程中必须有`LoadBalancerClient`的实现`Bean`。

```java
@Configuration
@ConditionalOnClass(RestTemplate.class)
@ConditionalOnBean(LoadBalancerClient.class)
public class LoadBalancerAutoConfiguration {

   @LoadBalanced
   @Autowired(required = false)
   private List<RestTemplate> restTemplates = Collections.emptyList();

   @Bean
   // 维护一个被@LoadBalanced注解修饰的RestTemplate对象列表，并进行初始化。
   public SmartInitializingSingleton loadBalancedRestTemplateInitializer(
         final List<RestTemplateCustomizer> customizers) {
      return new SmartInitializingSingleton() {
         @Override
         public void afterSingletonsInstantiated() {
            for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
               for (RestTemplateCustomizer customizer : customizers) {
                   // 为该实例添加LoadBalancerInterceptor拦截器
                  customizer.customize(restTemplate);
               }
            }
         }
      };
   }

   @Bean
   @ConditionalOnMissingBean
    // 用于给RestTemplate增加LoadBalancerInterceptor拦截器
   public RestTemplateCustomizer restTemplateCustomizer(
         final LoadBalancerInterceptor loadBalancerInterceptor) {
      return new RestTemplateCustomizer() {
         @Override
         public void customize(RestTemplate restTemplate) {
            List<ClientHttpRequestInterceptor> list = new ArrayList<>(
                  restTemplate.getInterceptors());
            list.add(loadBalancerInterceptor);
            restTemplate.setInterceptors(list);
         }
      };
   }

   @Bean
   // 用于实现给客户端发起请求时进行拦截，以实现客户端负载均衡
   public LoadBalancerInterceptor ribbonInterceptor(LoadBalancerClient loadBalancerClient) {
      return new LoadBalancerInterceptor(loadBalancerClient);
   }
}
```

- 下面是`LoadBalancerInterceptor`拦截器源码：

```java
public class LoadBalancerInterceptor implements ClientHttpRequestInterceptor {

   private LoadBalancerClient loadBalancer;
   // 构造方法注入LoadBalancerClient实例对象
   public LoadBalancerInterceptor(LoadBalancerClient loadBalancer) {
      this.loadBalancer = loadBalancer;
   }

   @Override
   public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
         final ClientHttpRequestExecution execution) throws IOException {
      final URI originalUri = request.getURI();
      // 从uri中拿到服务名，restTemplate中服务名作为host。
      String serviceName = originalUri.getHost();
      // 调用execute函数去根据服务名来选择实例并发起实际请求
      return this.loadBalancer.execute(serviceName, new LoadBalancerRequest<ClientHttpResponse>() {

               @Override
               public ClientHttpResponse apply(final ServiceInstance instance) throws Exception {
                  HttpRequest serviceRequest = new ServiceRequestWrapper(request, instance);
                  return execution.execute(serviceRequest, body);
               }
            });
   }

   private class ServiceRequestWrapper extends HttpRequestWrapper {

      private final ServiceInstance instance;

      public ServiceRequestWrapper(HttpRequest request, ServiceInstance instance) {
         super(request);
         this.instance = instance;
      }

      @Override
      public URI getURI() {
         URI uri = LoadBalancerInterceptor.this.loadBalancer.reconstructURI(
               this.instance, getRequest().getURI());
         return uri;
      }
   }
}
```

- 接下来查看`RibbonLoadBalancerClient`具体实现类中的`execute`方法：

```java
public class RibbonLoadBalancerClient implements LoadBalancerClient {
	@Override
	public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
         // 通过传入的serviceId去获取具体的服务实例名
   		ILoadBalancer loadBalancer = getLoadBalancer(serviceId);
   		Server server = getServer(loadBalancer);
   		if (server == null) {
      		throw new IllegalStateException("No instances available for " + serviceId);
   		}
        // 将获取的server包装成一个RibbonServer类的对象
   		RibbonServer ribbonServer = new RibbonServer(serviceId, server, isSecure(server,
         	serviceId), serverIntrospector(serviceId).getMetadata(server));
        // 构建Ribbon负载均衡器的上下文对象
   		RibbonLoadBalancerContext context = this.clientFactory.getLoadBalancerContext(serviceId);
        // 记录状态且进行跟踪 
   		RibbonStatsRecorder statsRecorder = new RibbonStatsRecorder(context, server);
   		try {
             // 使用riibonServer回调LoadBalancerRequest对象的apply方法，
		    // 向一个具体的服务实例发送实际请求
      		T returnVal = request.apply(ribbonServer);
      		statsRecorder.recordStats(returnVal);
      		return returnVal;
   		}
   		// catch IOException and rethrow so RestTemplate behaves correctly
   		catch (IOException ex) {
      		statsRecorder.recordStats(ex);
      		throw ex;
   		}
   		catch (Exception ex) {
      		statsRecorder.recordStats(ex);
      		ReflectionUtils.rethrowRuntimeException(ex);
   		}
   		return null;
	}
}
```

- `getServer`方法源码如下。可以看出，在选出具体的服务实例名时并没有使用`LoadBalancerClient`接口中的`choose`函数，而是使用的`Netflix Ribbon`自身的`ILoadBalancer`接口中定义的`chooseServer`函数。

```java
protected Server getServer(ILoadBalancer loadBalancer) {
   if (loadBalancer == null) {
      return null;
   }
   return loadBalancer.chooseServer("default"); // TODO: better handling of key
}
```

- `ILoadBalancer`接口源码如下：

```java
public interface ILoadBalancer {
    
	// 向负载均衡器中维护的实例列表增加服务实例
	public void addServers(List<Server> newServers);
	
    // 通过某种策略，从负载均衡器中挑选出一个具体的服务实例
	public Server chooseServer(Object key);
	
    // 标识负载均衡其中某个具体实例已经停止服务。
    // 该server对象，存储了服务端节点的一些元数据信息，如host、post以及一些部署信息等。 
	public void markServerDown(Server server);
	
	@Deprecated // 已过时
	public List<Server> getServerList(boolean availableOnly);

    // 获取当前正常服务的实例列表
    public List<Server> getReachableServers();

    // 获取所有已知的服务实例列表
	public List<Server> getAllServers();
}
```

- `RibbonServer`类实现的是`ServerInstance`接口，下面是两者的源码：

```java
public interface ServiceInstance {

	String getServiceId();

	String getHost();

	int getPort();

	boolean isSecure();

	URI getUri();

	Map<String, String> getMetadata();
}
```

```java
protected static class RibbonServer implements ServiceInstance {
	private final String serviceId;
	private final Server server;
    // 是否使用HTTPS标识
	private final boolean secure;
    // 用于存储元数据的map集合
	private Map<String, String> metadata;
	protected RibbonServer(String serviceId, Server server) {
		this(serviceId, server, false, Collections.<String, String> emptyMap());
	}
	protected RibbonServer(String serviceId, Server server, boolean secure,
			Map<String, String> metadata) {
		this.serviceId = serviceId;
		this.server = server;
		this.secure = secure;
		this.metadata = metadata;
	}
    
   // ...... 忽略get函数
}
```

- 查看具体实现类中`apply`方法，在`LoadBalancerInterceptor`类中以匿名内部类形式出现：

```java
@Override
public ClientHttpResponse apply(final ServiceInstance instance) throws Exception {
    HttpRequest serviceRequest = new ServiceRequestWrapper(request, instance);
    return execution.execute(serviceRequest, body);
}
```

- `ServiceRequestWrapper`重写了`getURI`方法，重写的`getURI`通过调用`LoadBalancerClient`接口的`reconstructURI`方法来重新构造一个`URI`来进行访问。该类也在`LoadBalancerInterceptor`类中，以局部内部类形式存在：

```java
   private class ServiceRequestWrapper extends HttpRequestWrapper {

      private final ServiceInstance instance;

      public ServiceRequestWrapper(HttpRequest request, ServiceInstance instance) {
         super(request);
         this.instance = instance;
      }

      @Override
      public URI getURI() {
         URI uri = LoadBalancerInterceptor.this.loadBalancer.reconstructURI(
               this.instance, getRequest().getURI());
         return uri;
      }
   }
}
```

- 关于`execution.execute(serviceRequest, body)`调用时，会调用`InterceptingClientHttpRequest`下`InterceptingRequestExecution`类的`execute`方法，源码如下：

```java
private class InterceptingRequestExecution implements ClientHttpRequestExecution {
	private final Iterator<ClientHttpRequestInterceptor> iterator;
	public InterceptingRequestExecution() {
		this.iterator = interceptors.iterator();
	}
	@Override
	public ClientHttpResponse execute(HttpRequest request, byte[] body) throws IOException {
		if (this.iterator.hasNext()) {
			ClientHttpRequestInterceptor nextInterceptor = this.iterator.next();
			return nextInterceptor.intercept(request, body, this);
		}
		else {
			ClientHttpRequest delegate = requestFactory.createRequest(
                // 调用的getURI是上述重写过后的getURI()方法
                // 它会使用RibbonLoadBalancerClient中实现的reconstructURI来组织具体请求的服务实例地址
                				request.getURI(), request.getMethod());
			delegate.getHeaders().putAll(request.getHeaders());
			if (body.length > 0) {
				StreamUtils.copy(body, delegate.getBody());
			}
			return delegate.execute();
		}
	}
}
```

- `RibbonLoadBalancerClient`类中的`reconstructURI`方法

```java
@Override
public URI reconstructURI(ServiceInstance instance, URI original) {
   Assert.notNull(instance, "instance can not be null");
   // 通过ServiceInstance对象获取serviceId
   String serviceId = instance.getServiceId();
   // 从SpringClientFactory对象中获取对应serviceId的负载均衡器的上下文RibbonLoadBalancerContext
   RibbonLoadBalancerContext context = this.clientFactory.getLoadBalancerContext(serviceId);
   // 根据host和port信息来构建服务实例信息的Server对象
   Server server = new Server(instance.getHost(), instance.getPort());
   boolean secure = isSecure(server, serviceId);
   URI uri = original;
   if (secure) {
      // 如果使用了HTTPS协议的话
      uri = UriComponentsBuilder.fromUri(uri).scheme("https").build().toUri();
   }
   // 构建服务实例的uri
   return context.reconstructURIWithServer(server, uri);
}
```

- `SpringClientFactory`：是一个用来创建客户端负载均衡器的工厂类，为每一个不同名的`Ribbon`客户端生成不同的`Spring`上下文。
- `RibbonLoadBalancerContext`：是`LoadBalancerContext`的子类，用于存储一些被负载均衡器使用的上下文内容和`API`操作，比如`reconstructURIWithServer(server, uri)`方法。该方法具体实现如下：

```java
public URI reconstructURIWithServer(Server server, URI orig
    String host = server.getHost();
    int port = server .getPort();
    if (host.equals(original.getHost()) 
            && port == original.getPort()) {
        return original;
    }
    String scheme = original.getScheme();
    if (scheme == null) {
        scheme = deriveSchemeAndPortFromPartialUri(original
    }
    try {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (!Strings.isNullOrEmpty(original.getRawUserInfo(
            sb.append(original.getRawUserInfo()).append("@"
        }
        sb.append(host);
        if (port >= 0) {
            sb.append(":").append(port);
        }
        sb.append(original.getRawPath());
        if (!Strings.isNullOrEmpty(original.getRawQuery()))
            sb.append("?").append(original.getRawQuery());
        }
        if (!Strings.isNullOrEmpty(original.getRawFragment(
            sb.append("#").append(original.getRawFragment()
        }
        URI newURI = new URI(sb.toString());
        return newURI;            
    } catch (URISyntaxException e) {
        throw new RuntimeException(e);
    }
}
```

- 以上便是`LoadBalancerInterceptor`拦截器对`RestTempalte`的请求与进行拦截，并利用`Spring Cloud`的负载均衡器`LoadBalancerClient`将以服务名为`host`的`URI`转换成具体的服务实例地址的过程。
- 值得注意的一点是，`Ribbon`在实现负载均衡器的时候，实际使用的还是`Ribbon`中定义的`ILoadBalancer`接口的实现，自动化配置会采用`ZoneAwareLoadBalancer`的实例来实现客户端的负载均衡。
- 接下来的就是对`Spring Cloud Ribbon`整合的负载均衡器和负载均衡策略的总结。