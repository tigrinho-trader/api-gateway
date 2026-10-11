package com.tigrinhotrader.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * Le o application.yml de verdade (sem as variaveis do sistema) e confere os padroes do Keycloak local e a
 * troca para o Auth0 so por variavel de ambiente.
 */
class AuthPropriedadesTest {

    @Test
    void padraoApontaParaOKeycloakLocal() throws IOException {
        AuthPropriedades p = carregar(Map.of());
        assertThat(p.emissor()).isEqualTo("http://localhost:8180/realms/tigrinho");
        assertThat(p.jwksUri()).isEqualTo("http://localhost:8180/realms/tigrinho/protocol/openid-connect/certs");
        assertThat(p.audiencia()).isEqualTo("tigrinho-api");
        assertThat(p.namespaceClaims()).isEqualTo("https://tigrinho-trader/");
        assertThat(p.usuariosUrl()).isEqualTo("http://localhost:8085");
        assertThat(p.cacheStatus()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.timeoutStatus()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void jwksAcompanhaOEmissorQuandoSoOEmissorMuda() throws IOException {
        AuthPropriedades p = carregar(Map.of("AUTH_ISSUER", "http://keycloak.exemplo:8080/realms/outro"));
        assertThat(p.jwksUri()).isEqualTo("http://keycloak.exemplo:8080/realms/outro/protocol/openid-connect/certs");
    }

    @Test
    void auth0SoPorConfiguracao() throws IOException {
        AuthPropriedades p = carregar(Map.of(
                "AUTH_ISSUER", "https://tigrinho-trader.us.auth0.com/",
                "AUTH_JWKS_URI", "https://tigrinho-trader.us.auth0.com/.well-known/jwks.json",
                "AUTH_AUDIENCE", "https://api.tigrinho-trader",
                "AUTH_CLAIMS_NAMESPACE", "https://ns.exemplo/",
                "AUTH_STATUS_CACHE", "1m",
                "AUTH_STATUS_TIMEOUT", "750ms"));
        assertThat(p.emissor()).isEqualTo("https://tigrinho-trader.us.auth0.com/");
        assertThat(p.jwksUri()).isEqualTo("https://tigrinho-trader.us.auth0.com/.well-known/jwks.json");
        assertThat(p.audiencia()).isEqualTo("https://api.tigrinho-trader");
        assertThat(p.namespaceClaims()).isEqualTo("https://ns.exemplo/");
        assertThat(p.cacheStatus()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.timeoutStatus()).isEqualTo(Duration.ofMillis(750));
    }

    private static AuthPropriedades carregar(Map<String, Object> ambiente) throws IOException {
        MutablePropertySources fontes = new MutablePropertySources();
        fontes.addFirst(new MapPropertySource("ambiente", ambiente));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(fontes::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(fontes),
                new PropertySourcesPlaceholdersResolver(fontes));
        return binder.bind("tigrinho.auth", AuthPropriedades.class).get();
    }
}
