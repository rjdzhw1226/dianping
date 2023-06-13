package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long id = UserHolder.getUser().getId();
        String key = "follows:"+id;
        //判断到底为关注还是取关
        if(isFollow){
            //关注
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            boolean save = save(follow);
            if(save){
                //把关注用户放入redis
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            //取关 delete from tb_follow where userId = ? and follow_user_id = ?
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", id).eq("follow_user_id", followUserId));
            if(remove){
                //移除关注的用户集合
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取用户id
        Long id = UserHolder.getUser().getId();
        //查询是否关注 select * from tb_follow where userId = ? and follow_user_id = ?
        Integer count = query().eq("user_id", id).eq("follow_user_id", followUserId).count();
        //判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户
        Long id1 = UserHolder.getUser().getId();
        String Key = "follows:"+id1;
        //求交集
        String Key2 = "follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(Key,Key2);
        if(intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> userDto = userService.listByIds(collect)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDto);
    }
}
