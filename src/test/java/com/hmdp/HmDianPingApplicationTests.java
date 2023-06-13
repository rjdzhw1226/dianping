package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSave() throws InterruptedException {

        shopService.saveShopToRedis(1L , 10L);

    }

    @Test
    void loadShopData(){
        //��ѯ������Ϣ
        List<Shop> list = shopService.list();
        //���̷���
        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //����д��redis
        for (Map.Entry<Long, List<Shop>> longListEntry : collect.entrySet()) {
            //��ȡ����id
            Long key = longListEntry.getKey();
            String Key1 = "shop:geo"+key;
            //��ȡͬ���͵��̵ļ���
            List<Shop> value = longListEntry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //д��redis��γ��
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(Key1,locations);
        }
    }

}
