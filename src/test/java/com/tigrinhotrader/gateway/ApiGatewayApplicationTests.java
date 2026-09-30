package com.tigrinhotrader.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Sobe o gateway apontando cada rota para um servidor HTTP falso que responde
 * "servico:caminho:usuario", e confere que cada /api/... chega no servico certo, sem o prefixo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

    private static final HttpServer TRADING = servidorFalso("trading");
    private static final HttpServer WALLET = servidorFalso("wallet");
    private static final HttpServer MARKET_DATA = servidorFalso("market-data");
    private static final HttpServer NOTIFICATION = servidorFalso("notification");

    @Autowired
    private WebTestClient cliente;

    private static HttpServer servidorFalso(String nome) {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            servidor.createContext("/", troca -> {
                String usuario = troca.getRequestHeaders().getFirst("X-Usuario-Id");
                byte[] corpo = (nome + ":" + troca.getRequestURI() + ":" + usuario).getBytes(StandardCharsets.UTF_8);
                troca.sendResponseHeaders(200, corpo.length);
                troca.getResponseBody().write(corpo);
                troca.close();
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(HttpServer s) {
        return "http://localhost:" + s.getAddress().getPort();
    }

    @DynamicPropertySource
    static void rotas(DynamicPropertyRegistry registro) {
        registro.add("TRADING_URL", () -> url(TRADING));
        registro.add("WALLET_URL", () -> url(WALLET));
        registro.add("MARKET_DATA_URL", () -> url(MARKET_DATA));
        registro.add("NOTIFICATION_URL", () -> url(NOTIFICATION));
    }

    @AfterAll
    static void pararServidores() {
        TRADING.stop(0);
        WALLET.stop(0);
        MARKET_DATA.stop(0);
        NOTIFICATION.stop(0);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/ordens,               trading:/ordens:u1",
            "/api/ordens/abc-123,       trading:/ordens/abc-123:u1",
            "/api/carteira/extrato,     wallet:/carteira/extrato:u1",
            "/api/cotacoes/BTCUSDT,     market-data:/cotacoes/BTCUSDT:u1",
            "/api/notificacoes,         notification:/notificacoes:u1"
    })
    void roteiaParaOServicoCertoSemPrefixo(String caminho, String esperado) {
        cliente.get().uri(caminho).header("X-Usuario-Id", "u1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(esperado);
    }

    @Test
    void caminhoSemRotaDa404() {
        cliente.get().uri("/api/inexistente").exchange().expectStatus().isNotFound();
        cliente.get().uri("/ordens").exchange().expectStatus().isNotFound();
    }

    @Test
    void corsLiberaOFrontend() {
        cliente.options().uri("/api/ordens")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    void healthDoActuator() {
        cliente.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
