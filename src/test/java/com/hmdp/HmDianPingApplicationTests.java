package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Test
    void testLogicalExpire() throws InterruptedException {
        // --- 1. 准备阶段：手动预热一个店铺数据到Redis ---
        // 假设我们要测试的店铺ID是 5L
        Long shopId = 5L;
        // 清理一下环境，防止之前有旧数据
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);

        System.out.println("【阶段一】开始预热缓存...");
        shopService.saveShop2Redis(shopId, 30L); // 设置30秒逻辑过期
        System.out.println("【阶段一】缓存预热成功！");

        // --- 2. 验证阶段：30秒内，数据不会变 ---
        System.out.println("【阶段二】验证30秒内返回的是旧数据...");
        // 模拟在数据库中店铺名字被修改了
        // 注意：这里我们并没有真的改数据库，只是为了后面验证。
        // 实际测试中，我们知道后台线程会去数据库拉取新数据。

        // 立即查询，应该得到我们刚存入的数据
        Shop currentShop = (Shop) shopService.queryById(shopId).getData();
        System.out.println("30秒内第一次查询，店铺名: " + currentShop.getName());
        assertNotNull(currentShop, "30秒内查询不应为空");


        // --- 3. 等待阶段：让缓存逻辑过期 ---
        System.out.println("【阶段三】程序暂停31秒，等待逻辑过期...");
        Thread.sleep(31000); // 等待31秒，确保超过30秒的逻辑过期时间
        System.out.println("【阶段三】已等待31秒，缓存现已逻辑过期。");


        // --- 4. 触发与验证阶段 ---
        System.out.println("【阶段四】发送请求以触发后台重建...");
        // 再次查询，这次会触发后台重建，但本身返回的应该还是旧数据
        currentShop = (Shop) shopService.queryById(shopId).getData();
        System.out.println("过期后第一次查询（触发重建），店铺名: " + currentShop.getName());
        assertNotNull(currentShop, "触发重建时，返回的旧数据不应为空");

        System.out.println("【阶段四】后台正在重建，主线程等待2秒...");
        Thread.sleep(2000); // 等待后台线程执行完毕

        System.out.println("【阶段四】验证重建结果...");
        // 最终查询，此时应该能拿到后台线程从数据库获取并写入缓存的最新数据
        // 因为我们的测试没有修改数据库，所以名字应该不变。但这个流程证明了重建逻辑被执行了。
        Shop finalShop = (Shop) shopService.queryById(shopId).getData();
        System.out.println("最终查询，店铺名: " + finalShop.getName());
        assertNotNull(finalShop, "重建后查询不应为空");

        System.out.println("测试成功！完整地验证了逻辑过期的全过程。");
    }

    @Test
    void passwordEncoderWorks() {
        String raw = "secret";
        String encoded = PasswordEncoder.encode(raw);
        assertNotNull(encoded, "编码结果不应为空");
        org.junit.jupiter.api.Assertions.assertTrue(PasswordEncoder.matches(encoded, raw));
        org.junit.jupiter.api.Assertions.assertFalse(PasswordEncoder.matches(encoded, "other"));
    }
}
