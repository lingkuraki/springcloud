# spring cloud feign

- `Spring Cloud Feign`具有可插拔的注解支持，包括`Feign`注解和`JAX-RS`注解。

## 快速入门

- 创建应用主类`FeignApplication`，并通过`@EnableFeignClients`注解开启`Spring Cloud Feign`的支持功能。

```java
@EnableFeignClients
@EnableDiscoveryClient
@SpringBootApplication
public class FeignApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeignApplication.class, args);
    }
}
```

- 定义一个接口，通过`@FeignClient`注解指定服务名来绑定服务，然后再使用`Spring MVC`的注解来绑定具体该服务提供的`REST`接口。

```java
@FeignClient(name = "hello-service")
public interface HelloService {

    @RequestMapping("/hello")
    String hello();
}
```

- 接着，创建一个`ConsumerController`来实现对`Feign`客户端的调用。使用`@Autowired`直接注入上面定义的`HelloService`实例，并在`helloConsumer`函数中调用这个绑定了`hello-service`服务接口的客户端来向该服务发起`/hello`接口的调用。

```java
@RestController
public class CustomerController {

    @Autowired
    private HelloService helloService;
    
    @RequestMapping(value = "/feign-consumer", method = RequestMethod.GET)
    public String helloConsumer() {
        return helloService.hello();
    }
}
```

- 最后，同`Ribbon`实现的服务消费者一样，需要在`application.properties`中指定服务注册中心，并定义自身的服务名为`feign-consumer`。

```java
server:
  port: 9010
spring:
  application:
    name: feign-consumer
eureka:
  client:
    service-url:
      defaultZone: http://localhost:1111/eureka/
```

## 参数绑定

- `Feign`中对几种不同形式参数的绑定方法。

```java
@FeignClient(name = "hello-service", configuration = LoggerFeignConfig.class, fallback = HelloServiceFallback.class)
public interface HelloService {

    @RequestMapping("/hello")
    String hello();

    @RequestMapping(value = "/hello1", method = RequestMethod.GET)
    String hello(@RequestParam("name") String name);

    @RequestMapping(value = "/hello2", method = RequestMethod.GET)
    User hello(@RequestHeader("name") String name, @RequestHeader("age") Integer age);

    @RequestMapping(value = "/hello3", method = RequestMethod.POST)
    String hello(@RequestBody User user);
}
```

- 注意：`@RequestParam`、`@RequestHeader`等可以指定参数名称的注解，它们的`value`不能少。在`SpringMVC`程序中，这些注解会根据参数来作为默认值，但是在`Feign`中绑定参数必须通过`value`属性来指明具体的参数名，不然会抛弃`IllegalStateException`异常，`value`属性不能为空。

## 继承特性

创建一个`maven`工程`hello-service-api`，里面定义可同时复用于服务端和客户端的接口。

- 定义一个接口`Hello-Service`

```java
@RequestMapping("/refactor")
public interface HelloService {

    @RequestMapping(value = "/hello4", method = RequestMethod.GET)
    String hello(@RequestParam("name") String name);

    @RequestMapping(value = "/hello5", method = RequestMethod.GET)
    User hello(@RequestHeader("name") String name, @RequestHeader("age") Integer age);

    @RequestMapping(value = "/hello6", method = RequestMethod.POST)
    String hello(@RequestParam User user);
}
```

- 在`hello-service`服务中的`pom.xml`文件中导入`hello-service-api`，定义一个`RefactorHelloController`来实现`HelloService`

```java
@RestController
public class RefactorHelloController implements HelloService {

    @Override
    public String hello(@RequestParam("name") String name) {
        return "Hello " + name;
    }

    @Override
    public User hello(@RequestHeader("name") String name, @RequestHeader("age") Integer age) {
        return new User(name, age);
    }

    @Override
    public String hello(@RequestBody User user) {
        return "Hello " + user.getName() + ", " + user.getAge();
    }
}
```

- 在服务消费方，引入`Hello-Service-api`，创建`RefactorHelloService`接口，继承`HelloService`接口

```java
@FeignClient(value = "hello-service")
public interface RefactorHelloService extends HelloService {
}
```

- 在`CustomerController`中，注入`RefactorHelloService`的实例，如下：

```java
@RestController
public class CustomerController {
    
    @Autowired
    private RefactorHelloService refactorHelloService;
    
    @RequestMapping(value = "/feign-consumer3", method = RequestMethod.GET)
    public String helloConsumer3() {
    StringBuilder sb = new StringBuilder();
    sb.append(refactorHelloService.hello("kuraki")).append("\n");
    sb.append(refactorHelloService.hello("kuraki", 26)).append("\n");
    sb.append(refactorHelloService.
              hello(new com.kuraki.feign.dto.User("kuraki", 26))).append("\n");
    return sb.toString();
    }
}
```

	## 服务降级配置

- 只需为`Feign`客户端的定义接口编写一个具体的接口实现类。如下：

```java
@Component
public class HelloServiceFallback implements HelloService {

    @Override
    public String hello() {
        return "error";
    }

    @Override
    public String hello(String name) {
        return "error name";
    }

    @Override
    public User hello(String name, Integer age) {
        return new User("lingbo", 26);
    }

    @Override
    public String hello(User user) {
        return "error user";
    }
}
```

- 在`@FeignClient`注解的`fallback`属性来指定对应的服务降级实现类。

```java
@FeignClient(name = "hello-service", fallback = HelloServiceFallback.class)
public interface HelloService {
    @RequestMapping("/hello")
    String hello();

    @RequestMapping(value = "/hello1", method = RequestMethod.GET)
    String hello(@RequestParam("name") String name);

    @RequestMapping(value = "/hello2", method = RequestMethod.GET)
    User hello(@RequestHeader("name") String name, @RequestHeader("age") Integer age);

    @RequestMapping(value = "/hello3", method = RequestMethod.POST)
    String hello(@RequestBody User user);
}
```

## 日志配置

- 在`application.properties`文件中使用`logging.level.<FeignClient>`的参数配置格式来开启指定`Feign`客户端的`DEBUG`日志，其中`<FeignClient>`为`Feign`客户端定义接口的完整路径。
- 其次，`Feign`客户端默认的`Logger.Level`对象定义为`NONE`级别，该级别不会记录任何`Feign`调用过程中的信息，可在启动类或者自定义一个配置类中修改级别，如下：

```java
@Configuration
public class LoggerFeignConfig {

    @Bean
    public Logger.Level feignLoggerLevel(){
        return Logger.Level.FULL;
    }
}
```

- 对于`Feign`的`Logger`级别主要有下面`4`类：
  - `NONE:`不记录任何信息。
  - `BASIC:`仅记录请求方法，`url`以及响应状态码和执行时间。
  - `HEADERS:`除了记录`BASIC`级别的信息之外，还会记录请求和响应的头信息。
  - `FULL:`记录所有请求与响应的明细，包括头信息、请求体、元数据等。