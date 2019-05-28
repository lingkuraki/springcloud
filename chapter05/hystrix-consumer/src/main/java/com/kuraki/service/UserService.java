package com.kuraki.service;

import com.kuraki.bean.User;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCollapser;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import com.netflix.hystrix.contrib.javanica.annotation.ObservableExecutionMode;
import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheKey;
import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheResult;
import com.netflix.hystrix.contrib.javanica.command.AsyncResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import rx.Observable;

import java.util.List;
import java.util.concurrent.Future;

@Service
public class UserService {

    @Autowired
    private RestTemplate restTemplate;

    // 同步执行
    @HystrixCommand(commandKey = "getUserById", groupKey = "UserGroup", threadPoolKey = "getUserByIdThread")
    public User getUserById(Long id) {
        return restTemplate.getForObject("http://localhost:8090/users/{1}", User.class, id);
    }

    // 异步执行
    @HystrixCommand(fallbackMethod = "defaultUser")
    public Future<User> getUserByIdAsyn(final Long id) {
        return new AsyncResult<User>() {
            @Override
            public User invoke() {
                return restTemplate.getForObject("http://localhost:8090/users/{1}", User.class, id);
            }
        };
    }

    public User defaultUser(){
        return new User();
    }

    @HystrixCommand(observableExecutionMode = ObservableExecutionMode.EAGER)
    public Observable<User> getUserByIdObserval(Long id) {
        return Observable.create(observer -> {
            try {
                if (!observer.isUnsubscribed()) {
                    User user = restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
                    observer.onNext(user);
                    observer.onCompleted();
                }
            } catch (Exception e) {
                observer.onError(e);
            }
        });
    }

    /**
     * 请求缓存
     */
    @CacheResult(cacheKeyMethod = "getUserByIdCacheKey")
    @HystrixCommand
    public User findUserById(@CacheKey("id") Long id) {
        return restTemplate.getForObject("http://user-service/users/{1}", User.class, id);
    }

    private Long getUserByIdCacheKey(Long id) {
        return id;
    }

    /**
     * 请求合并
     */
    @HystrixCollapser(batchMethod = "findAll", collapserProperties = {
            @HystrixProperty(name = "timeDelayInMilliseconds", value = "100")
    })
    public User find(Long id) {
        return null;
    }

    @HystrixCommand
    public List<User> findAll(List<Long> ids) {
        return restTemplate.getForObject("http://user-service/users?id={1}", List.class, StringUtils.join(ids, ","));
    }

}
