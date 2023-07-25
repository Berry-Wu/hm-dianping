package com.hmdp;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopData() {
        //1. 查询所有店铺信息
        List<Shop> shopList = shopService.list();
        //2. 将店铺进行分组,按照typeId
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3. 分批写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取类型id
            Long typeId = entry.getKey();
            // 获取同类型店铺的集合
            List<Shop> shops = entry.getValue();
            // 写入redis GEO数据  GEOADD key 经度 纬度 member
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                //将当前type的商铺都添加到locations集合中
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            //批量写入
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    public void testLogicalExpire(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L, shop, 10L, TimeUnit.SECONDS);
    }


    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(3L, 100L);
    }


    @Test
    public void testJOSNUtil(){
        Stu stu1 = new Stu("wzy", 12);
        Stu stu2 = new Stu("yzw", 23);
        List<Stu> list = new ArrayList<>();
        list.add(stu1);
        list.add(stu2);
        String jsonStr = JSONUtil.toJsonStr(list);
        System.out.println(jsonStr);
        String jsonStr1 = JSONUtil.toJsonStr(stu1);
        System.out.println(jsonStr1);

        for (Stu stu : JSONUtil.toList(jsonStr, Stu.class)) {
            System.out.println(stu);
        }

        System.out.println(JSONUtil.toBean(jsonStr1, Stu.class));

    }

}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Stu{
    String name;
    Integer age;
}
