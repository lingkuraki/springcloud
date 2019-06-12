# Spring Cloud Hystrix总结

- `Spring Cloud Hystrix`实现了**断路器、线程隔离**等一系列的服务保护功能。
- `Hystrix`具备**服务降级、服务熔断、线程和信号隔离、请求缓存、请求合并以及服务监控**等强大功能。

## 原理分析

###工作流程

![](E:\studySelf\image\hystrix流程图.png)

> 1.创建`HystrixCommand`或`HystrixObservableCommand`对象

- 创建该对象用来表示**对依赖服务的操作请求，同时传递所有需要的参数**。使用的是`GoF`中的**命令模式**来实现对服务调用操作的封装。而这两个`Command`对象分别针对不同的应用场景。
  - `HystrixCommand:`用在依赖的服务返回单个操作结果的时候。
  - `HystrixObservableCommand:`用在依赖的服务返回多个操作结果的时候。从这个对象的定义可以看出，这个对象采用的是**观察者-订阅者**模式。

> 2.命令执行

一共存在`4`种命令的执行方式。每个对象个实现了两种执行方式的方法。

- `HystrixCommand`实现了以下两种方式：
  - `execute():`同步执行，从依赖的服务返回一个单一的结果对象，或是在发生错误的时候抛出异常。
  - `queue():`异步执行，直接返回一个`Future`对象，其中包含了服务执行结果时要返回的单一结果对象。
- `HystrixObservableCommand`实现了另外两种执行方式：
  - `observe():`返回`Observable`对象，代表了操作的多个结果，它是一个`Hot Observable`。
  - `toObservable():`同样会返回`Observable`对象，也代表操作的多个结果，但它返回的是一个`Cold Observable`。
- `Hot Observable`和`Cold Observable`，分别对应了`command.observe()`和`command.toObservable()`的返回对象。
  - 对于`Hot Observable`，它不论**事件源**是否有**订阅者**，都会在创建后对事件进行发布。所以，对于**订阅者**来说，它可能只看到事件的局部过程。
  - 对于`Cold Observable`，他在没有**订阅者**的时候并不会发布事件，而是进行等待，直到有**订阅者**之后才发布事件。所以，它可以保证从一开始看到整个操作的全部过程。
- `execute()`是通过`queue()`返回的`Future<T>`对象的`get()`方法来实现同步的。
- `queue()`则是通过`toObservable()`来获得一个`Cold Observable`，并且通过`toBlocking()`将`Observable`转换成`BlockingObservable`，它可以把数据以阻塞的范式发布出来。而`toFuture`方法则再将对象转换为`Future`返回，并不会阻塞。

```java
public R execute() {
    try {
        return queue().get();
    } catch (Exception e) {
        throw decomposeException();
    }
}

public Future<R> queue() {
	final Observable<R> o = toObservable();
    final Future<R> f = o.toBlocking().toFuture();
    
    if (f.isDone()) {
        // TODO: 处理立即抛出的错误
    }
    return f;
}
```

> 3.结果是否被缓存

- 若当前命令的请求缓存功能是开启的，并且该命令命中缓存，那么缓存的结果会立即以`Observable`对象的形式返回。

> 4.断路器是否打开

- 如果命令结果没有命中缓存，那么`Hystrix`在执行命令前需要检查断路器是否为打开状态：
  - 如果是打开的，那么`Hystrix`不会执行命令，而是转接到`fallback`处理逻辑。
  - 如果是关闭的，那么`Hystrix`会检查是否有可用的资源来执行命令。

> 5.线程池/请求队列/信号量是否占满

- 如果与命令相关的线程池和请求队列，或者信号量（不使用线程池的时候）已经被占满，那么`Hystrix`也不会执行命令，而是转接到`fallback`处理逻辑。注意：`Hystrix`所判断的线程池并非容器的线程池，而是每个依赖服务的专有线程池。

> 6.`construct()`和`run()`方法

- `HystrixCommand.run()`：返回一个单一的结果，或者抛出异常。
- `HystrixObservableCommand.construct()`：返回一个`Observable`对象来发射多个结果，亦通过`onError`发送错误通知
- 如果方法的执行时间超过了命令设置的超时阈值，当前处理线程将会抛出一个`TimeoutException`。此时，`Hystrix`会转接到`fallback`处理逻辑。

> 7.计算断路器的健康值

- `Hystrix`会将**成功、失败、拒绝和超时**等信息报告给断路器，断路器会维护一组计数器来统计这些数据。
- 断路器会根据这些统计数据来决定是否将断路器打开，来对某个依赖服务的请求进行**熔断/短路**，直到恢复期结束。若在恢复期结束后，根据统计数据来判断如果还是未达到健康指标，就再次**熔断/短路**。

> 8.`fallback`处理

- 当命令执行失败的时候，`Hystrix`会进入`fallback`尝试回退处理，就是通常所说的**服务降级**。
  1. 当前命令处于**熔断/短路**状态，断路器是打开的时候，会进行服务降级。
  2. 当前命令的线程池、请求队列或者信号量被占满的时候，会进行服务降级。
  3. `HystrixObservableCommand.construct()`或`HystrixCommand.run()`抛出异常的时候，会进行服务降级。
- 实现一个通用的响应结果，并且该结果的处理逻辑应当是从缓存或者根据一些静态逻辑来获取，而不是依赖网络请求获取。
  - 当使用`HystrixCommand`的时候，通过实现`HystrixCommand.getFallback()`来实现服务降级逻辑。
  - 当使用`HystrixObservableCommand`的时候，通过`HystrixObservableCommand.resumeWithFallback()`来实现服务降级逻辑，该方法返回一个`Observable`对象来发射一个或者多个降级结果。
- 如果降级执行失败的时候，`Hystrix`会根据不同的执行方法做出不用的处理：
  1. `execute():`抛出异常；
  2. `queue():`正常返回`Future`对象，但是当调用`get()`来获取结果的时候抛出异常。
  3. `observer():`正常返回`Observable`对象，当订阅它时，将立即通过调用订阅者的`OnError`方法来通知中止请求。
  4. `toObservable():`正常返回`Observable`对象，当订阅它时，将通过调用订阅者的`OnError`方法来通知中止请求。

> 9.返回成功的响应

- 当`Hystrix`命令执行成功之后，它会将**处理结果直接返回**或是**以`Observable`的形式返回**。

![](E:\studySelf\image\依赖结果返回流程图.png)

- `toObservable():`返回最原始的`Observable`，必须通过订阅它才会真正触发命令的执行流程。
- `observe():`在`toObservable()`产生原始`Observable`之后立即订阅它，让命令能够马上开始异步执行，并返回一个`Observable`对象，当调用它的`subscribe`时，将重新产生结果和通知给订阅者。
- `queue():`将`toObservable()`产生的原始`Observable`通过`toBlocking()`方法转换成`BlockingObservable`对象，并调用它的`toFuture()`方法返回异步的`Future`对象。
- `execute():`在`queue()`产生异步结果`Future`对象之后，通过调用`get()`方法阻塞并等待结果的返回。

###断路器原理

- 先看下断路器`HystrixCircuitBreaker`的定义

```java
public interface HystrixCircuitBreaker {

    // 每个Hystrix命令的请求都通过它判断是否被执行
    boolean allowRequest();

    // 返回当前断路器是否打开
    boolean isOpen();
    
    // 用来闭合断路器
    void markSuccess();

    void markNonSuccess();

    boolean attemptExecution();
    
    class Factory {...}
    
    static class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {...}
    
    static class NoOpCircuitBreaker implements HystrixCircuitBreaker {...}
}
```

- 静态类`Factory`中维护了一个`Hystrix`命令与`HystrixCircuitBreaker`的关系集合：

  - `ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand`，其中`key`通过`HystrixCommandKey`定义，每一个`Hystrix`命令需要有一个`key`来标识，同时一个`Hystrix`命令也会在该集合中找到它对应的断路器`HystrixCircuitBreaker`实例。

- 静态类`NoOpCircuitBreaker`定义了一个什么都不做的断路器实现，它允许所有请求，并且断路器状态始终闭合。

- 静态类`HystrixCircuitBreakerImpl`是断路器接口`HystrixCircuitBreaker`的实现类，在该类中定义了是断路器的`4`种核心对象。

  1. `HystrixCommandProperties properties:`断路器对应`HystrixCommand`实例的属性对象。
  2. `HystrixCommandMetrics metrics:`用来让`HystrixCommand`记录各类度量指标的对象。
  3. `AtomicBoolean circuitOpen:`断路器是否打开的标志，默认为`false`。
  4. `AtomicLong circuitOpenedOrLastTestedTime:`断路器打开或是上一次测试的时间戳。

- 对`HystrixCircuitBreaker`接口的各个方法的实现如下：

  - ```java
    @Override
    public boolean isOpen() {
        if (properties.circuitBreakerForceOpen().get()) {
            return true;
        }
        if (properties.circuitBreakerForceClosed().get()) {
            return false;
        }
        return circuitOpened.get() >= 0;
    }
    ```

  - 如果断路器被强制开启，则返回`true`；如果被强制关闭，则返回`false`。如果都没有的话，则返回`circuitOpened`与`0`比较的布尔值。

  - ```java
    @Override
    public boolean allowRequest() {
        // 如果断路器打开，则返回false，拒绝所有请求
        if (properties.circuitBreakerForceOpen().get()) {
            return false;
        }
        // 如果强制关闭了，则允许所有请求
        if (properties.circuitBreakerForceClosed().get()) {
            return true;
        }
        if (circuitOpened.get() == -1) {
            return true;
        } else {
            // 判断当前状态是否是搬开状态，是的话，则返回false，允许请求
            if (status.get().equals(Status.HALF_OPEN)) {
                return false;
            } else {
    		   // 判断断开的时间戳 + cbswMs与当前时间的比较值。false说明允许请求
                return isAfterSleepWindow();
            }
        }
    }   
    
    private boolean isAfterSleepWindow() {
        final long circuitOpenTime = circuitOpened.get();
        final long currentTime = System.currentTimeMillis();
        final long sleepWindowTime= properties.circuitBreakerSleepWindowInMilliseconds().get();
        return currentTime > circuitOpenTime + sleepWindowTime;
    }
    ```

  - `isAfterSleepWindow`方法，通过`circuitBreakerSleepWindowInMilliseconds`属性设置了一个断路器打开之后的休眠时间`(默认5秒)`，在该休眠时间到达之后，将再次允许请求尝试访问。此时断路器处于**半开**状态。若此时请求继续失败，断路器有进入打开状态，并继续等待下一个休眠窗口过去之后再次尝试；若请求成功，则将断路器重新置于关闭状态。

  - `markSuccess():`在**半开路**状态时使用。若`Hystrix`命令调用成功，通过调用它将打开的断路器关闭，并重置度量指标对象。

  ```java
  @Override
  public void markSuccess() {
      if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
          // 重置度量指标对象
          metrics.resetStream();
          Subscription previousSubscription = activeSubscription.get();
          if (previousSubscription != null) {
              previousSubscription.unsubscribe();
          }
          Subscription newSubscription = subscribeToStream();
          activeSubscription.set(newSubscription);
          circuitOpened.set(-1L);
      }
  }
  ```

![](E:\studySelf\image\Hystrix执行逻辑.jpg)

### 依赖隔离

- `Hystrix`使用**舱壁模式**实现线程池的隔离，它会为每一个依赖服务创建一个独立的线程池，这样就算某个依赖服务出现延迟过高的情况，也只是对该依赖服务的调用产生影响，而不会拖慢它的依赖服务。通过实现对依赖服务的线程池隔离，可以带来如下优势：
  1. **应用自身得到完全保护，不会受不可控的依赖服务影响**。
  2. **可以有效降低接入新服务的风险**。因为即使新服务接入后运行不稳定，也不会影响其它的请求。
  3. **当依赖的服务从失效恢复正常后，它的线程池会被清理并且能够马上恢复健康的服务**。
  4. 当依赖的服务出现配置错误，线程池会快速反映出此问题。同时，可以动态刷新属性。
  5. 当依赖的服务因实现机制调整等原因造成其性能出现很大变化时，线程池的监控指标信息会反映出这样的变化。
- 通过对依赖服务实现线程池隔离，可让应用更加健壮，不会因为个别依赖服务出现问题而引起非相关服务的异常。

## 使用详解

### 创建请求命令

- 通过继承的方式实现，如下：

```java
public class UserCommand extends HystrixCommand<User> {

    private RestTemplate restTemplate;

    private Long id;

    public UserCommand() {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("CommandGroupKey"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("CommandKey"))
                .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("ThreadPoolKey")));
    }

    public UserCommand(Setter setter, RestTemplate restTemplate, Long id) {
        super(setter);
        this.restTemplate = restTemplate;
        this.id = id;
    }

    @Override
    protected User run() throws Exception {
        return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
    }

    @Override // 重写该方法，开启缓存
    protected String getCacheKey() {
        return String.valueOf(id);
    }
}
```

- 既可以实现**同步请求**，也可以实现**异步请求**：
  - 同步请求：`User u = new UserCommand(restTemplate, 1L).execute();`
  - 异步请求：`Future<User> futureUser = new UserCommand(restTemplatem, 1L).queue();`

- 另外，就是使用`@HystrixCommand`注解来实现`Hystrix`命令的定义：

```java
@Service
public class UserService {

    @Autowired
    private RestTemplate restTemplate;

    // 同步执行
    @HystrixCommand(commandKey = "getUserById", groupKey = "UserGroup", threadPoolKey = "getUserByIdThread")
    public User getUserById(Long id) {
        return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
    }

    // 异步执行
    @HystrixCommand
    public Future<User> getUserByIdAsyn(final Long id) {
        return new AsyncResult<User>() {
            @Override
            public User invoke() {
                return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
            }
        };
    }
}
```

- 除此之外，也可以将`HystrixCommand`通过`Observable`来实现响应式执行方式。
  - 通过调用`observe()`和`toObservable()`方法可以返回`Observable`对象。**该对象只能发布一次数据**。

```java
Observable<String> ho = new UserCommand(restTemplate, 1L).observae();
Observable<String> co = new UserCommand(restTemplate, 1L).toObservable();
```

- `Hystrix`提供了另外一个特殊命令封装`HystrixObservableCommand`，通过它实现的命令可以发射多次的`Observable`

```java
public class UserObservableCommand extends HystrixObservableCommand<User> {

    private RestTemplate restTemplate;

    private Long id;

    public UserObservableCommand(Setter setter, RestTemplate restTemplate, Long id) {
        super(setter);
        this.restTemplate = restTemplate;
        this.id = id;
    }

    @Override
    protected Observable<User> construct() {
        return Observable.create(observer -> {
            try {
                if (!observer.isUnsubscribed()) {
                    User user = restTemplate.getForObject("http://user-service/user/{1}", User.class, id);
                    observer.onNext(user);
                    observer.onCompleted();
                }
            } catch (Exception e) {
                observer.onError(e);
            }
        });
    }
}
```

- 对应的注解开发如下：

```java
@HystrixCommand(observableExecutionMode = ObservableExecutionMode.EAGER)
public Observable<User> getUserByIdObserval(Long id) {
    return Observable.create(observer -> {
        try {
            if (!observer.isUnsubscribed()) {
                User user = restTemplate.getForObject("http://user-service/{1}", User.class, id);
                observer.onNext(user);
                observer.onCompleted();
            }
        } catch (Exception e) {
            observer.onError(e);
        }
    });
}
```

- 可以通过`observableExecutionMode`参数来控制是使用`observe()`还是`toObservable()`的执行方式。
  - `observableExecutionMode = ObservableExecutionMode.EAGER:`表示执行`observe()`方式。
  - `observableExecutionMode = ObservableExecutionMode.LAZY:`表示使用`toObservable()`执行方式。

### 定义服务降级

- 在`HystrixCommand`中可以通过重载`getFallback()`方法来实现服务降级逻辑，`Hystrix`会在`run()`执行过程中出现错误、超时、线程池拒绝、断路器熔断等情况时，执行`getFallback()`方法内的逻辑。

  ```java
  @Override
  protected User getFallback() {
      return new User();
  }
  ```

- 使用注解实现服务降级只需要使用`@HystrixCommand`中的`fallbackMethod`参数来指定具体的服务降级实现方法。

  ```java
  // 异步执行
  @HystrixCommand(fallbackMethod = "defaultUser")
  public Future<User> getUserByIdAsyn(final Long id) {
      return new AsyncResult<User>() {
          @Override
          public User invoke() {
              return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
          }
      };
  }
  public User defaultUser(){
      return new User();
  }
  ```

- 在使用注解来定义服务降级逻辑时，我们需要将具体的`Hystrix`命令与`fallback`实现函数定义在同一个类中，并且`fallbackMethod`的值必须与实现`fallback`方法的名字相同。

### 异常处理

> 异常传播

- 在`HystrixCommand`实现的`run()`方法中抛出异常时，除了`HystrixBadRequestException`之外，其它异常均会被`Hystrix`认为命令执行失败并触发服务器降级的处理逻辑。
- `@HystrixCommand`注解的`ignoreExceptions`参数支持忽略指定异常类型。被其指定的异常即使抛出也不会触发后续的`fallback`逻辑。

> 异常获取

- 传统方式：可以用`getFallback()`方法通过`Throwable getExecutionException()`方法来获取具体的异常。
- 注解方式：只需要在`fallback`实现方法的参数中增加`Throwable e`对象的定义，就可以在方法内部获取触发服务降级的具体异常内容。

```java
@HystrixCommand(fallbackMethod = "fallback1")
User getUserById(String id) {
    throw new RuntimeException("getUserById command failed");
}

User fallback1(String id, Throwable e) {
    assert "getUserById command failed".equals(e.getMessage());
}
```

###命令名称、分组以及线程池划分

```java
public UserCommand() {
    super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("CommandGroupKey"))
            .andCommandKey(HystrixCommandKey.Factory.asKey("CommandKey")));
}
```

- 先调用了`withGroupKey`来设置**命令组名**，然后才通过调用`andCommandKey`来设置**命令名**。
- 只有`withGroupKey`静态函数可以创建`Setter`的实例，所以`GroupKey`是每一个`Setter`必需的参数，而`CommandKey`则是一个可选参数。
- `Hystrix`命令默认的线程划分也是根据命令分组来实现的。默认情况下，`Hystrix`会让相同组名的命令使用同一个线程池，所以需要在创建`Hystrix`命令时为其指定命令组名来实现默认的线程池划分。

```java
public UserCommand() {
    super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("CommandGroupKey"))
            .andCommandKey(HystrixCommandKey.Factory.asKey("CommandKey"))
            .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("ThreadPoolKey")));
}
```

- 如果在没有特别指定`HystrixThreadPoolKey`的情况下，依然会使用命令组的方式来划分线程池。通常情况下，尽量通过`HystrixThreadPoolKey`的方式来划分，而不是通过组名的默认方式来实现划分。
- 在注解开发中，通过设置`@HystrixCommand`注解的`commandKey`、`groupKey`以及`threadPoolKey`属性即可。分别表示命令名称、分组以及线程池划分。

```java
@HystrixCommand(commandKey = "getUserById", groupKey = "UserGroup", threadPoolKey = "UseThread")
public User getUserById(Long id) {
    return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
}
```

###请求缓存

- 在高并发的场景下，`Hystrix`中提供了请求缓存的功能，来方便的优化系统，达到减轻高并发时的请求线程消耗、降低请求响应时间的效果。

>开启请求缓存功能

- 在实现`HystrixCommand`或`HystrixObservableCommand`时，通过重载`getCacheKey()`方法来开启请求缓存。

```java
public class UserCommand extends HystrixCommand<User> {

    private RestTemplate restTemplate;

    private Long id;

    public UserCommand() {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("CommandGroupKey"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("CommandKey"))
                .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("ThreadPoolKey")));
    }

    public UserCommand(Setter setter, RestTemplate restTemplate, Long id) {
        super(setter);
        this.restTemplate = restTemplate;
        this.id = id;
    }

    @Override
    protected User run() throws Exception {
        return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
    }

    @Override
    protected User getFallback() {
        return new User();
    }

    @Override // 重写该方法，开启缓存
    protected String getCacheKey() {
        return String.valueOf(id);
    }
}
```

- `Hystrix`根据`getCacheKey`方法返回的值来区分是否是重复的请求，如果它们的`cacheKey`相同，那么该依赖服务只会在第一个请求到达时真实地调用一次，另外一个请求则是直接从请求缓存中返回结果。所以通过开启请求缓存具备以下三个好处：
  1. 减少重复的请求数，降低依赖服务的并发度。
  2. 在同一用户请求的上下文中，相同依赖服务的返回数据始终保持一致。
  3. 请求缓存在`run()`和`construct()`执行之前生效，所以可以有效减少不必要的线程开销。

> 清理失效缓存功能

- `Hystrix`可以通过`HystrixRequestCache.clear()`方法来进行缓存的清理，如下两个示例类：

```java
public class UserGetCommand extends HystrixCommand<User> {

    private static final HystrixCommandKey GETTER_KEY = HystrixCommandKey.Factory.asKey("CommandKey");
    private RestTemplate restTemplate;
    private Long id;

    public UserGetCommand(RestTemplate restTemplate, Long id) {

        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("GetSetGet"))
              .andCommandKey(GETTER_KEY));
        this.restTemplate = restTemplate;
        this.id = id;
    }

    @Override
    protected User run() throws Exception {
        return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
    }

    @Override
    protected String getCacheKey() {
        // 根据id置入缓存
        return String.valueOf(id);
    }

    public static void flushCache(Long id) {
        // 刷新缓存，根据id进行清理
        HystrixRequestCache.getInstance(
            GETTER_KEY, HystrixConcurrencyStrategyDefault.getInstance()).clear(String.valueOf(id));
    }
}
```

```java
public class UserPostCommand extends HystrixCommand<User> {

    private RestTemplate restTemplate;
    private User user;

    public UserPostCommand(RestTemplate restTemplate, User user) {

        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("GetSetGet")));
        this.restTemplate = restTemplate;
        this.user = user;
    }

    @Override
    protected User run() throws Exception {
        // 写操作
        User user1 = restTemplate.postForObject("http://user-service/users", user, User.class);
        // 刷新缓存，清理缓存中失效的User
        UserGetCommand.flushCache(user.getId());
        return user1;
    }
}
```

- 在`UserGetCommand`的实现中，增加了一个静态方法`flushCache`，该方法通过`HystrixRequestCache.getInstance(GETTER_KEY, HystrixConcurrencyStrategyDefault.getInstance())`方法从默认的`Hystrix`并发策略中根据`GETTER_KEY`获取到改名了的请求缓存对象`HystrixRequestCache`的实例，然后再调用该请求缓存对象实例的`clear`方法，从而进行缓存清理。

> 工作原理

- 首先`getCacheKey`方法来自于`AbstractCommand`抽象命令类实现，该类相关源码如下：

```java
abstract class AbstractCommand<R> implements HystrixInvokableInfo<R>, HystrixObservable<R> {
    
    protected final HystrixRequestCache requestCache;
    
	protected String getCacheKey() {
        return null;
    }
    
    protected boolean isRequestCachingEnabled() {
        return properties.requestCacheEnabled().get() && getCacheKey() != null;
    }
    
    // 核心分为两步：尝试获取请求缓存以及将请求结果加入缓存
    public Observable<R> toObservable() {
        ....
        // 尝试从缓存中获取结果
    	final boolean requestCacheEnabled = isRequestCachingEnabled();
	    final String cacheKey = getCacheKey();
        final AbstractCommand<R> _cmd = this;
		/* try from cache first */
		if (requestCacheEnabled) {
    	  	HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) 			requestCache.get(cacheKey);
    		if (fromCache != null) {
        		isResponseFromCache = true;
        		return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
    		}
		}
        Observable<R> hystrixObservable = Observable.defer(applyHystrixSemantics)
                .map(wrapWithAllOnNextHooks);
	    Observable<R> afterCache;
        ....
            
        // 加入缓存
        if (requestCacheEnabled && cacheKey != null) {
            // wrap it for caching
            HystrixCachedObservable<R> toCache = HystrixCachedObservable.
                									from(hystrixObservable, _cmd);
            HystrixCommandResponseFromCache<R> fromCache = 
                (HystrixCommandResponseFromCache<R>) requestCache.putIfAbsent(cacheKey, toCache);
            if (fromCache != null) {
                // another thread beat us so we'll use the cached value instead
                toCache.unsubscribe();
                isResponseFromCache = true;
                return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
            } else {
                // we just created an ObservableCommand so we cast and return it
                afterCache = toCache.toObservable();
            }
        } else {
            afterCache = hystrixObservable;
        }
	}
    ....
}
```

> **使用注解实现请求缓存**

- 提供了三个专用与请求缓存的注解

| 注解           | 描述                                                         | 属性                           |
| -------------- | ------------------------------------------------------------ | ------------------------------ |
| `@CacheResult` | 该注解用来标记请求命令返回的结果应该被缓存，它必须与`@HystrixCommand`注解结合使用。 | `cacheKeyMethod`               |
| `@CacheRemove` | 该注解用来让请求命令的缓存失效，失效的缓存根据定义的`Key`决定。 | `commandKey`，`cacheKeyMethod` |
| `@CacheKey`    | 该注解用来在请求命令的参数上标记，使其作为缓存的`Key`值，如果没有标注则会使用所有的参数。**如果同时还使用了`@CacheResult`和`CacheRemove`注解的`cacheKeyMethod`方法指定缓存`Key`的生成，那么该注解将不会起作用**。 | `value`                        |

- **设置请求缓存**：由于该方法被`@CacheResult`注解修饰，所以`Hystrix`会将该结果置入缓存中，而它的缓存`Key`值会使用所有的参数。

```java
@CacheResult
@HystrixCommand
public User getUserById(Long id) {
    return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
}
```

- **定义缓存`Key`：**有两种方式定义缓存的`key`，具体如下：

```java
@CacheResult(cacheKeyMethod = "getUserByIdCacheKey")
@HystrixCommand
public User getUserById(Long id) {
    return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
}

private Long getUserByIdCacheKey(Long id) {
    return id;
}
```

通过`@CacheKey`注解实现的方式更加简单，它的优先级比`cacheKeyMethod`的优先级低；如果已经使用了`cacheKeyMethod`指定缓存`Key`的生成函数，那么`@CacheKey`注解不会生效。

```java
@CacheResult
@HystrixCommand
public User getUserById(@CacheKey("id") Long id) {
    return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
}
```

`@CacheKey`注解除了可以指定方法参数作为缓存`Key`之外，它还允许访问参数对象的内部属性作为缓存`Key`：

```java
@CacheResult
@HystrixCommand
public User getUserById(@CacheKey("id") User user) {
    return restTemplate.getForObject("http://user-service/users/{1}", User.class, user.getId());
}
```

- **缓存清理：**通过`@CacheRemove`注解来实现失效缓存的清理

```java
@CacheResult
@HystrixCommand
public User getUserById(@CacheKey("id") Long id) {
    return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
}

@CacheRemove(commandKey = "getUserById")
@HystrixCommand
public void update(@CacheKey("id") User user) {
    return restTemplate.getForObject("http://user-service/users/{1}", user, User.class);
}
```

- `@CacheRemove`注解的`commandKey`属性是必须是指定，它用来指明需要使用请求缓存的请求命令。

### 请求合并

- `Hystrix`提供了`HystrixCollapser`来实现请求的合并，以减少通信消耗和线程数的占用。

```java
public abstract class HystrixCollapser<BatchReturnType, ResponseType, RequestArgumentType> implements HystrixExecutable<ResponseType>, HystrixObservable<ResponseType> {
    ...
    // 该函数定义获取请求参数的方法
    public abstract RequestArgumentType getRequestArgument();
    
    // 合并请求产生批量命令的具体实现方法
    protected abstract HystrixCommand<BatchReturnType> createCommand(
        Collection<CollapsedRequest<ResponseType, RequestArgumentType>> requests);
    
    // 批量命令结果返回后的处理，需要实现将批量结果拆分并传递给合并前的各个原子请求命令的逻辑
    protected abstract void mapResponseToRequests(BatchReturnType batchResponse, Collection<CollapsedRequest<ResponseType, RequestArgumentType>> requests);
}
```





















































































