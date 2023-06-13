package com.hmdp.utils.Lock;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class simpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public simpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    //线程标识
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    private static final DefaultRedisScript<Long>UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(long.class);
    }

    @Override
    public boolean tryLock(long timeoutseconds) {

        /**
         * 获取线程
         * Thread thread = Thread.currentThread();
         */

        //获取线程名称
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutseconds, TimeUnit.SECONDS);
        /*Boolean.TRUE.equals(success);*/
        //返回
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
       //调用脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT , Collections.singletonList(KEY_PREFIX + name) ,ID_PREFIX + Thread.currentThread().getId());
        /*
        //判断当前要删除的锁一致性
        //获取标识
        String s1 = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中标识
        String s2 = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断一致性
        if (s1.equals(s2)) {
            //一致释放
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
        */
    }
}
