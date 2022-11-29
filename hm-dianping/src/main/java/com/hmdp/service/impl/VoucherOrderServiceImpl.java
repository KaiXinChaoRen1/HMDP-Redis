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
import io.lettuce.core.RedisBusyException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 秒杀业务
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;

//    @Override         //事务失效问题,直接获取代理类的解决方式需要在接口中写方法,而在这里实现类里需要实现
//    public Result createVoucherOrder(Long voucherId) {
//        return null;
//    }

    //lua脚本（秒杀）********************************************************
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //****************************************************************

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //Spring启动时使用线程处理阻塞队列里的任务
    @PostConstruct
    private void init() {
        //启动时创建消息队列，要不然也得手动创建
        DefaultRedisScript<Object> addMQScript = new DefaultRedisScript<>("redis.call('xgroup', 'create','stream.orders', 'g1', '0', 'mkstream')");
        SECKILL_SCRIPT.setResultType(Long.class);
        try {
            stringRedisTemplate.execute(addMQScript,Collections.emptyList(),"");
        }catch (RedisBusyException | RedisSystemException e) {
            log.info("消息队列已经创建了");
        }
        //提交线程任务执行
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
//    @PreDestroy
//    private void preDestroy() {
//        //关闭时删除，否则下次启动上面代码会报错，其实捕获就行了，淦
//        DefaultRedisScript<Object> addMQScript = new DefaultRedisScript<>("redis.call('xgroup', 'destroy','stream.orders', 'g1')");
//        stringRedisTemplate.execute(addMQScript,Collections.emptyList(),"");
//    }

    /**
     * Redis实现消息队列
     * 1.List模拟消息队列。（大垃）缺点：1.同一个消息只能被一个消费者拿到 2.拿到消息后如果丢失就再也无了
     * 2.PubSub发布订阅。（特垃）优点：1.支持多生产者多消费者  缺点：1.数据不能持久化，没人接收就直接无了2.消息丢失3.消息堆积有上限
     * 3.Streams。
     * 1.单消费模式（小垃） 优点：1.消息会保存2.多消费者3.可以阻塞读取。缺点，1.读最新的话可能会漏读
     * 2.消费者组 （牛） 优点：1.可以维护一个标识，记录最后一个被处理的消息，解决漏读 2.消息确认机制3.持久化4.阻塞5.出现错误可以回溯
     */
    //******************************************************************7.其他线程处理消息
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息==>  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    //opsForStream()    玩Stream
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
        //*****************************************************************************8.出现错误处理PendingList
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    //*******************************************************************6.异步秒杀的前半部分（最终版），到返回给前端信息，后续的操作由其他线程读取消息队列慢慢做
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本，判断资格，扣减库存并将消息并加入消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        return Result.ok(orderId);
    }

    //****************************************************************5.3.创建订单功能（最终版）
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        //因为是子线程，userId只能去voucherOrder里取
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 加锁（前边redis已经做过判断，这里兜底一下）
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单！");  //异步处理已经没有前端了，打印一下日志即可
            return;
        }
        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("不允许重复下单！");
                return;
            }
            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                log.error("库存不足！");
                return;
            }
            // 7.创建订单
            save(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }


//    //**********************************************************5.2.阻塞队列，异步执行阻塞队列里的任务
//    //*********************************************************** 坑！？：1.阻塞队列大小受内存限制2.服务宕机数据丢失3.取出任务后出现错误，任务丢失
//    //1.阻塞队列（获取不到会阻塞）
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    //2.线程池
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//    //3.线程任务
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    // 1.获取队列中的订单信息（获取不到会阻塞）
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2.创建订单
//                    createVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }
//    //4.Spring启动时处理阻塞队列里的任务
//    @PostConstruct
//    private void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }
//    //***************************************************************************************


//    //************************************************************************5.1.秒杀优化，异步执行
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,                             //脚本
//                Collections.emptyList(),                    //key
//                voucherId.toString(), userId.toString()     //args
//        );
//        int r = result.intValue();
//        // 2.判断结果
//        if (r != 0) {
//            // 2.1.不为0 ，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2.为0 ，有购买资格，创建订单，把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        // 2.3.放入阻塞队列
//        orderTasks.add(voucherOrder);
//        // 3.阻塞队列里慢慢的异步执行，这里可以直接返回订单id
//        return Result.ok(orderId);
//    }


//    //*********************************************************************4.使用redisson实现是分布式锁,
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5.一人一单
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象
//        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//        // 尝试获取锁(可以有三个参数,重试时间,超时释放时间,时间单位,这里用默认即可)
//        boolean isLock = redisLock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            // 5.1.查询订单
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            if (count > 0) {
//                return Result.fail("用户已经购买过一次！");
//            }
//            // 6.扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1") // set stock = stock - 1
//                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                    .update();
//            if (!success) {
//                return Result.fail("库存不足！");
//            }
//            // 7.创建订单并返回id
//            VoucherOrder voucherOrder = new VoucherOrder();
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            voucherOrder.setUserId(userId);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//
//            return Result.ok(orderId);
//        } finally {
//            redisLock.unlock();
//        }
//    }


//    //****************************************************************3.redis实现手写分布式锁,实现下单操作
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//
//        // 创建锁对象
//        SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        // 尝试获取锁
//        boolean isLock = redisLock.tryLock(1200);
//        // 判断
//        if (!isLock) {
//            //根据业务决定是直接返回错误信息还是重试
//            return Result.fail("不允许重复下单！");
//        }
//
//        try {
//            // 5.1.查询订单
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            if (count > 0) {
//                return Result.fail("用户已经购买过一次！");
//            }
//            // 6.扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1") // set stock = stock - 1
//                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                    .update();
//            if (!success) {
//                return Result.fail("库存不足！");
//            }
//            // 7.创建订单返回id
//            VoucherOrder voucherOrder = new VoucherOrder();
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            voucherOrder.setUserId(userId);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            return Result.ok(orderId);
//        } finally {
//            // 释放锁
//            redisLock.unlock();
//        }
//    }


//    //**************************************************************************2.下单操作(单机情况下)
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5.实现一人一单,(如果用同步方法,则所有人串行执行,效率太低,我们只需要让单个用户不重复购买就行了,因此锁对象用用户id)
//        Long userId = UserHolder.getUser().getId();
//        //synchronized (userId.toString().intern()) {       //intern(),从常量值中取这个值,没有的话,加入常量池中(锁加在这里,会导致先释放锁,在提交事务,只能把锁加到调用这个方法的地方,才能保证先提交事务,再释放锁)
//        // 5.1.查询订单
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0) {
//            return Result.fail("用户已经购买过一次！");
//        }
//        // 6.扣减库存(乐观锁实现,在更新时判断是否变化,由于业务关系,只需要判断库存是否>0即可)
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")                                         // set stock = stock - 1
//                .eq("voucher_id", voucherId).gt("stock", 0)       // where id = ? and stock > 0
//                .update();
//        if (!success) {
//            return Result.fail("库存不足！");
//        }
//
//        // 7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 7.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        // 7.返回订单id
//        return Result.ok(orderId);
//        //}
//    }
//


//    //***************************************************************************1.秒杀代金券,并且实现一人一单(单机情况下)
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀活动时间
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 3.判断库存
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        //4.下单功能(再这里加锁能保证先提交事务再释放锁)
//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            //可以这样解决,添加依赖,启动类添加注解,然后直接获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);       //这个方法加了事务,而这里调用的者this,也就是VoucherOrderServiceImpl,而不是其代理类,要知道Spring的事务是通过代理实现的,所以直接这样调用会出现事务失效的问题
//        }
//    }


}
