package com.commerce.task;

import com.commerce.common.Const;
import com.commerce.common.RedissonManager;
import com.commerce.service.OrderService;
import com.commerce.util.PropertiesUtil;
import com.commerce.util.RedisSharededPoolUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CloseOrderTask {

    // Spring Schedule默认是串行执行。如果改用quartz的话就要注意。

    // 例如Redisson里面的初始化，目前为了小伙伴们的项目能先正常运行，Redisson初始化已注释

    @Autowired
    private OrderService orderService;

    @Autowired
    private RedissonManager redissonManager;

    /**
     * 没有分布式锁, 只适合单机部署环境
     */
//    @Scheduled(cron = "0 */1 * * * ?")// 每1分钟(每个1分钟的整数倍)
    public void closeOrderTaskV1() {
        int hour = Integer.parseInt(PropertiesUtil.getProperty("close.order.task.time.hour", "1"));
        orderService.closeOrder(hour);
    }


    /**
     * 可能出现死锁，虽然在执行close的时候有防死锁，但是还是会出现，因为setnx无法与expire进行原子操作
     */
//    @Scheduled(cron = "0 */1 * * * ?")// 每1分钟(每个1分钟的整数倍)
    public void closeOrderTaskV2() throws InterruptedException {
        long lockTimeout = Long.parseLong(PropertiesUtil.getProperty("lock.timeout.millis", "5000"));// 锁5秒有效期
        //这个时间如何用呢，看下面。和时间戳结合起来用。
        Long setnxResult = RedisSharededPoolUtil.setnx(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, String.valueOf(System.currentTimeMillis() + lockTimeout));
        if (setnxResult != null && setnxResult.intValue() == 1) {
            //如果返回值是1，代表设置成功，获取锁
            closeOrder();
        } else {
            log.info("没有获得分布式锁:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
        }
    }


    /**
     * 防死锁之分布式锁
     */
    @Scheduled(cron = "0 */1 * * * ?")// 每1分钟(每个1分钟的整数倍)
    public void closeOrderTaskV3() throws InterruptedException {
        //防死锁分布式锁
        long lockTimeout = Long.parseLong(PropertiesUtil.getProperty("lock.timeout.millis", "50000"));// 锁50秒有效期
        // 项目由于历史数据关单订单比较多,需要处理,初次用50s时间,后续改成5s即可.同时50s也为了讲课debug的时候时间长而设置。

        //这个时间如何用呢，看下面。和时间戳结合起来用。
        Long setnxResult = RedisSharededPoolUtil.setnx(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, String.valueOf(System.currentTimeMillis() + lockTimeout));
        if (setnxResult != null && setnxResult.intValue() == 1) {
            //如果返回值是1，代表设置成功，获取锁
            closeOrder();
        } else {
            // 如果setnxResult==null 或 setnxResult.intValue() ==0 即 != 1的时候
            // 未获取到锁，继续判断,判断时间戳,看是否可以重置获取到锁
            String lockValueStr = RedisSharededPoolUtil.get(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);

            // 如果lockValue不是空,并且当前时间大于锁的有效期,说明之前的lock的时间已超时,执行getset命令.
            if (lockValueStr != null && System.currentTimeMillis() > Long.parseLong(lockValueStr)) {
                String getSetResult = RedisSharededPoolUtil.getSet(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, String.valueOf(System.currentTimeMillis() + lockTimeout));
                // 再次用当前时间戳getset，
                // 返回给定 key 的旧值。  ->旧值判断，是否可以获取锁
                // 当 key 没有旧值时，即 key 不存在时，返回 nil 。 ->获取锁
                // 这里我们set了一个新的value值，获取旧的值。
                if (getSetResult == null || (getSetResult != null && StringUtils.equals(lockValueStr, getSetResult))) {
                    // 获取到锁
                    closeOrder();
                } else {
                    log.info("没有获得分布式锁:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
                }
            } else {
                log.info("没有获得分布式锁:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
            }
        }
    }


    /**
     * Redisson分布式锁实现
     *
     * @throws InterruptedException
     */
//    @Scheduled(cron = "0 */1 * * * ?")// 每1分钟(每个1分钟的整数倍)
    public void closeOrderTaskV4() throws InterruptedException {
        RLock lock = redissonManager.getRedisson().getLock(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
        boolean getLock = false;
        try {
            if (getLock = lock.tryLock(0, 50, TimeUnit.SECONDS)) {// trylock增加锁
                log.info("===获取{},ThreadName:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, Thread.currentThread().getName());
                int hour = Integer.parseInt(PropertiesUtil.getProperty("close.order.task.time.hour", "2"));
//                orderService.closeOrder(hour);
                System.out.println("模拟执行业务: orderService.closeOrder(" + hour + ");");
            } else {
                log.info("===没有获得分布式锁:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
            }
        } finally {
            if (getLock) {
                log.info("===释放分布式锁:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
                lock.unlock();
            }
        }
    }


    private void closeOrder() {
        // expire命令用于给该锁设定一个过期时间，用于防止线程crash，导致锁一直有效，从而导致死锁。
        RedisSharededPoolUtil.expire(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, 50);// 有效期50秒,防死锁

        log.info("获取{},ThreadName:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, Thread.currentThread().getName());

        int hour = Integer.parseInt(PropertiesUtil.getProperty("close.order.task.time.hour", "1"));

        orderService.closeOrder(hour);
//        System.out.println("模拟执行业务: orderService.closeOrder(hour);");

        RedisSharededPoolUtil.del(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);// 释放锁

        log.info("释放{},ThreadName:{}", Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK, Thread.currentThread().getName());

        log.info("===========================");
    }


}
