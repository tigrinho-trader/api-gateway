package com.tigrinhotrader.gateway.painel;

import com.tigrinhotrader.gateway.config.PainelPropriedades;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Token do login do painel: {@code base64url("usuario|expiraEmSegundos") + "." + base64url(HMAC-SHA256)} com o
 * segredo do painel. Sem estado no servidor; vale ate expirar (ou ate trocar o segredo).
 */
@Component
public class TokenPainel {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final byte[] segredo;

    public TokenPainel(PainelPropriedades propriedades) {
        this.segredo = propriedades.segredo().getBytes(StandardCharsets.UTF_8);
    }

    public String emitir(String usuario, Instant expiraEm) {
        String dados = B64.encodeToString((usuario + "|" + expiraEm.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return dados + "." + B64.encodeToString(assinar(dados));
    }

    /** O usuario do token, se a assinatura confere e ele ainda vale. */
    public Optional<String> validar(String token, Instant agora) {
        if (token == null || segredo.length < 32) {
            return Optional.empty();
        }
        int ponto = token.indexOf('.');
        if (ponto <= 0 || ponto != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        String dados = token.substring(0, ponto);
        try {
            byte[] assinatura = Base64.getUrlDecoder().decode(token.substring(ponto + 1));
            if (!MessageDigest.isEqual(assinar(dados), assinatura)) {
                return Optional.empty();
            }
            String[] partes = new String(Base64.getUrlDecoder().decode(dados), StandardCharsets.UTF_8).split("\\|");
            if (partes.length != 2 || partes[0].isBlank()
                    || !agora.isBefore(Instant.ofEpochSecond(Long.parseLong(partes[1])))) {
                return Optional.empty();
            }
            return Optional.of(partes[0]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] assinar(String dados) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.length == 0 ? new byte[1] : segredo, "HmacSHA256"));
            return mac.doFinal(dados.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC indisponivel", e);
        }
    }
}
