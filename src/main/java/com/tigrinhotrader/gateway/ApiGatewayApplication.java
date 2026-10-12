package com.tigrinhotrader.gateway;

import com.tigrinhotrader.gateway.config.ResolucaoDnsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        ResolucaoDnsConfig.encurtarCacheDnsDaJvm();
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
