package com.kuraki.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced // 必须改注解修饰的restTemplate，才可以以服务名访问
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

}
