package com.kuraki.service;

import org.springframework.cloud.netflix.feign.FeignClient;

@FeignClient(value = "hello-service")
public interface RefactorHelloService extends HelloService {
}
