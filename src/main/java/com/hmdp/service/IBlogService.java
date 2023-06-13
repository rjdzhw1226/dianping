package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    public Result queryBlogById(Long id);

    public Result likeBlog(Long id);

    public void isBlogLiked(Blog blog);

    public Result queryBlogLikes(Long id);

    public Result saveBlog(Blog blog);

    public Result queryBlogOfFollow(Long max, Integer offset);
}
