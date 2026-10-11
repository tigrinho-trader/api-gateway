package com.tigrinhotrader.gateway.web;

import com.tigrinhotrader.gateway.config.AuthPropriedades;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Propaga a identidade do token para os servicos. Sempre apaga os cabecalhos X-Usuario-* que vieram do
 * cliente e, se a requisicao estiver autenticada, preenche:
 * X-Usuario-Id = sub (sem codificacao); X-Usuario-Email / -Nome / -Avatar = claim com namespace ou a
 * padrao (email, name, picture), em URL-encoding UTF-8; omitidos se a claim nao existir.
 * Roda depois da seguranca (que e um WebFilter) e antes do roteamento.
 */
@Component
public class FiltroIdentidade implements GlobalFilter, Ordered {

    public static final int ORDEM = Ordered.HIGHEST_PRECEDENCE + 100;

    static final String PREFIXO = "x-usuario-";
    static final String ID = "X-Usuario-Id";
    static final String EMAIL = "X-Usuario-Email";
    static final String NOME = "X-Usuario-Nome";
    static final String AVATAR = "X-Usuario-Avatar";

    private final String namespace;

    public FiltroIdentidade(AuthPropriedades propriedades) {
        this.namespace = propriedades.namespaceClaims();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .map(jwt -> exchange.mutate().request(r -> r.headers(h -> {
                    limpar(h);
                    preencher(h, jwt);
                })).build())
                .switchIfEmpty(Mono.fromSupplier(() -> exchange.mutate().request(r -> r.headers(this::limpar)).build()))
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return ORDEM;
    }

    private void limpar(HttpHeaders cabecalhos) {
        List<String> nomes = cabecalhos.keySet().stream()
                .filter(nome -> nome.toLowerCase(Locale.ROOT).startsWith(PREFIXO))
                .toList();
        nomes.forEach(cabecalhos::remove);
    }

    private void preencher(HttpHeaders cabecalhos, Jwt jwt) {
        cabecalhos.set(ID, jwt.getSubject());
        definirCodificado(cabecalhos, EMAIL, claim(jwt, "email"));
        definirCodificado(cabecalhos, NOME, claim(jwt, "name"));
        definirCodificado(cabecalhos, AVATAR, claim(jwt, "picture"));
    }

    /** Claim com namespace (ex.: https://tigrinho-trader/email) ou, se nao houver, a padrao. */
    private String claim(Jwt jwt, String nome) {
        String comNamespace = jwt.getClaimAsString(namespace + nome);
        return StringUtils.hasText(comNamespace) ? comNamespace : jwt.getClaimAsString(nome);
    }

    private static void definirCodificado(HttpHeaders cabecalhos, String cabecalho, String valor) {
        if (StringUtils.hasText(valor)) {
            cabecalhos.set(cabecalho, URLEncoder.encode(valor, StandardCharsets.UTF_8));
        }
    }
}
