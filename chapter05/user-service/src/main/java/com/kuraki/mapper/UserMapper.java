package com.kuraki.mapper;

import com.kuraki.bean.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import tk.mybatis.mapper.common.Mapper;

public interface UserMapper extends Mapper<User> {

    @Select("SELECT * FROM user WHERE id = #{userId}")
    User getUserById(@Param("userId")Long userId);
}
