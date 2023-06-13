package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <p>
 *  工具类
 * </p>
 *
 * @author 冬
 * @since 2022-09-27
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 缓存工具类
     * @param stringRedisTemplate
     */

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key , Object value , Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key , JSONUtil.toJsonStr(value), time , unit);
    }

    public void setRedisLogicalExpireTime(String key , Object value , Long time , TimeUnit unit){
        //设置过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key , JSONUtil.toJsonStr(redisData));
    }


    /**
     * 缓存穿透
     * @param keyPrefix
     * @param id
     * @param classType
     * @param nextBack
     * @param time
     * @param unit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T,ID> T query (String keyPrefix , ID id , Class<T> classType , Function<ID,T> nextBack ,Long time ,TimeUnit unit){
        //从缓存查
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断存在
        if (StrUtil.isNotBlank(json)){
            //存在，返回缓存的值
            return JSONUtil.toBean(json , classType);
        }
        //判断缓存命中的是否为空""
        if (json != null){

            return null;
        }
        //去数据库查
        T t = nextBack.apply(id);
        //不存在
        if (t == null) {
            //将空值存入Redis(注意存入为""，不为null)
            stringRedisTemplate.opsForValue().set(key ,"", RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //存在，写入Redis商铺缓存，JSON形式
        this.set(key , t , time , unit);

        return t;
    }


    /**
     * 缓存击穿
     * @param keyPrefix
     * @param id
     * @param classType
     * @param nextBack
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R , ID> R queryTimeOut(String keyPrefix , ID id , Class<R> classType , Function<ID , R> nextBack ,Long time ,TimeUnit unit){
        String key = keyPrefix + id;
        //从Redis查询商铺缓存，string形式
        String json = stringRedisTemplate.opsForValue().get(key);
        //不存在，返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //存在，反序列化json字符串为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r  =JSONUtil.toBean((JSONObject) redisData.getData(),classType);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回数据，设置的过期时间在当前时间之后
            return r;
        }
        //过期，进行缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = getLock(lockKey);
        //重建
        //判断是否拿到锁
        //取得，开启独立线程，根据id去数据库查
        if (lock){
            //二次判断
            if (expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            // 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                //重建缓存
                try {
                    //查数据库
                    R r1 = nextBack.apply(id);
                    //写入Redis
                    this.setRedisLogicalExpireTime(key ,r1 ,time ,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        // 未取得，返回过期信息
        return r;
    }

    private boolean getLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
