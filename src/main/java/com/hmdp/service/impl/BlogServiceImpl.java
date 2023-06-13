package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //查询Blog
        Blog byId = getById(id);
        if(byId == null){
           return Result.fail("笔记不存在！");
        }
        //查询Blog关联的用户
        queryBlogUser(byId);
        //查询是否被点赞过
        isBlogLiked(byId);
        return Result.ok(byId);
    }

    @Override
    public void isBlogLiked(Blog byId) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //未登录，无需查询点赞
            return;
        }
        //获取登录用户
        Long userId = user.getId();
        //判断当前用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY+byId.getId();
        //Boolean member = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        //byId.setIsLike(BooleanUtil.isTrue(member));
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        byId.setIsLike(score != null);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5点赞用户 zrange key 0 4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(range == null || range.isEmpty()){
          return Result.ok(Collections.emptyList());
        }
        //解析出其中的用户Id
        List<Long> ids = range.stream().map(item -> {
            return Long.valueOf(item);
        }).collect(Collectors.toList());
        String idStr = StrUtil.join(",",ids);
        //根据用户id查询用户 where id in (5,1) order by field (id,5,1)
//        List<UserDTO> collect = userService.listByIds(ids)
//                .stream()
//                .map(item -> BeanUtil.copyProperties(item, UserDTO.class))
//                .collect(Collectors.toList());
        List<UserDTO> collect = userService.query().in("id",ids).last("ORDER BY FILED(id,"+ idStr +")").list()
                .stream()
                .map(item -> BeanUtil.copyProperties(item, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(collect);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if(!save){
            return Result.fail("新增笔记失败");
        }
        //查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = RedisConstants.FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long id = UserHolder.getUser().getId();
        //查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = RedisConstants.FEED_KEY +id;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>();
        long minTime = 0;
        int count = 1;
        //解析数据 blogId,score(时间戳),offset
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取id
            String idStr = typedTuple.getValue();
            ids.add(Long.valueOf(idStr));
            //获取时间戳
            long l = typedTuple.getScore().longValue();
            //获取查询偏移量
            if(l == minTime){
                count++;
            } else{
                minTime = l;
                count = 1;
            }
        }
        //根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FILED(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            //查询Blog关联的用户
            queryBlogUser(blog);
            //查询是否被点赞过
            isBlogLiked(blog);
        }

        //封装返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(count);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        //Boolean member = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //if(BooleanUtil.isFalse(member))
        if(score == null){
            //未点赞
            //+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //保存用户到redis的set集合
            if(isSuccess){
                //stringRedisTemplate.opsForSet().add(key,userId.toString());
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //已点赞
            //-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //将用户从redis的set集合中移除
            //stringRedisTemplate.opsForSet().remove(key,userId.toString());
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());

        }
        return Result.ok();
    }

    private void queryBlogUser(Blog byId) {
        Long userId = byId.getUserId();
        User user = userService.getById(userId);
        byId.setName(user.getNickName());
        byId.setIcon(user.getIcon());
    }
}
