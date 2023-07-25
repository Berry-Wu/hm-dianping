package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.api.R;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author wzy
 * @creat 2023-07-16-12:09
 */
@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //方法1：将**任意**Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 方法2：将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 方法3：根据指定的Key查询缓存，并反序列化为指定类型，利用**缓存空值**的方式解决**缓存穿透**问题
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //1.从Redis中查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) { //isNotBlank中如果是null、""都会返回false
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }

        //[new]判断命中的是否为空值。
        if (json != null) {
            return null;
        }

        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        //5.不存在，返回错误
        if (r == null) {
            //[new]将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入Redis
        this.set(key, r, time, unit);
        //7.返回
        return r;
    }

    // 方法4：根据指定的Key查询缓存，并反序列化为指定类型，需要利用**逻辑过期**解决**缓存击穿**问题
    //这里需要声明一个线程池，因为下面需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //[缓存击穿-互斥锁]：获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //避免返回值为null，我们这里使用了BooleanUtil工具类
        return BooleanUtil.isTrue(flag);
    }

    //[缓存击穿-互斥锁]：释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        //1.从Redis中查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)){
            //不存在直接返回null
            return null;
        }
        //3.命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //4.判断是否过期
        if (LocalDateTime.now().isBefore(redisData.getExpireTime())){
            //4.1如果未过期则直接返回数据
            return r;
        }

        //4.2 过期，则需要缓存重建
        //5.缓存重建
        //5.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2 判断是否获取锁成功
        if (isLock){
            //成功则开启独立线程，并返回旧的店铺信息
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存:查询数据库
                    R r1 = dbFallback.apply(id);
                    //重建缓存:写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
            //直接返回商铺信息
            return r;
        }
        //未获取到锁，直接返回商铺信息
        return r;
    }

}
