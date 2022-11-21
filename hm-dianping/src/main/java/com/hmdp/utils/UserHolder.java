package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 每一个进入tomcat的请求都是一个独立的线程?
 * 使用ThreadLocal为每一个用户线程保存用户信息
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    //存
    public static void saveUser(UserDTO user){
        tl.set(user);
    }
    //取
    public static UserDTO getUser(){
        return tl.get();
    }
    //删
    public static void removeUser(){
        tl.remove();
    }
}
