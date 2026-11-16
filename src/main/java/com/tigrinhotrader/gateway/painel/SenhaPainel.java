package com.tigrinhotrader.gateway.painel;

import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Confere a senha do painel contra o hash guardado no ambiente: {@code pbkdf2_sha256:<iteracoes>:<sal>:<hash>}, sal e
 * hash em base64url sem padding (o formato que {@code scripts/senha_painel.py} gera). Comparacao em tempo constante.
 */
public final class SenhaPainel {

    static final String ALGORITMO = "pbkdf2_sha256";

    private SenhaPainel() {
    }

    public static boolean confere(String senha, String hashGuardado) {
        if (senha == null || hashGuardado == null) {
            return false;
        }
        String[] partes = hashGuardado.split(":");
        if (partes.length != 4 || !ALGORITMO.equals(partes[0])) {
            return false;
        }
        try {
            int iteracoes = Integer.parseInt(partes[1]);
            byte[] sal = Base64.getUrlDecoder().decode(partes[2]);
            byte[] esperado = Base64.getUrlDecoder().decode(partes[3]);
            if (iteracoes < 1 || esperado.length == 0) {
                return false;
            }
            return MessageDigest.isEqual(esperado, derivar(senha, sal, iteracoes, esperado.length));
        } catch (IllegalArgumentException e) {
            return false; // hash mal formado
        }
    }

    /** Gera um hash no mesmo formato (usado nos testes; em producao o hash vem do script). */
    static String gerar(String senha, byte[] sal, int iteracoes) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return ALGORITMO + ":" + iteracoes + ":" + b64.encodeToString(sal) + ":"
                + b64.encodeToString(derivar(senha, sal, iteracoes, 32));
    }

    private static byte[] derivar(String senha, byte[] sal, int iteracoes, int bytes) {
        try {
            KeySpec spec = new PBEKeySpec(senha.toCharArray(), sal, iteracoes, bytes * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponivel", e);
        }
    }
}
