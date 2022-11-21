package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * 登录拦截器
 * 默认从上往下执行,手动设置顺序的话小的先执行
 *
 * 使用拦截器拦截需要登录才能操作的请求,判断并返回登录用户信息,刷新token时间.
 * 这样会导致,用户登陆后如果只访问不拦截的请求,token一样会过期
 * 可以写两个拦截器,一个拦截所有请求,刷新token,以及登录信息等,再写一个拦截器只拦截需要登录的请求,这样就完美了
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //添加拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",         //放行店铺信息(与用户是否登录无关)
                        "/voucher/**",      //放行优惠信息(与用户是否登录无关)
                        "/shop-type/**",    //放行店铺类型(与用户是否登录无关)
                        "/blog/hot",        //放行热点接口(与用户是否登录无关)
                        "/upload/**",       //放行上传接口(用于测试)
                        "/user/code",       //放行验证码接口
                        "/user/login"       //放行登录接口
                ).order(1);

        // token刷新的拦截器
        //默认从上往下执行,手动设置顺序的话小的先执行
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
    }
}
