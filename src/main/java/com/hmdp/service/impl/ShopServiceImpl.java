package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //工具类实现：缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿:互斥锁
        //Shop shop = queryWithMutex(id);

        //缓存击穿:逻辑过期
        //Shop shop = queryWithExpire(id);

        //工具类实现：缓存击穿
        //Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * queryById：解决缓存穿透问题
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        //1.从Redis中查询商铺缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) { //isNotBlank中如果是null、""都会返回false
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //[new]判断命中的是否为空值。
        if (shopJson != null) {
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop = getById(id);

        //5.不存在，返回错误
        if (shop == null) {
            //[new]将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    /**
     * queryById：解决缓存击穿：使用互斥锁
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        //1.从Redis中查询商铺缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) { //isNotBlank中如果是null、""都会返回false
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //[缓存穿透]判断命中的是否为空值。
        if (shopJson != null) {
            return null;
        }

        //[缓存击穿]获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //[缓存击穿]判断是否获取成功
            if (!isLock) {
                //[缓存击穿]失败，则休眠重试
                Thread.sleep(50);
                //重试就是重新查询缓存，所以这里直接使用递归
                return queryWithMutex(id);
            }

            //4.成功，根据id查询数据库
            shop = getById(id);

            //模拟重建的延时
            Thread.sleep(200);

            //5.不存在，返回错误
            if (shop == null) {
                //[缓存穿透]将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //[缓存击穿]释放互斥锁
            unlock(lockKey);
        }

        //7.返回
        return shop;
    }

    //这里需要声明一个线程池，因为下面需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * queryById：解决缓存击穿：使用逻辑过期
     *
     * @param id
     * @return
     */
    public Shop queryWithExpire(Long id) {
        //1.从Redis中查询商铺缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //不存在直接返回null
            return null;
        }
        //3.命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //4.判断是否过期
        if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
            //4.1如果未过期则直接返回数据
            return shop;
        }

        //4.2 过期，则需要缓存重建
        //5.缓存重建
        //5.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2 判断是否获取锁成功
        if (isLock) {
            //成功则开启独立线程，并返回旧的店铺信息
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存(这里过期时间设置得短，为了便于测试)
                    this.saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
            //直接返回商铺信息
            return shop;
        }
        //未获取到锁，直接返回商铺信息
        return shop;
    }

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

    //[缓存击穿-逻辑过期] 缓存重建：封装过期时间，并写入redis
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 附近店铺实现
     *
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            //不需要坐标查询，直接数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;

        //3.查询redis、按照距离排序、分页:shopId、distance
        //GEOSEARCH key FROMLONLAT x y BYRADIUS 5000 m WITHDIST
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y), //搜索的圆心
                new Distance(5000), //搜索半径
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 判断集合的长度是否小于等于下一页的from，如果符合则说明没有下一页的数据了
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        //4.1 截取从from到end的内容
        List<Long> ids = new ArrayList<>(list.size());//保存查询到的店铺id，后续根据id查询shop
        Map<String, Distance> distanceMap = new HashMap<>(list.size());//保存id和距离对
        list.stream().skip(from).forEach(result -> {
            //4.2 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5.根据id查询shop
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idsStr + ")").list();
        //遍历shop，将距离set到shop中
        for (Shop shop : shops) {
            log.info("距离：{}",distanceMap.get(shop.getId().toString()).getValue());
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
