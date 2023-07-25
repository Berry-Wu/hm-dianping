package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询blog相关用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();

        records.forEach(blog -> {
            // 查询用户
            this.queryBlogUser(blog);
            //查询blog是否被点赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录，无需判断是否点赞
            return;
        }
        Long userId = user.getId();
        //判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            //如果未点赞，则数据库对应点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //保存用户id到redis
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //如果已点赞，取消点赞，并数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //并从redis中移除用户id
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }


    /**
     * 获取点赞排行榜s
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 获取top点赞用户  zrange key 0 4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析其中的用户
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据id查询用户 WHERE id  IN ( 1014 , 1 )  ORDER BY FIELD(id,1014,1)
        List<UserDTO> userDTOS = userService
                .query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    /**
     * 保存笔记，同时推送
     *
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSave = save(blog);
        if (!isSave) {
            return Result.fail("保存笔记失败!");
        }
        // 查询笔记作者的所有粉丝
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId())
                .list();

        // 推送笔记id给所有粉丝
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 关注推送：分页查询收件箱
     *
     * @param minTime
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询该用户收件箱（之前存的key固定前缀 + 粉丝id），所以根据当前用户id就可以查询是否有关注的人发了笔记
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        //3.解析数据：blogId、minTime（时间戳）、offset、
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int off = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //3.1 获取id
            ids.add(Long.valueOf(tuple.getValue()));

            //3.2 获取score（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                off++;
            } else {
                minTime = time;
                off = 1;
            }
        }

        //4.根据id查询blog (SQL的in不能排序问题，手动指定排序为传入的ids)
        String idsStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", idsStr).last("ORDER BY FIELD(id," + idsStr + ")").list();

        for (Blog blog : blogs) {
            //4.1 查询发布该blog的用户信息
            queryBlogUser(blog);
            //4.2 查询当前用户是否给该blog点过赞
            isBlogLiked(blog);
        }

        //5.封装返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(off);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
