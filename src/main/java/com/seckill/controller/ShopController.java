package com.seckill.controller;

import com.seckill.cache.ShopCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/shop")
public class ShopController {

    private final ShopCacheService shopCacheService;

    public ShopController(ShopCacheService shopCacheService) {
        this.shopCacheService = shopCacheService;
    }

    /** 商铺详情（多级缓存） */
    @GetMapping("/{id}")
    public Map<String, Object> shop(@PathVariable long id) {
        String detail = shopCacheService.queryShopDetail(id);
        return Map.of("code", detail == null ? 404 : 0, "data", detail == null ? "not found" : detail);
    }
}
