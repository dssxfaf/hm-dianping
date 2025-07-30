package com.hmdp.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //1.缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //2.互斥锁解决缓存击穿
//        Shop shop1 = null;
//        try {
//            shop1 = queryWithMutex(id);
//        } catch (InterruptedException e) {
//            // Restore the interrupted status
//            Thread.currentThread().interrupt();
//            // Optionally log the exception and return an appropriate error response
//            // For example, you might return a "service unavailable" or "request timed out" error.
//            return Result.fail("查询被中断，请重试");
//        }
        //3.逻辑过期解决缓存击穿
        Shop shop = null;
        try {
            // 3.逻辑过期解决缓存击穿
            shop = queryWithLogicalExpire(id);
        } catch (InterruptedException e) {
            // 恢复中断状态
            Thread.currentThread().interrupt();
            // 返回通用错误信息
            return Result.fail("系统繁忙，请稍后重试");
        }
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) throws InterruptedException {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3. 存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject)redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return shop;

        }
        //5.2 已过期，需要缓存重建
        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean islock = trylock(lockKey);
        //6.2 判断是否获取锁成功
        if(islock){
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                this.saveShop2Redis(id,30L);
                unlock(lockKey);
            });
        }
        //6.4 失败，返回过期的商铺信息
        return shop;
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，反序列化后返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){
            //返回1个错误信息
            return null;
        }
        //3.实现缓存重建
        //3.1 获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean islock = trylock(lockKey);
        //3.2 判断是否成功
        if(!islock){
            //3.3 失败，则休眠并重试
            Thread.sleep(50);
            return queryWithMutex(id);
        }
        //3.4 成功，根据id查询数据库
        //4.不存在，根据id查询数据
        Shop shop = getById(id);
        if (shop == null) {
            // ❗缓存空值，防止穿透
            stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            return null;
        }
        //5.不存在，返回错误
        if(shop == null){
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        //7.释放互斥锁
        unlock(lockKey);
        //8.返回
        return shop;
    }
    public Shop queryWithPassThrough(Long id) throws InterruptedException {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，反序列化后返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){
            //返回1个错误信息
            return null;
        }
        //4.不存在，根据id查询数据
        Shop shop = getById(id);
        //模拟重建的延时
        Thread.sleep(200);
        if (shop == null) {
            // ❗缓存空值，防止穿透
            stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            return null;
        }
        //5.不存在，返回错误
        if(shop == null){
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        // 模拟重建的延时
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        String key = CACHE_SHOP_KEY + id;
        if(id == null){
            return Result.fail("店铺ID不能为空");
        }
        boolean isSuccess = this.updateById(shop);
        if (!isSuccess) {
            return Result.fail("数据库更新失败");
        }
        stringRedisTemplate.delete(key);
        //7.返回
        return Result.ok(shop);
    }
}
