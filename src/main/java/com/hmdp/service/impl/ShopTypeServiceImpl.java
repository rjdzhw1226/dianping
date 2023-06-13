package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 冬
 * @since 2022-09-08
 */

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {

        //方法1
        //token前缀统一提取
        String typeKey = RedisConstants.CACHE_TYPE_KEY;
        //从Redis中查询
        String typeString = stringRedisTemplate.opsForValue().get(typeKey);
        List<ShopType> cacheTypeList = JSONUtil.toList(typeString, ShopType.class);
        //存在直接返回
        if (!cacheTypeList.isEmpty()) {
            List<ShopType> typeList = new ArrayList<>();
            for (ShopType shopType : cacheTypeList) {
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        //不存在，去数据库中查
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //数据库中未查到，返回错误信息
        if (typeList == null) {
            return Result.fail("不存在此分类，错误！");
        }
        //声明字符串数组
        List<String> shopTypeList = new ArrayList<>();
        //循环存入
        for (ShopType shopType : typeList) {
            String s = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(s);
        }
        //存入Redis缓存中
        //stringRedisTemplate.opsForList().rightPushAll(typeKey, shopTypeList);
        stringRedisTemplate.opsForValue().set(typeKey , shopTypeList.toString());

        return Result.ok(typeList);
    }


    //方法2
    /**
     * String typeString = stringRedisTemplate.opsForValue().get(typeKey);
     * range.stream().map(x -> JSONUtil.toBean(x, ShopType.class)).collect(Collectors.toList());
     */

}
