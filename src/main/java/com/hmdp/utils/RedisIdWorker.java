package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIME = 1640995200L;

    /**
     * 序列号位数
     */
    private static final long INCREMENTID_SIZE = 32;

    private StringRedisTemplate stringRedisTemplate;

    //注入
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate= stringRedisTemplate;
    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long l = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("seconds =" + l);
//    }

    /**
     * 生成全局唯一ID
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix){

        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeSign = second - BEGIN_TIME;

        //生成序列号
        //用日期来区分订单号
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        //自增ID
        Long incrementID = stringRedisTemplate.opsForValue().increment("incr :" + keyPrefix + ":" + date);

        //让位拼接
        long finTimesign = timeSign << INCREMENTID_SIZE | incrementID;

        //拼接返回
        return finTimesign;
    }
}
