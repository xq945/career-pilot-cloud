package com.careerpilot.poc.consumer;

import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "poc-provider")
public interface ProviderClient {

    @GetMapping("/hello")
    Map<String, Object> hello(@RequestParam("name") String name);
}
