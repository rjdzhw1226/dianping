package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient(){

        //start nginx.exe
        //taskkill /f /t /im nginx.exe
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.88.130:6379").setPassword("123321");
        //config.useSingleServer().setAddress("redis://127.0.0.1:6379");

        //返回配置类redisson，创建客户端
        return Redisson.create(config);


    }


}
