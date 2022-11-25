package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 使用redis实现分布式全局唯一id
 */

@Component
public class RedisIdWorker {

    //开始时间戳手动获取并设置的（2022/1/1 0:0:0）
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //同一秒内区分不同id的序列号的位数
    private static final int COUNT_BITS = 32;
    //redisTemplate
    private StringRedisTemplate stringRedisTemplate;
    //构造方法注入
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取id
     * @param keyPrefix     不同业务的用于生成id的标识
     */
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        //当前的时间
        LocalDateTime now = LocalDateTime.now();
        //当前的秒数
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //获取的时间戳
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // Redis自增长，有上限（2的64次方），而且标识id的序列号只有32位，
        // 所以如果项目一下子用了好几年而且业务量很大，就放不下了是吧
        // 不能用同一个key，可以加一个date标识解决，这样将来还可以统计当天生成的id（当然使用年月日都可以）
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date );

        // 3.数字拼接(时间戳32位，空出32个0用序列号补充（或运算）)
        return timestamp << COUNT_BITS | count;
    }
}
