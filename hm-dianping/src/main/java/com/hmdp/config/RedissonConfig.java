package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置
 *      基于redis实现的Java分布式工具集
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        //单节点,(可以使用useClusterServers()添加集群地址)
        config.useSingleServer().setAddress("redis://43.143.216.21:6379").setPassword("Aa@111111");
        return Redisson.create(config);
    }
}
