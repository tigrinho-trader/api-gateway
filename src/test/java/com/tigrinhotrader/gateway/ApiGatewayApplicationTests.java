package com.tigrinhotrader.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Sobe o gateway apontando cada rota para um servidor HTTP falso que responde
 * "servico:caminho:X-Usuario-Id" (e devolve X-Usuario-Email/Nome/Avatar/Papel recebidos em Eco-*).
 * O Auth0 e simulado por um par de chaves RSA gerado aqui, com o JWKS servido por outro servidor falso;
 * o user-service falso responde o status da conta conforme o sub e conta as consultas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

    private static final String EMISSOR = "https://tigrinho-teste.auth0.local/";
    private static final String AUDIENCIA = "https://api.tigrinho-teste";
    private static final String NS = "https://tigrinho-trader/";
    private static final String ORIGEM = "http://localhost:5173";

    private static final RSAKey CHAVE = gerarChave("chave-teste");
    /** Mesmo kid da chave publicada, mas outro par: assinatura nao confere. */
    private static final RSAKey CHAVE_INTRUSA = gerarChave("chave-teste");
    /** kid que nao existe no JWKS. */
    private static final RSAKey CHAVE_DESCONHECIDA = gerarChave("outra-chave");

    private static final Map<String, AtomicInteger> CONSULTAS_STATUS = new ConcurrentHashMap<>();
    private static final List<String> CAMINHOS_STATUS = new CopyOnWriteArrayList<>();

    private static final HttpServer JWKS = servidor(Map.of("/", troca -> responder(troca, 200,
            new JWKSet(CHAVE.toPublicJWK()).toString())));
    private static final HttpServer TRADING = servidor(Map.of("/", eco("trading")));
    private static final HttpServer WALLET = servidor(Map.of("/", eco("wallet")));
    private static final HttpServer MARKET_DATA = servidor(Map.of("/", eco("market-data")));
    private static final HttpServer NOTIFICATION = servidor(Map.of("/", eco("notification")));
    private static final HttpServer USUARIOS = servidor(Map.of(
            "/", eco("usuarios"),
            "/interno/", ApiGatewayApplicationTests::statusDaConta));

    @Autowired
    private WebTestClient cliente;

    @LocalServerPort
    private int porta;

    @DynamicPropertySource
    static void configuracao(DynamicPropertyRegistry registro) {
        registro.add("TRADING_URL", () -> url(TRADING));
        registro.add("WALLET_URL", () -> url(WALLET));
        registro.add("MARKET_DATA_URL", () -> url(MARKET_DATA));
        registro.add("NOTIFICATION_URL", () -> url(NOTIFICATION));
        registro.add("USER_URL", () -> url(USUARIOS));
        registro.add("AUTH0_JWKS_URI", () -> url(JWKS) + "/.well-known/jwks.json");
        registro.add("AUTH0_ISSUER", () -> EMISSOR);
        registro.add("AUTH0_AUDIENCE", () -> AUDIENCIA);
        registro.add("AUTH_STATUS_TIMEOUT", () -> "500ms");
    }

    @AfterAll
    static void pararServidores() {
        Stream.of(JWKS, TRADING, WALLET, MARKET_DATA, NOTIFICATION, USUARIOS).forEach(s -> s.stop(0));
    }

    // ---------- roteamento e identidade ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/ordens,                      trading:/ordens:auth0|ana",
            "/api/ordens/abc-123,              trading:/ordens/abc-123:auth0|ana",
            "/api/jogos/flecha/sessoes/atual,  trading:/jogos/flecha/sessoes/atual:auth0|ana",
            "/api/carteira,                    wallet:/carteira:auth0|ana",
            "/api/carteira/extrato,            wallet:/carteira/extrato:auth0|ana",
            "/api/notificacoes,                notification:/notificacoes:auth0|ana",
            "/api/usuarios,                    usuarios:/usuarios:auth0|ana",
            "/api/usuarios/me,                 usuarios:/usuarios/me:auth0|ana"
    })
    void tokenValidoRoteiaParaOServicoCertoComOSub(String caminho, String esperado) {
        cliente.get().uri(caminho).headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(esperado);
    }

    @Test
    void patchEDeleteTambemPassam() {
        cliente.patch().uri("/api/usuarios/me").headers(h -> h.setBearerAuth(token("auth0|ana")))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"apelido\":\"ana\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("usuarios:/usuarios/me:auth0|ana");
        cliente.delete().uri("/api/usuarios/me").headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void cabecalhosForjadosSaoTrocadosPelosDoToken() {
        cliente.get().uri("/api/carteira")
                .headers(h -> h.setBearerAuth(token("auth0|ana")))
                .header("X-Usuario-Id", "hacker")
                .header("x-usuario-id", "hacker2")
                .header("X-Usuario-Email", "hacker@x.com")
                .header("X-Usuario-Nome", "Hacker")
                .header("X-Usuario-Avatar", "http://x")
                .header("X-Usuario-Papel", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Eco-Email")
                .expectHeader().doesNotExist("Eco-Nome")
                .expectHeader().doesNotExist("Eco-Avatar")
                .expectHeader().doesNotExist("Eco-Papel")
                .expectBody(String.class).isEqualTo("wallet:/carteira:auth0|ana");
    }

    @Test
    void cabecalhosForjadosSaoRemovidosNaRotaPublica() {
        cliente.get().uri("/api/cotacoes/BTCUSDT")
                .header("X-Usuario-Id", "hacker")
                .header("X-USUARIO-EMAIL", "hacker@x.com")
                .header("x-usuario-nome", "Hacker")
                .header("X-Usuario-Avatar", "http://x")
                .header("X-Usuario-Papel", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Eco-Email")
                .expectHeader().doesNotExist("Eco-Nome")
                .expectHeader().doesNotExist("Eco-Avatar")
                .expectHeader().doesNotExist("Eco-Papel")
                .expectBody(String.class).isEqualTo("market-data:/cotacoes/BTCUSDT:null");
    }

    @Test
    void emailNomeEAvatarComNamespaceVaoCodificados() {
        String jwt = assinar(claims("google-oauth2|123")
                .claim(NS + "email", "joão.ação@exemplo.com.br")
                .claim(NS + "name", "João da Silva Ação")
                .claim(NS + "picture", "https://cdn.exemplo.com/a b.png?x=1&y=ç")
                .claim("email", "outro@exemplo.com")
                .claim("name", "Outro")
                .claim("picture", "https://outro"));
        cliente.get().uri("/api/usuarios/me").headers(h -> h.setBearerAuth(jwt))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Eco-Email", codificar("joão.ação@exemplo.com.br"))
                .expectHeader().valueEquals("Eco-Nome", "Jo%C3%A3o+da+Silva+A%C3%A7%C3%A3o")
                .expectHeader().valueEquals("Eco-Avatar", codificar("https://cdn.exemplo.com/a b.png?x=1&y=ç"))
                .expectBody(String.class).isEqualTo("usuarios:/usuarios/me:google-oauth2|123");
    }

    @Test
    void semNamespaceUsaAsClaimsPadrao() {
        String jwt = assinar(claims("auth0|padrao")
                .claim(NS + "email", "")
                .claim("email", "maria@exemplo.com")
                .claim("name", "Maria Conceição")
                .claim("picture", "https://s.gravatar.com/avatar/1.png"));
        cliente.get().uri("/api/usuarios/me").headers(h -> h.setBearerAuth(jwt))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Eco-Email", "maria%40exemplo.com")
                .expectHeader().valueEquals("Eco-Nome", codificar("Maria Conceição"))
                .expectHeader().valueEquals("Eco-Avatar", codificar("https://s.gravatar.com/avatar/1.png"))
                .expectBody(String.class).isEqualTo("usuarios:/usuarios/me:auth0|padrao");
    }

    // ---------- rotas publicas ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/cotacoes,              market-data:/cotacoes:null",
            "/api/cotacoes/BTCUSDT,      market-data:/cotacoes/BTCUSDT:null",
            "/api/jogos/flecha/regras,   trading:/jogos/flecha/regras:null",
            "/api/ordens/regras,         trading:/ordens/regras:null",
            "/api/ordens/barreira,       trading:/ordens/barreira:null"
    })
    void rotasPublicasFuncionamSemToken(String caminho, String esperado) {
        cliente.get().uri(caminho)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(esperado);
    }

    @Test
    void rotaPublicaIgnoraOToken() {
        // token valido: nao propaga identidade (rota publica nao precisa)
        cliente.get().uri("/api/jogos/flecha/regras").headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("trading:/jogos/flecha/regras:null");
        // token vencido ou lixo nao derruba a rota publica
        cliente.get().uri("/api/cotacoes").headers(h -> h.setBearerAuth(tokenVencido()))
                .exchange()
                .expectStatus().isOk();
        cliente.get().uri("/api/cotacoes").header(HttpHeaders.AUTHORIZATION, "Bearer lixo")
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST,   /api/ordens/regras",
            "POST,   /api/ordens/barreira",
            "POST,   /api/jogos/flecha/regras",
            "DELETE, /api/jogos/flecha/regras"
    })
    void rotaPublicaSoValeParaGet(String metodo, String caminho) {
        cliente.method(HttpMethod.valueOf(metodo)).uri(caminho)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- 401 ----------

    @ParameterizedTest
    @ValueSource(strings = {"/api/usuarios", "/api/usuarios/me", "/api/jogos/flecha/sessoes", "/api/ordens",
            "/api/ordens/abc", "/api/carteira", "/api/notificacoes"})
    void semTokenNaRotaProtegidaDa401ProblemJson(String caminho) {
        cliente.get().uri(caminho)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Não autenticado")
                .jsonPath("$.detail").isEqualTo("Token de acesso ausente");
    }

    static Stream<Arguments> tokensInvalidos() {
        Instant agora = Instant.now();
        return Stream.of(
                Arguments.of("assinatura de outra chave", assinar(claims("auth0|ana"), CHAVE_INTRUSA)),
                Arguments.of("kid desconhecido", assinar(claims("auth0|ana"), CHAVE_DESCONHECIDA)),
                Arguments.of("emissor errado", assinar(claims("auth0|ana").issuer("https://outro.auth0.com/"))),
                Arguments.of("audiencia errada", assinar(claims("auth0|ana").audience("https://outra-api"))),
                Arguments.of("sem audiencia", assinar(claims("auth0|ana").audience((String) null))),
                Arguments.of("vencido", tokenVencido()),
                Arguments.of("ainda nao valido", assinar(claims("auth0|ana")
                        .notBeforeTime(Date.from(agora.plusSeconds(600))))),
                Arguments.of("nao e JWT", "abc.def.ghi"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensInvalidos")
    void tokenInvalidoDa401(String caso, String jwt) {
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(jwt))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Token de acesso inválido ou expirado");
    }

    // ---------- conta bloqueada / excluida ----------

    @Test
    void contaBloqueadaDa403() {
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(token("auth0|bloqueado")))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Conta bloqueada");
    }

    @Test
    void contaExcluidaDa403() {
        cliente.get().uri("/api/usuarios/me").headers(h -> h.setBearerAuth(token("auth0|excluido")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Conta excluída");
    }

    @Test
    void contaBloqueadaAindaVeRotaPublica() {
        cliente.get().uri("/api/cotacoes").headers(h -> h.setBearerAuth(token("auth0|bloqueado")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void usuarioAindaNaoProvisionadoPassa() {
        cliente.get().uri("/api/usuarios/me").headers(h -> h.setBearerAuth(token("auth0|novo")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("usuarios:/usuarios/me:auth0|novo");
    }

    @Test
    void outroStatusQualquerPassa() {
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(token("auth0|pendente")))
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth0|falha", "auth0|lento", "auth0|sem-status"})
    void userServiceComProblemaDa503(String sub) {
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(token(sub)))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503);
    }

    @Test
    void erroDoUserServiceNaoFicaEmCache() {
        String jwt = token("auth0|falha-repetida");
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isEqualTo(503);
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isEqualTo(503);
        assertThat(CONSULTAS_STATUS.get("auth0|falha-repetida")).hasValue(2);
    }

    @Test
    void statusFicaEmCacheEOIdVaiCodificado() {
        String jwt = token("auth0|cache");
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isOk();
        cliente.get().uri("/api/ordens").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isOk();
        assertThat(CONSULTAS_STATUS.get("auth0|cache")).hasValue(1);
        assertThat(CAMINHOS_STATUS).contains("/interno/usuarios/auth0%7Ccache/status");
    }

    @Test
    void bloqueioTambemFicaEmCache() {
        String jwt = token("auth0|bloqueado-cache");
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isForbidden();
        cliente.get().uri("/api/carteira").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isForbidden();
        assertThat(CONSULTAS_STATUS.get("auth0|bloqueado-cache")).hasValue(1);
    }

    // ---------- CORS ----------

    @Test
    void corsLiberaOFrontend() {
        cliente.options().uri("/api/ordens")
                .header("Origin", ORIGEM)
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGEM);
    }

    @Test
    void preflightLiberaPatchEAuthorizationSemToken() {
        cliente.options().uri("/api/usuarios/me")
                .header("Origin", ORIGEM)
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGEM)
                .expectHeader().value("Access-Control-Allow-Methods", v -> assertThat(v).contains("PATCH"))
                .expectHeader().value("Access-Control-Allow-Headers",
                        v -> assertThat(v.toLowerCase()).contains("authorization"));
    }

    @Test
    void preflightDeOrigemDesconhecidaDa403() {
        cliente.options().uri("/api/carteira")
                .header("Origin", "http://site-malicioso.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void respostas401E200TrazemCors() {
        cliente.get().uri("/api/carteira").header("Origin", ORIGEM)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGEM);
        cliente.get().uri("/api/carteira").header("Origin", ORIGEM).headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGEM);
    }

    // ---------- caminhos sem rota, actuator e caminhos suspeitos ----------

    @Test
    void caminhoSemRota() {
        // dentro de /api: sem token 401 (nao revela o que existe), com token 404
        cliente.get().uri("/api/inexistente").exchange().expectStatus().isUnauthorized();
        cliente.get().uri("/api/inexistente").headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange().expectStatus().isNotFound();
        // fora de /api: sempre 404, com ou sem token (valido ou nao)
        cliente.get().uri("/ordens").exchange().expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        cliente.get().uri("/ordens").headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange().expectStatus().isNotFound();
        cliente.get().uri("/ordens").headers(h -> h.setBearerAuth(tokenVencido()))
                .exchange().expectStatus().isNotFound();
        cliente.get().uri("/interno/usuarios/x/status").exchange().expectStatus().isNotFound();
        cliente.get().uri("/").exchange().expectStatus().isNotFound();
        cliente.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }

    @Test
    void actuatorPublico() {
        cliente.get().uri("/actuator/health").exchange().expectStatus().isOk();
        cliente.get().uri("/actuator/info").exchange().expectStatus().isOk();
        cliente.get().uri("/actuator/health").headers(h -> h.setBearerAuth(tokenVencido()))
                .exchange().expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/usuarios/../interno/usuarios/x/status",
            "/api/usuarios/..;/interno/usuarios/x/status",
            "/api/usuarios/%2e%2e/interno/usuarios/x/status",
            "/api/usuarios/%2E%2E%2Finterno%2Fusuarios",
            "/api/usuarios/x%5C..%5Cinterno",
            "/api/cotacoes/./BTCUSDT",
            "/api/cotacoes;x=1"})
    void caminhoQueTentaSairDaRotaDa400(String caminho) {
        int consultasAntes = CAMINHOS_STATUS.size();
        cliente.get().uri(URI.create("http://localhost:" + porta + caminho))
                .headers(h -> h.setBearerAuth(token("auth0|ana")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(CAMINHOS_STATUS).hasSize(consultasAntes);
    }

    // ---------- apoio: tokens ----------

    private static RSAKey gerarChave(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder claims(String sub) {
        Instant agora = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(EMISSOR)
                .audience(List.of(AUDIENCIA, EMISSOR + "userinfo"))
                .issueTime(Date.from(agora))
                .expirationTime(Date.from(agora.plusSeconds(300)));
    }

    private static String assinar(JWTClaimsSet.Builder claims) {
        return assinar(claims, CHAVE);
    }

    private static String assinar(JWTClaimsSet.Builder claims, RSAKey chave) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chave.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(chave));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(String sub) {
        return assinar(claims(sub));
    }

    private static String tokenVencido() {
        Instant agora = Instant.now();
        return assinar(claims("auth0|ana")
                .issueTime(Date.from(agora.minusSeconds(3600)))
                .expirationTime(Date.from(agora.minusSeconds(600))));
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }

    // ---------- apoio: servidores falsos ----------

    private static HttpServer servidor(Map<String, HttpHandler> contextos) {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            contextos.forEach(servidor::createContext);
            servidor.setExecutor(Executors.newCachedThreadPool());
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(HttpServer s) {
        return "http://localhost:" + s.getAddress().getPort();
    }

    /** Responde "servico:caminho:X-Usuario-Id" e devolve os demais X-Usuario-* recebidos em Eco-*. */
    private static HttpHandler eco(String nome) {
        return troca -> {
            Headers recebidos = troca.getRequestHeaders();
            List<String> ids = recebidos.get("X-Usuario-Id");
            String usuario = ids == null ? "null" : String.join(",", ids);
            for (String campo : List.of("Email", "Nome", "Avatar", "Papel")) {
                List<String> valores = recebidos.get("X-Usuario-" + campo);
                if (valores != null) {
                    troca.getResponseHeaders().set("Eco-" + campo, String.join(",", valores));
                }
            }
            responder(troca, 200, nome + ":" + troca.getRequestURI() + ":" + usuario);
        };
    }

    /** user-service falso: GET /interno/usuarios/{id}/status conforme o sub. */
    private static void statusDaConta(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getRawPath();
        CAMINHOS_STATUS.add(caminho);
        String id = URLDecoder.decode(caminho.split("/")[3], StandardCharsets.UTF_8);
        CONSULTAS_STATUS.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        troca.getResponseHeaders().set("Content-Type", "application/json");
        switch (id) {
            case "auth0|bloqueado", "auth0|bloqueado-cache" -> responder(troca, 200, "{\"status\":\"BLOQUEADO\"}");
            case "auth0|excluido" -> responder(troca, 200, "{\"status\":\"EXCLUIDO\"}");
            case "auth0|pendente" -> responder(troca, 200, "{\"status\":\"QUALQUER\"}");
            case "auth0|novo" -> responder(troca, 404, "{\"title\":\"Not Found\"}");
            case "auth0|falha", "auth0|falha-repetida" -> responder(troca, 500, "{\"title\":\"erro\"}");
            case "auth0|sem-status" -> responder(troca, 200, "{}");
            case "auth0|lento" -> {
                dormir(1500);
                responder(troca, 200, "{\"status\":\"ATIVO\"}");
            }
            default -> responder(troca, 200, "{\"status\":\"ATIVO\"}");
        }
    }

    private static void responder(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.sendResponseHeaders(status, bytes.length);
        troca.getResponseBody().write(bytes);
        troca.close();
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
