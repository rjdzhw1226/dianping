package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 此处为二级拦截器，用来判断是否拦截
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){ this.stringRedisTemplate = stringRedisTemplate;}

    public LoginInterceptor() {
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取请求头的token
//        String token = request.getHeader("authorization");//前端页面定义的请求头
//        if (StrUtil.isBlank(token)){
//            //不存在拦截,返回401状态码
//            response.setStatus(401);
//            return false;
//        }
//        //2.基于token获取redis中的用户
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
//        //判断是否需要拦截
//        if (userMap.isEmpty()) {
//            //用户不存在，拦截
//            response.setStatus(401);
//            //返回无权限状态码
//            return false;
//        }
//
//        //5.将查询到的Hash数据转为UserDTO对象
//        //将map中的数据自动填充到对象中，false是忽视错误，有异常直接往外抛就行了
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        //6.存在，保存用户信息到TreadLocal中
//        UserHolder.saveUser(userDTO);
//        //7.刷新token的有效期
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,30, TimeUnit.MINUTES);
//        //有用户，放行
//        return true;

        //判断是否需要拦截
        if (UserHolder.getUser() == null) {
            //用户不存在，拦截
            response.setStatus(401);
            //返回无权限状态码
            return false;
        }
        //有用户，放行
        return true;
    }
}
