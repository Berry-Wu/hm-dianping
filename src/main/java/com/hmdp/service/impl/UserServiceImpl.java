package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        //session.setAttribute("code", code);
        //4.保存验证码到redis
        //stringRedisTemplate.opsForValue().set("login:code:"+phone, code,2, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码(这里没有去真的发送验证码，想要改成邮箱验证参考瑞吉外卖部分)
        log.info("生成的验证码为：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //通过session获取验证码
        //Object cacheCode = session.getAttribute("code");
        //通过redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
        //2.验证码不一致则报错
        if (code == null || !cacheCode.equals(code)) {
            return Result.fail("输入的验证码错误");
        }

        //3.一致则先根据手机号查询用户
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.eq(User::getPhone, phone);
        User user = getOne(lqw);

        //4.用户不存在则创建,存在则继续执行程序
        if (user == null) {
            //创建逻辑封装成了一个方法
            user = createUserWithPhone(phone);
        }
        log.info("user信息：{}", user);

        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);

        //5.保存用户信息到session中
        //session.setAttribute("user", userDTO);

        //5.保存用户信息到redis
        //5.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //5.2 将UserDto对象转为hash存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        //这里将value全转换为String类型，因为userDto中的id是Long型，直接存入hash结构会报错
        userMap.forEach((key, value) -> {
            if (null != value) userMap.put(key, String.valueOf(value));
        });
        //5.3 存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设定token的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    /**
     * 用户签到逻辑
     *
     * @return
     */
    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();

        //拼接key
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + date;

        //获取今天是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //写入redis setbit key offset 0|1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 连续签到统计
     *
     * @return
     */
    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();

        //拼接key
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + date;

        //获取今天是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //获取本月截至今天为止所有的签到记录，返回的是一个十进制数字 BITFIELD key GET dayOfMonth 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if (result == null | result.isEmpty()) {
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == 0 || num == null) {
            return Result.ok(0);
        }
        //循环遍历，
        int count = 0;
        while (true){
            if ((num & 1) == 0){
                break;
            }else {
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName("游客" + phone);
        //2.保存用户信息
        save(user);
        return user;
    }
}
