package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        //首先根据key从redis中查询缓存
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String shopTypes = stringRedisTemplate.opsForValue().get(key);
        //如果缓存不为空，则将返回的json字符串转换为类型列表，并且返回查询结果
        if (StrUtil.isNotBlank(shopTypes)){
            List<ShopType> typeList = JSONUtil.toList(shopTypes, ShopType.class);
            return Result.ok(typeList);
        }
        //如果为空，则进行sql查询，获取类型列表
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList.isEmpty()){
            return Result.fail("分类信息为空");
        }
        //如果sql查询不为空，则将查询的列表转化为json字符串，将其存入redis中
        String jsonStr = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
        return Result.ok(typeList);
    }
}
