package com.tigrinhotrader.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Confere o reino importado pelo Keycloak do docker-compose (infra/keycloak/tigrinho-realm.json), para o
 * arquivo nao quebrar em silencio: o gateway e o frontend dependem desses valores.
 */
class ReinoKeycloakTest {

    private static JsonNode reino;

    @BeforeAll
    static void carregar() throws IOException {
        reino = new ObjectMapper().readTree(Path.of("infra", "keycloak", "tigrinho-realm.json").toFile());
    }

    @Test
    void reinoComCadastroLoginPorEmailEPortugues() {
        assertThat(reino.path("realm").asText()).isEqualTo("tigrinho");
        assertThat(reino.path("enabled").asBoolean()).isTrue();
        assertThat(reino.path("sslRequired").asText()).isEqualTo("none");
        assertThat(reino.path("registrationAllowed").asBoolean()).isTrue();
        assertThat(reino.path("loginWithEmailAllowed").asBoolean()).isTrue();
        assertThat(reino.path("verifyEmail").asBoolean(true)).isFalse();
        assertThat(reino.path("internationalizationEnabled").asBoolean()).isTrue();
        assertThat(reino.path("defaultLocale").asText()).isEqualTo("pt-BR");
        assertThat(textos(reino.path("supportedLocales"))).containsExactlyInAnyOrder("pt-BR", "en");
    }

    @Test
    void clienteDoJogoEPublicoComPkce() {
        JsonNode cliente = clienteDoJogo();
        assertThat(cliente.path("enabled").asBoolean()).isTrue();
        assertThat(cliente.path("publicClient").asBoolean()).isTrue();
        assertThat(cliente.has("secret")).isFalse();
        assertThat(cliente.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(cliente.path("implicitFlowEnabled").asBoolean(true)).isFalse();
        assertThat(cliente.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(cliente.path("attributes").path("pkce.code.challenge.method").asText()).isEqualTo("S256");
        assertThat(textos(cliente.path("defaultClientScopes")))
                .contains("basic", "profile", "email", "web-origins", "roles", "acr");
    }

    @Test
    void enderecosDoFrontendLiberados() {
        JsonNode cliente = clienteDoJogo();
        List<String> origens = List.of("http://localhost:5173", "http://127.0.0.1:5173",
                "http://localhost:3000", "http://127.0.0.1:3000");
        List<String> redirects = textos(cliente.path("redirectUris"));
        origens.forEach(o -> assertThat(redirects).contains(o, o + "/*"));
        assertThat(textos(cliente.path("webOrigins"))).containsExactlyInAnyOrderElementsOf(origens);
        List<String> posLogout = List.of(cliente.path("attributes").path("post.logout.redirect.uris")
                .asText().split("##"));
        origens.forEach(o -> assertThat(posLogout).contains(o + "/*"));
    }

    @Test
    void accessTokenLevaAAudienciaDaApi() {
        JsonNode mapper = null;
        for (JsonNode m : clienteDoJogo().path("protocolMappers")) {
            if ("oidc-audience-mapper".equals(m.path("protocolMapper").asText())) {
                mapper = m;
            }
        }
        assertThat(mapper).as("mapper de audiencia").isNotNull();
        JsonNode config = mapper.path("config");
        assertThat(config.path("included.custom.audience").asText()).isEqualTo("tigrinho-api");
        assertThat(config.path("access.token.claim").asText()).isEqualTo("true");
        assertThat(config.path("id.token.claim").asText()).isEqualTo("false");
    }

    @Test
    void usuarioDeDesenvolvimento() {
        JsonNode usuario = null;
        for (JsonNode u : reino.path("users")) {
            if ("admin".equals(u.path("username").asText())) {
                usuario = u;
            }
        }
        assertThat(usuario).as("usuario admin").isNotNull();
        assertThat(usuario.path("enabled").asBoolean()).isTrue();
        assertThat(usuario.path("email").asText()).isEqualTo("admin@tigrinho.local");
        assertThat(usuario.path("emailVerified").asBoolean()).isTrue();
        // firstName/lastName preenchem a claim name (e evitam o "complete seu perfil" no primeiro login)
        assertThat(usuario.path("firstName").asText()).isNotBlank();
        assertThat(usuario.path("lastName").asText()).isNotBlank();
        JsonNode senha = usuario.path("credentials").get(0);
        assertThat(senha.path("type").asText()).isEqualTo("password");
        assertThat(senha.path("value").asText()).isEqualTo("admin123");
        assertThat(senha.path("temporary").asBoolean(true)).isFalse();
    }

    @Test
    void telasDeLoginComOTemaDoJogo() throws IOException {
        assertThat(reino.path("loginTheme").asText()).isEqualTo("flecha-certeira");
        assertThat(reino.path("displayName").asText()).isEqualTo("Flecha Certeira");

        Path tema = Path.of("infra", "keycloak", "themes", "flecha-certeira", "login");
        String propriedades = Files.readString(tema.resolve("theme.properties"));
        assertThat(propriedades).contains("parent=keycloak.v2").contains("css/styles.css css/flecha.css");
        assertThat(tema.resolve("resources/css/flecha.css")).exists();
        assertThat(Files.readString(tema.resolve("resources/img/cenario.svg"))).startsWith("<svg");
        assertThat(Files.readString(tema.resolve("messages/messages_pt_BR.properties")))
                .contains("loginAccountTitle=Entrar no jogo");
    }

    @Test
    void loginSocialConfiguradoPeloScriptSoComCredenciais() throws IOException {
        String script = Files.readString(Path.of("infra", "keycloak", "configurar-reino.sh"));
        assertThat(script).contains("configurar google google")
                .contains("configurar microsoft microsoft")
                .contains("GOOGLE_CLIENT_ID").contains("MICROSOFT_CLIENT_SECRET")
                .contains("trustEmail=true")
                .contains("sem credenciais no .env");
        // o script roda em Linux: CRLF quebraria o bash
        assertThat(script).doesNotContain("\r");
    }

    private static JsonNode clienteDoJogo() {
        for (JsonNode c : reino.path("clients")) {
            if ("flecha-certeira".equals(c.path("clientId").asText())) {
                return c;
            }
        }
        throw new AssertionError("cliente flecha-certeira nao encontrado no reino");
    }

    private static List<String> textos(JsonNode lista) {
        List<String> valores = new ArrayList<>();
        lista.forEach(v -> valores.add(v.asText()));
        return valores;
    }
}
