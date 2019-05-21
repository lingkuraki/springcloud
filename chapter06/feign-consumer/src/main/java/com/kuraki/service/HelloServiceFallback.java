package com.kuraki.service;

import com.kuraki.entity.User;
import org.springframework.stereotype.Component;

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
