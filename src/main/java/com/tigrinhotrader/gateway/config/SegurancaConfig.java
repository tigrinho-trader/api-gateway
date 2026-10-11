package com.tigrinhotrader.gateway.config;

import com.tigrinhotrader.gateway.web.RespostasSeguranca;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Seguranca do gateway: valida o JWT do provedor OpenID Connect (assinatura via JWKS, iss, aud, exp) e decide quem e
 * publico. API stateless: sem sessao, sem CSRF, sem login por formulario.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AuthPropriedades.class)
public class SegurancaConfig {

    /** Rotas publicas (tabela do contrato). Nelas o token e ignorado, mesmo se vier vencido. */
    static final ServerWebExchangeMatcher PUBLICAS = new OrServerWebExchangeMatcher(List.of(
            new PathPatternParserServerWebExchangeMatcher("/**", HttpMethod.OPTIONS),
            new PathPatternParserServerWebExchangeMatcher("/actuator/health"),
            new PathPatternParserServerWebExchangeMatcher("/actuator/health/**"),
            new PathPatternParserServerWebExchangeMatcher("/actuator/info"),
            new PathPatternParserServerWebExchangeMatcher("/api/cotacoes/**"),
            new PathPatternParserServerWebExchangeMatcher("/api/jogos/flecha/regras", HttpMethod.GET),
            new PathPatternParserServerWebExchangeMatcher("/api/ordens/regras", HttpMethod.GET),
            new PathPatternParserServerWebExchangeMatcher("/api/ordens/barreira", HttpMethod.GET)));

    @Bean
    SecurityWebFilterChain cadeiaDeSeguranca(ServerHttpSecurity http, RespostasSeguranca respostas,
                                             GlobalCorsProperties cors) {
        // mesmo CORS das rotas (spring.cloud.gateway.globalcors): assim o 401 tambem sai com os cabecalhos CORS
        UrlBasedCorsConfigurationSource fonteCors = new UrlBasedCorsConfigurationSource();
        fonteCors.setCorsConfigurations(cors.getCorsConfigurations());

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(c -> c.configurationSource(fonteCors))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(regras -> regras
                        .matchers(PUBLICAS).permitAll()
                        .pathMatchers("/api/**").authenticated()
                        // fora de /api so existe o actuator publico; o resto vira 404 (RespostasSeguranca)
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenConverter(conversorIgnorandoPublicas())
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas)
                        .jwt(jwt -> { }))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))
                .build();
    }

    /** Le o Bearer do cabecalho Authorization, menos nas rotas publicas. */
    private static ServerAuthenticationConverter conversorIgnorandoPublicas() {
        ServerBearerTokenAuthenticationConverter padrao = new ServerBearerTokenAuthenticationConverter();
        return exchange -> PUBLICAS.matches(exchange)
                .flatMap(resultado -> resultado.isMatch() ? Mono.empty() : padrao.convert(exchange));
    }

    /**
     * Decoder RS256 com as chaves do JWKS do provedor (Keycloak, Auth0...). O JWKS so e buscado na primeira
     * validacao (nada de rede na inicializacao) e fica em cache.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(AuthPropriedades propriedades) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(propriedades.jwksUri()).build();
        OAuth2TokenValidator<Jwt> audiencia = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(propriedades.audiencia()));
        // createDefaultWithIssuer = exp/nbf (com 60s de tolerancia) + iss
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(propriedades.emissor()), audiencia));
        return decoder;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
