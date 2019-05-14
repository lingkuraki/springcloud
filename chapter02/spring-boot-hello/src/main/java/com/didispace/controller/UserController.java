package com.didispace.controller;

import com.didispace.service.UserService;
import com.kuraki.bean.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @RequestMapping("/{id}")
    public User getUserById(@PathVariable("id")String userId){
        return userService.getUserById(userId);
    }
}
