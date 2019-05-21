package com.kuraki.service;

import com.kuraki.feign.service.HelloService;
import org.springframework.cloud.netflix.feign.FeignClient;

@FeignClient(value = "hello-service")
public interface RefactorHelloService extends HelloService {
}
