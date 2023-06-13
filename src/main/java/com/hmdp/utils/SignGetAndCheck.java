package com.hmdp.utils;

import com.hmdp.service.IUserService;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

public class SignGetAndCheck {
    @Resource
    private IUserService iUserService;

    private static StringRedisTemplate stringRedisTemplate;
    public static String getSign(Map<String , String> params ,String sysSecretKey){

        String phone = params.get("phone");
        String code = params.get("Code");
        String password = params.get("password");
        String secretTextget = MD5Utils.md5(phone + code + password + sysSecretKey);

        return secretTextget;
    }

    public static Boolean checkSign(String sysSecretKey, String token){
        Boolean flag = false;
        HashMap<String, String> map = new HashMap<>();

        //取登录时生成的sign
        Map<Object, Object> Sign = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_SIGN_CHECK_SAVE + token);
        String secretSign = (String) Sign.get("secret");
        //取用户信息
        //iUserService.loginInformationGet()
        Map<Object, Object> User = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_SAVE + token);
        map.put("phone",(String) User.get("phone"));
        map.put("Code",(String) User.get("code"));
        map.put("password",(String) User.get("password"));

        System.out.println("现在的sign-->>" + secretSign);
        System.out.println("验证的sign-->>" + getSign(map, sysSecretKey));

        //校验sign
        if(secretSign.equals(getSign(map, sysSecretKey))){
            flag = true;
        }
        return flag;
    }
}
