package com.tigrinhotrader.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login do painel do super-admin ({@code tigrinho.painel.*}). A senha nunca fica no ambiente: so o hash dela
 * (PBKDF2-SHA256), gerado por {@code scripts/senha_painel.py}. Sem hash ou sem segredo o painel fica desligado.
 *
 * @param usuario nome de login do painel (PAINEL_USUARIO, padrao super-admin-oscar)
 * @param senhaHash {@code pbkdf2_sha256:<iteracoes>:<sal>:<hash>} em base64url (PAINEL_SENHA_HASH)
 * @param segredo chave que assina os tokens do painel, com pelo menos 32 caracteres (PAINEL_SEGREDO)
 * @param validade quanto tempo o login do painel vale (PAINEL_VALIDADE, padrao 8h)
 */
@ConfigurationProperties(prefix = "tigrinho.painel")
public record PainelPropriedades(String usuario, String senhaHash, String segredo, Duration validade) {

    public PainelPropriedades {
        usuario = usuario == null || usuario.isBlank() ? "super-admin-oscar" : usuario.trim();
        senhaHash = senhaHash == null ? "" : senhaHash.trim();
        segredo = segredo == null ? "" : segredo.trim();
        validade = validade == null || validade.isNegative() || validade.isZero() ? Duration.ofHours(8) : validade;
    }

    public boolean habilitado() {
        return !senhaHash.isEmpty() && segredo.length() >= 32;
    }

    @Override
    public String toString() {
        return "PainelPropriedades[usuario=" + usuario + ", habilitado=" + habilitado() + ", validade=" + validade + "]";
    }
}
