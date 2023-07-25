package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author wzy
 * @creat 2023-07-16-17:12
 */
@Component
public class RedisIdWorker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //设置起始时间:2022.01.01 00:00:00
    public static final Long BEGIN_TIMESTAMP = 1640995200L;
    //序列号长度
    public static final Long COUNT_BIT = 32L;

    public long nextId(String keyPrefix) {
        //生成时间戳
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        // 先获取当天日期，精确到天
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //拼接并返回
        long id = timeStamp << COUNT_BIT | count; // 将timeStamp左移32位，然后使用或运算拼接redis序列号
        return id;
    }

//    public static void main(String[] args) {
//        //设置一下起始时间，时间戳就是起始时间与当前时间的秒数差
//        LocalDateTime tmp = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        System.out.println(tmp.toEpochSecond(ZoneOffset.UTC));
//        //结果为1640995200L
//    }
}
