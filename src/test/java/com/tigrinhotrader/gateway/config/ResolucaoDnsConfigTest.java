package com.tigrinhotrader.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.resolver.DefaultAddressResolverGroup;
import java.security.Security;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

/**
 * Resolucao de nomes pela JVM (cache curto): com o resolvedor proprio do Netty, depois de um container ser
 * recriado o gateway seguia no IP antigo (500 "Connection refused") ate ser reiniciado.
 */
class ResolucaoDnsConfigTest {

    @Test
    void rotasEWebClientResolvemPelaJvm() {
        ResolucaoDnsConfig config = new ResolucaoDnsConfig();
        assertThat(config.resolucaoPelaJvmNasRotas().customize(HttpClient.create()).configuration().resolver())
                .isSameAs(DefaultAddressResolverGroup.INSTANCE);
        assertThat(config.resolucaoPelaJvmNoWebClient().configure(HttpClient.create()).configuration().resolver())
                .isSameAs(DefaultAddressResolverGroup.INSTANCE);
        // o padrao do reactor-netty e o resolvedor DNS do Netty, que segue o TTL do DNS do Docker
        assertThat(HttpClient.create().configuration().resolver()).isNotSameAs(DefaultAddressResolverGroup.INSTANCE);
    }

    @Test
    void jvmGuardaOEnderecoPorPoucosSegundos() {
        String antes = Security.getProperty("networkaddress.cache.ttl");
        try {
            ResolucaoDnsConfig.encurtarCacheDnsDaJvm();
            assertThat(Security.getProperty("networkaddress.cache.ttl")).isEqualTo("5");
        } finally {
            Security.setProperty("networkaddress.cache.ttl", antes == null ? "-1" : antes);
        }
    }
}
