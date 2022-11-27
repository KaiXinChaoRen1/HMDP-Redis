package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置
 *      基于redis实现的Java 分布式工具集
 *          其中分布式锁的原理：
 *              1.可重入原理：利用hash结构，增加一个重入次数，获取锁时重入次数+1，释放锁重入次数-1，当重入次数==0的时候，删除
 *              2.重试机制原理：利用信号量和发布订阅机制实现，等待其他锁释放发送的信号量，而不是直接重试，减少cpu浪费
 *              3.超时时间设为-1：使用WatchDog，10秒自动续约一次（如果不设置超时时间，服务宕机则不能解锁，使用续约方式如果服务宕机，就不会再续约，到点释放）
 *              4.主从一致原理：简单粗暴，多个redis节点都作为主节点读写，只有同时成功（可以设置多少个成功才算成功）才算成功（连锁）
 *                      需要配置多个RedisClient，在创建锁的时候使用先用getLock获取多个锁，再用getMultiLock（）把多个锁传入
 *
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
