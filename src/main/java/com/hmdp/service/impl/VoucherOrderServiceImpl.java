package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 冬
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> BETTER_SCRIPT;

    static {
        BETTER_SCRIPT = new DefaultRedisScript<>();
        BETTER_SCRIPT.setLocation(new ClassPathResource("better.lua"));
        BETTER_SCRIPT.setResultType(long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //类一加载就执行
    //异步线程下单
    @PostConstruct
    public void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的用户订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    //List<MapRecord<String, Object, Object>> list = new ArrayList<>();
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //失败 继续循环
                        continue;
                    }
                    //成功 可以下单
                    MapRecord<String, Object, Object> mapRecord = list.get(0);
                    Map<Object, Object> value = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", mapRecord.getId());

                } catch (Exception e) {
                    log.error("订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获取pendingList中的用户订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //失败 继续循环
                        break;
                    }
                    //成功 可以下单
                    MapRecord<String, Object, Object> mapRecord = list.get(0);
                    Map<Object, Object> value = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", mapRecord.getId());

                } catch (Exception e) {
                    log.error("pendingList订单异常", e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }
    /*//阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{


        @Override
        public void run() {
            while(true){

                try {
                    //获取队列中的用户订单信息
                    VoucherOrder takeOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(takeOrder);
                } catch (Exception e) {
                    log.error("订单异常" , e);
                }
            }
        }
    }*/

    /**
     * spring提交之后才释放锁
     * @param takeOrder
     */
    private void handleVoucherOrder(VoucherOrder takeOrder) {

        Long id1 = takeOrder.getId();

        RLock Lock = redissonClient.getLock("lock:order:" + id1);
        //获取锁
        boolean isLock = Lock.tryLock();
        //判断
        if (!isLock) {
            //失败
            log.error("不允许重复下单");
            return;
        }
        //事务提交才会释放锁
        try {
            //返回代理对象（事务）
            proxy.createVoucherOrder(takeOrder);
        } finally {
            Lock.unlock();
        }
    }

    /**
     * 优惠券秒杀下单
     * @param voucherId
     * @return
     */
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户ID
        Long UserId = UserHolder.getUser().getId();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本 脚本中存入redis队列
        Long result = stringRedisTemplate.execute(
                BETTER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserId.toString(),String.valueOf(orderId)
        );
        //判断结果是否为零
        int i = result.intValue();
        if (i != 0) {
            //不为零则没有购买资格
            Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }
        //获取动态代理
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户ID
        Long UserId = UserHolder.getUser().getId();
       //执行lua脚本
        Long result = stringRedisTemplate.execute(
                BETTER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserId.toString()
        );
        //判断结果是否为零
        int i = result.intValue();
        if (i != 0) {
            //不为零则没有购买资格
            Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }

        // 为0 ，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(UserId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        //存入阻塞队列
        orderTasks.add(voucherOrder);
        //获取动态代理
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }
*/

   /* @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断优惠劵的时间是否在设置的范围内
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀时间尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已结束
            return Result.fail("秒杀时间已经结束！");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1) {
            //不足，返回异常
            return Result.fail("优惠劵库存不足！");

        }

        *//**
         * 乐观锁解决 添加版本号version
         * 查询时同时查出版本号
         * eg: stock = 1
         *     version = 1
         * 添加时对stock-1，version+1
         * 同时去查version是否等于之前查出来的值
         * 是则未修改，否则发生线程安全问题
         *//*

        //获取用户ID
        Long id1 = UserHolder.getUser().getId();
        *//* synchronized(id1.toString().intern()){}*//*
        //上锁
        //simpleRedisLock Lock = new simpleRedisLock(stringRedisTemplate, "order:" + id1);
        RLock Lock = redissonClient.getLock("lock:order:" + id1);
        //获取锁
        boolean isLock = Lock.tryLock();
        //判断
        if (!isLock) {
            //失败
            return Result.fail("不允许重复下单");
        }
        //事务提交才会释放锁
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            Lock.unlock();
        }
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //获取用户ID
//        Long id1 = UserHolder.getUser().getId();
        Long id1 = voucherOrder.getUserId();
        //查询订单
        Integer count = query().eq("user_id", id1).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if (count > 0) {
            //重复购买
            log.error("用户重复购买");
            return;
        }
        //充足，走SQL去扣减库存
        boolean success = seckillVoucherService.update()
                // set stock = stock - 1
                .setSql("stock = stock - 1")
                //where id = ? and stock = ?
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                //eq("stock" , voucher.getStock())
                .update();
        //扣减异常
        if (!success) {
            log.error("优惠劵库存不足！");
            return;
        }
        /*//创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单ID
        long order = redisIdWorker.nextId("order");
        voucherOrder.setId(order);
        //用户ID
        Long id = UserHolder.getUser().getId();
        voucherOrder.setUserId(id);
        //代金券ID
        voucherOrder.setVoucherId(voucherOrder);*/
        save(voucherOrder);

        //返回订单ID
        /*return Result.ok(order);*/
    }
}
