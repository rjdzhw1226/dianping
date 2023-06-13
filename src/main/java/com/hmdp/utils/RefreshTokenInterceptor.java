package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 此处为一级拦截器，拦截所有作token刷新
 * ！注意
 * 登录拦截器在Bean初始化之前它就执行了，
 * 它是无法获取SpringIOC容器中的内容的，所以不能直接配置Bean的注解@Component
 * 只能让拦截器执行的时候实例化拦截器Bean，在拦截器配置类SpringMvcConfig里面先实例化拦截器
 * 然后再获取
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /**
         *  获取session
         *  HttpSession session = request.getSession();
         *  取session中的信息
         *  Object user = session.getAttribute("user");
         */

        //获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //调用检查sign方法,不一致直接拦截
        String s= (String) stringRedisTemplate.opsForHash().entries("sysSecretKey").get("sysSecretKey");
        if (SignGetAndCheck.checkSign(s,token)) {
            return true;
        }

        //基于token获取Redis的用户信息，hash方法
        Map<Object, Object> userDTOMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        //判断用户是否存在
        if (userDTOMap.isEmpty()){
            return true;
        }
        //将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userDTOMap, new UserDTO(), false);
        //存在，保存用户信息到ThreadLocal，不用每次获取user信息时都从Redis中取，也确保多线程时数据的一致性
        /**
         * UserHolder.saveUser((UserDTO) user);
         */
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token , RedisConstants.LOGIN_USER_TTL , TimeUnit.SECONDS );
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
