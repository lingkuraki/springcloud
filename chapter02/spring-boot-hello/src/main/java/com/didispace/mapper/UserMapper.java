package com.didispace.mapper;

import com.kuraki.bean.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import tk.mybatis.mapper.common.Mapper;

import java.util.Optional;

public interface UserMapper extends Mapper<User> {
    @Select("SELECT * FROM user WHERE user_id = #{userId}")
    Optional<User> getUserById(@Param("userId") Long userId);
}
