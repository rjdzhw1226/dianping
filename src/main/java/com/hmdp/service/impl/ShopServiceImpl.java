package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 冬
 * @since 2022-09-16
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 商铺缓存
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //原始代码
        /*String key = RedisConstants.CACHE_SHOP_KEY + id;
        //从Redis查询商铺缓存，string形式
        String shopCacheJson = stringRedisTemplate.opsForValue().get(key);
        //存在，直接返回，Bean形式
        if (StrUtil.isNotBlank(shopCacheJson)) {
            Shop shop = JSONUtil.toBean(shopCacheJson, Shop.class);
            return Result.ok(shop);
        }
        //判断查到的是不是空值(不是null 而是"")
        if (shopCacheJson.equals("")//此处写成 shopCacheJson != null 效果一样) {
            //返回错误信息
            return Result.fail("没有这个店铺信息！");
        }
        //不存在，根据id去数据库查
        Shop shop = getById(id);
        //数据库不存在，返回错误信息
        if (shop == null) {
            //将空值存入Redis(注意存入为""，不为null)
            stringRedisTemplate.opsForValue().set(key ,"", RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
            //返回错误信息
            return Result.fail("查询商铺信息不存在，错误！");
        }
        //存在，写入Redis商铺缓存，JSON形式
        stringRedisTemplate.opsForValue().set(key ,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL , TimeUnit.MINUTES);
        //返回
        return Result.ok(shop);*/

        //使用工具类的方法解决缓存穿透
        //Shop shop = cacheClient.query(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //使用工具类的方法解决缓存击穿
        Shop shop = cacheClient.queryTimeOut(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalTimeOut(id);
        if (shop == null) {
            return Result.fail("店铺信息不存在！");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期
     * @param id
     * @return
     */
    public Shop queryWithLogicalTimeOut(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //从Redis查询商铺缓存，string形式
        String shopCacheJson = stringRedisTemplate.opsForValue().get(key);
        //不存在，返回null
        if (StrUtil.isBlank(shopCacheJson)) {
            return null;
        }
        //存在，反序列化json字符串为对象
        RedisData redisData = JSONUtil.toBean(shopCacheJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();

        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回数据，设置的过期时间在当前时间之后
            return shop;
        }
        //过期，进行缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = getLock(lockKey);
        //重建
        //判断是否拿到锁
        //取得，开启独立线程，根据id去数据库查
        if (lock){
            //二次判断
            if (expireTime.isAfter(LocalDateTime.now())) {
                return shop;
            }
            // 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                //重建缓存
                try {
                    this.saveShopToRedis(id ,1800L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        // 未取得，返回过期信息
        return shop;
    }

    /**
     * 互斥锁
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //从Redis查询商铺缓存，string形式
        String shopCacheJson = stringRedisTemplate.opsForValue().get(key);
        //存在，直接返回，Bean形式
        if (StrUtil.isNotBlank(shopCacheJson)) {
            Shop shop = JSONUtil.toBean(shopCacheJson, Shop.class);
            return shop;
        }
        //判断查到的是不是空值(不是null 而是"")
        if (shopCacheJson.equals("")/*此处写成 shopCacheJson != null 效果一样*/) {
            //返回错误信息
            return null;
        }
        //缓存重建
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean lock = getLock(lockKey);
            //判断是否获取成功
            if (!lock){
                //失败休眠，重试
                Thread.sleep(60);
                //递归
                return queryWithMutex(id);
            }
            //获取成功，根据id去数据库查
            shop = getById(id);
            //数据库不存在，返回错误信息
            if (shop == null) {
                //将空值存入Redis(注意存入为""，不为null)
                stringRedisTemplate.opsForValue().set(key ,"", RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //存在，写入Redis商铺缓存，JSON形式
            stringRedisTemplate.opsForValue().set(key ,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL , TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(lockKey);
        }
        //返回
        return shop;
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //从Redis查询商铺缓存，string形式
        String shopCacheJson = stringRedisTemplate.opsForValue().get(key);
        //存在，直接返回，Bean形式
        if (StrUtil.isNotBlank(shopCacheJson)) {
            Shop shop = JSONUtil.toBean(shopCacheJson, Shop.class);
            return shop;
        }
        //判断查到的是不是空值(不是null 而是"")
        if (shopCacheJson.equals("")/*此处写成 shopCacheJson != null 效果一样*/) {
            //返回错误信息
            return null;
        }
        //不存在，根据id去数据库查
        Shop shop = getById(id);
        //数据库不存在，返回错误信息
        if (shop == null) {
            //将空值存入Redis(注意存入为""，不为null)
            stringRedisTemplate.opsForValue().set(key ,"", RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //存在，写入Redis商铺缓存，JSON形式
        stringRedisTemplate.opsForValue().set(key ,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL , TimeUnit.MINUTES);
        //返回
        return shop;
    }

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean getLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     * @return
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 设置热点缓存
     * @param id
     * @param expireSecond
     */
    public void saveShopToRedis(Long id ,Long expireSecond) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        //写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id , JSONUtil.toJsonStr(redisData));
    }

    /**
     * 更新
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {

        //先数据库后缓存更安全高效
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        //返回成功信息
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //是否根据坐标查询
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int offset = (current - 1) + SystemConstants.DEFAULT_PAGE_SIZE;
        int limit = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis按距离排序
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(limit));
        //解析id
        if(search == null){
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        if(content.size() <= offset){
            //没有下一页，结束
            return Result.ok();
        }
        //截取offset limit条
        List<Long> ids = new ArrayList<>(content.size());
        Map<String , Distance> map = new HashMap<>(content.size());
        content.stream().skip(offset).forEach(r->{
            //获取店铺id
            String id = r.getContent().getName();
            ids.add(Long.valueOf(id));
            //获取距离
            Distance distance = r.getDistance();
            map.put(id,distance);
        });
        //根据id查shop
        String join = StrUtil.join(",", ids);
        //排序
        List<Shop> shop = query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();
        for (Shop shop1 : shop) {
            shop1.setDistance(map.get(shop1.getId().toString()).getValue());
        }
        //返回
        return Result.ok(shop);
    }
}
