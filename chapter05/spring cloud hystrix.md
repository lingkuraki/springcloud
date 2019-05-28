# Spring Cloud Hystrix总结

- `Spring Cloud Hystrix`实现了**断路器、线程隔离**等一系列的服务保护功能。
- `Hystrix`具备**服务降级、服务熔断、线程和信号隔离、请求缓存、请求合并以及服务监控**等强大功能。

## 原理分析

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







































































