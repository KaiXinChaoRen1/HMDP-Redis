package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁
 */
public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //k的前缀标识
    private static final String KEY_PREFIX = "lock:";
    //v的前缀标识
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-"; //设为true去掉uuid里的‘-’


    /**
     * 操作lua脚本的实现类，提前定义好，因为如果等每次释放再读取产生io操作效率低
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取锁
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示作为v
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);    //避免返回值是null拆箱出现空指针
    }

//    /**
//     * 释放锁：要避免因为获取锁之后业务执行实现太长超过锁的过期时间，然后其他线程获取锁，而本线程业务完毕后释放的将是别人的锁
//     */
//    @Override
//    public void unlock() {
//        // 获取线程标示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取锁中的标示
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 判断标示是否一致
//        if (threadId.equals(id)) {
//            // 再释放锁，防止释放别人的锁 （注意，判断与真正释放不是原子操作，依旧有问题，因为是分布式的加synchronized也没用，应该使用redis的‘事务’）
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

    /**
     * 释放锁2.0，利用lua脚本原子的执行redis操作
     *      还有坑！？(非必须,根据业务扩展)
     *          不可重入
     *          不可重试
     *          超时释放
     *          主(写)从(读)一致问题
     *                  使用Redisson解决
     */
    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,                                          //script
                Collections.singletonList(KEY_PREFIX + name),           //keys
                ID_PREFIX + Thread.currentThread().getId());      //args
    }

}
