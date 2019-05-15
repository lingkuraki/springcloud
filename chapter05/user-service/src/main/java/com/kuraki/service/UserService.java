package com.kuraki.service;

import com.kuraki.bean.User;
import com.kuraki.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    public User getUserById(Long userId) {
        return userMapper.getUserById(userId);
    }
}
