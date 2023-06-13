package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 冬
 * @since 2022-09-28
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码功能
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendcode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合
            return Result.fail("手机号格式不合法！");
        }
        //符合
        String code = RandomUtil.randomNumbers(6);
        //保存code到Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone , code , RedisConstants.LOGIN_CODE_TTL , TimeUnit.MINUTES);
        //保存code到session
        // session.setAttribute("code" , code);
        //记录日志
        log.debug("验证码发送成功，验证码{}",code);
        //返回前端标识语句
        return Result.ok();
    }

    /**
     * 系统密匙 由时间戳生成
     * @param year
     * @param month
     * @param dayOfMonth
     * @param hour
     * @param min
     * @param sec
     * @return
     */
    @Override
    public String secretKey(int year,int month,int dayOfMonth,int hour,int min,int sec){
        LocalDateTime time = LocalDateTime.of(year, month, dayOfMonth, hour, min, sec);
        long BEGIN_TIME = time.toEpochSecond(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeSign = second - BEGIN_TIME;
        String s = String.valueOf(timeSign);
        HashMap<String, String> map = new HashMap<>();
        map.put("sysSecretKey",s);
        stringRedisTemplate.opsForHash().putAll("sysSecretKey",map);
        return s;
    }

    /**
     * 签到
     * @return
     */
    @Override
    public Result sign() {
        //获取当前登录用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + id + format;
        //计算今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis bitmap SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计连续签到天数
     * @return
     */
    @Override
    public Result signCount() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:"+ userId + format;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至今天为止的所有签到记录，返回的是一个十进制的数字 BITFIELD sign：5：202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            //没有任何签到结果
            return  Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        //循环遍历
        int count = 0;
        while (true){
            //让这个数字与1做与运算，得到数字的最后一个bit位   //判读这个bit位是否为0
            if ((num & 1) == 0){
                //如果为0，说明未签到，结束
                break;
            }else {
                //如果不为0，说明已签到，计数器加1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            //不符合
            return Result.fail("手机号格式不合法!");
        }

        /**
         * 从session取cacheCode并校验验证码code
         * Object cacheCode = session.getAttribute("code");
         * String code = loginForm.getCode();
         */

        //从Redis中获取验证码并校验
        //后台 存的
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        //前端 用户输入的
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            //不符合
            return Result.fail("验证码为空或过期或不一致");
        }
        //都一致查询用户存在与否
        User user = query().eq("phone" , phone).one();
        //不存在创造新用户并保存
        if(user == null){
            user = createUser(phone);
        }

        /**
         * 存在放行
         * 保存用户信息到session中
         *  session.setAttribute("user" , BeanUtil.copyProperties(user , UserDTO.class));
         */

        //保存用户信息到Redis中
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //做签名生成 作为每次登录生成的
        String phoneMD = loginForm.getPhone();
        String CodeMD = loginForm.getCode();
        String passwordMD = loginForm.getPassword();
        String secretText = MD5Utils.md5((phoneMD + CodeMD + passwordMD + secretKey(2022,1,1,0,0,0)));

        //存用户登录信息
        Map<String, String> MapUser = new HashMap<>();
        MapUser.put("phone",phoneMD);
        MapUser.put("code",CodeMD);
        MapUser.put("password",passwordMD);

        //将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO ,new HashMap<>(),
                //此工具支持自定义返回值的类型，指定值为String类型
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName ,fieldValue) -> fieldValue.toString())
        );

       /* 自己手动存储，未验证
        String id = userDTO.getId().toString();
        String nickName = userDTO.getNickName();
        String icon = userDTO.getIcon();

        Map<String, String> Map = new HashMap<>();
        Map.put("id",id);
        Map.put("nickName",nickName);
        Map.put("icon",icon);*/
        Map<String, String> MapSecret = new HashMap<>();
        MapSecret.put("secret",secretText);
        //存储
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        String signKey = RedisConstants.LOGIN_SIGN_CHECK_SAVE + token;
        String userKey = RedisConstants.LOGIN_USER_SAVE +token;

        //存用户信息
        stringRedisTemplate.opsForHash().putAll(tokenKey , userDTOMap);
        //存签名
        stringRedisTemplate.opsForHash().putAll(signKey , MapSecret);
        stringRedisTemplate.opsForHash().putAll(userKey,MapUser);
        //过期时间
        stringRedisTemplate.expire(tokenKey , RedisConstants.LOGIN_USER_TTL , TimeUnit.SECONDS);
        //sign过期时间
        stringRedisTemplate.expire(signKey , RedisConstants.LOGIN_SIGN_CHECK_SAVE_TLL , TimeUnit.SECONDS);
        stringRedisTemplate.expire(userKey, RedisConstants.LOGIN_USER_SAVE_TLL,TimeUnit.SECONDS);
        //返回token
        return Result.ok(token);
    }


    public static void beginTime(String date){
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date parse = simpleDateFormat.parse(date);
            System.out.println(parse);
            /*LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
            long l = time.toEpochSecond(ZoneOffset.UTC);*/
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建用户
     * @param phone
     * @return
     */
    private User createUser(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存user
        save(user);
        return user;
    }
}
