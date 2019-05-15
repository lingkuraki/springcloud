package com.kuraki.controller;

import com.kuraki.bean.User;
import com.kuraki.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Future;

@RestController
public class ConsumerController {

    @Autowired
    private UserService userService;

    @RequestMapping("/hystrix-consumer/{id}")
    public User getUserById(@PathVariable("id") Long userId) {
        return userService.getUserById(userId);
    }

    @RequestMapping("/hystrix-consumer/asyn/{id}")
    public Future<User> getUserByIdAsyn(@PathVariable("id") Long userId) {
        return userService.getUserByIdAsyn(userId);
    }
}
