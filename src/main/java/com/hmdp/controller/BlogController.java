package com.hmdp.controller;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @GetMapping("{id}")
    public Result queryById(@PathVariable Long id){
        return blogService.queryBlogById(id);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable Long id){
        return blogService.queryBlogLikes(id);
    }


    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {

        return blogService.queryHotBlog(current);
    }

    /**
     * 用户页面下的笔记分页查询
     * @param id
     * @param current
     * @return
     */
    @GetMapping("/of/user")
    public Result queryUserBlog(@RequestParam("id") Long id, @RequestParam(value = "current", defaultValue = "1")Integer current){
        Page<Blog> pageInfo = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);

        LambdaQueryWrapper<Blog> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Blog::getUserId, id);

        blogService.page(pageInfo, lqw);
        List<Blog> records = pageInfo.getRecords();
        return Result.ok(records);
    }

    /**
     * 分页查询收件箱
     * @param minTime
     * @param offset
     * @return
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam("lastId") Long max,@RequestParam(value = "offset", defaultValue = "0")Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }


}
