package com.tigrinhotrader.gateway.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import java.security.Security;
import org.springframework.boot.autoconfigure.web.reactive.function.client.ReactorNettyHttpClientMapper;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;

/**
 * O Docker troca o IP de um servico quando o container e recriado (ex.: {@code docker compose up --build}).
 * O resolvedor DNS proprio do Netty guarda o endereco pelo TTL que o DNS do Docker informa (minutos), e o
 * gateway seguia mandando requisicoes ao IP antigo ("Connection refused" / 500) ate ser reiniciado.
 * Aqui as rotas e o WebClient do user-service resolvem nomes pela JVM, que guarda o endereco por poucos segundos.
 */
@Configuration
public class ResolucaoDnsConfig {

    /** Segundos que a JVM guarda um endereco resolvido (o padrao sem SecurityManager e 30). */
    public static final String CACHE_DNS_SEGUNDOS = "5";

    /** Chamado no main, antes de qualquer resolucao de nome (a JVM le o valor uma vez so). */
    public static void encurtarCacheDnsDaJvm() {
        Security.setProperty("networkaddress.cache.ttl", CACHE_DNS_SEGUNDOS);
    }

    static HttpClient resolvendoPelaJvm(HttpClient cliente) {
        return cliente.resolver(DefaultAddressResolverGroup.INSTANCE);
    }

    /** HttpClient das rotas do gateway. */
    @Bean
    HttpClientCustomizer resolucaoPelaJvmNasRotas() {
        return ResolucaoDnsConfig::resolvendoPelaJvm;
    }

    /** HttpClient por tras do WebClient.Builder (ClienteUsuarios). */
    @Bean
    ReactorNettyHttpClientMapper resolucaoPelaJvmNoWebClient() {
        return ResolucaoDnsConfig::resolvendoPelaJvm;
    }
}
