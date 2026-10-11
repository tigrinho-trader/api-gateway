# api-gateway

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Porta de entrada unica da aplicacao (Spring Cloud Gateway). Valida o token JWT emitido pelo Auth0 (OAuth2) antes de rotear a chamada pro servico correto e repassa a identidade do usuario aos servicos. E o unico ponto que o frontend enxerga; nenhum servico interno e exposto direto.

## Como interage com os outros servicos

- Roteia (sincrono, REST) para user-service, trading-service, wallet-service, market-data-service e notification-service.
- Valida o JWT do Auth0 nas rotas protegidas e repassa a identidade do usuario em cabecalhos `X-Usuario-*`.
- Consulta o user-service (`/interno/usuarios/{id}/status`) para barrar contas bloqueadas ou excluidas.

## Autenticacao (Auth0)

Fluxo:

1. O frontend faz login no Auth0 (Universal Login) e recebe um **access token** (JWT RS256) para a API
   `AUTH0_AUDIENCE`.
2. Toda chamada ao gateway leva `Authorization: Bearer <access token>`.
3. O gateway valida o token: assinatura (chaves publicas do JWKS do Auth0, buscadas so na primeira validacao e
   guardadas em cache), emissor (`iss`), audiencia (`aud`) e validade (`exp`/`nbf`, 60 s de tolerancia).
   Token ausente ou invalido numa rota protegida -> **401** `application/problem+json`.
4. O gateway **apaga qualquer cabecalho `X-Usuario-*` mandado pelo cliente** (em todas as rotas) e, se a
   requisicao esta autenticada, preenche os cabecalhos de identidade a partir do token.
5. Antes de rotear, confere se a conta nao esta bloqueada/excluida (ver abaixo).
6. Os servicos confiam nos cabecalhos (so o gateway e exposto) e decodificam com `URLDecoder.decode(v, UTF_8)`.

### Cabecalhos de identidade

| Cabecalho | Valor |
|---|---|
| `X-Usuario-Id` | claim `sub` (ex.: `auth0\|abc123`, `google-oauth2\|123`), sem codificacao |
| `X-Usuario-Email` | claim `<ns>email` ou, se nao houver, `email`; URL-encoded UTF-8; omitido se nao houver |
| `X-Usuario-Nome` | claim `<ns>name` ou `name`; URL-encoded UTF-8; omitido se nao houver |
| `X-Usuario-Avatar` | claim `<ns>picture` ou `picture`; URL-encoded UTF-8; omitido se nao houver |

`<ns>` = `AUTH0_CLAIMS_NAMESPACE` (padrao `https://tigrinho-trader/`). O Auth0 nao coloca email/nome no access
token por padrao: uma Action pos-login adiciona essas claims com o namespace, por exemplo:

```js
exports.onExecutePostLogin = async (event, api) => {
  const ns = 'https://tigrinho-trader/';
  api.accessToken.setCustomClaim(ns + 'email', event.user.email);
  api.accessToken.setCustomClaim(ns + 'name', event.user.name);
  api.accessToken.setCustomClaim(ns + 'picture', event.user.picture);
};
```

### Conta bloqueada / excluida

Depois de autenticar (so em rotas protegidas), o gateway consulta
`GET {USER_URL}/interno/usuarios/{sub URL-encoded}/status`:

| Resposta do user-service | Gateway |
|---|---|
| `200 {"status":"ATIVO"}` (ou outro status) | segue para o servico |
| `200 {"status":"BLOQUEADO"}` | **403** "Conta bloqueada" |
| `200 {"status":"EXCLUIDO"}` | **403** "Conta excluída" |
| `404` (usuario ainda nao provisionado, ex.: primeiro login) | segue |
| erro, resposta sem status ou demora maior que `AUTH_STATUS_TIMEOUT` | **503** (falha fechada) |

As respostas validas (inclusive o 404) ficam num cache em memoria por `sub` durante `AUTH_STATUS_CACHE`;
erros nao entram no cache. Ou seja: um bloqueio leva ate esse tempo para valer no gateway.

## Rotas

| Caminho publico | Servico | Caminho interno | Autenticacao |
|---|---|---|---|
| `/api/usuarios`, `/api/usuarios/**` | user-service (8085) | `/usuarios/**` | JWT |
| `GET /api/jogos/flecha/regras` | trading-service (8081) | `/jogos/flecha/regras` | publica |
| `/api/jogos/**` | trading-service (8081) | `/jogos/**` | JWT |
| `GET /api/ordens/regras`, `GET /api/ordens/barreira` | trading-service (8081) | `/ordens/...` | publica |
| `/api/ordens/**` | trading-service (8081) | `/ordens/**` | JWT |
| `/api/carteira/**` | wallet-service (8082) | `/carteira/**` | JWT |
| `/api/cotacoes/**` | market-data-service (8083) | `/cotacoes/**` | publica |
| `/api/notificacoes/**` | notification-service (8084) | `/notificacoes/**` | JWT |
| `/actuator/health`, `/actuator/info` | o proprio gateway | - | publica |
| `OPTIONS` (preflight CORS) | - | - | publica |

- Nas rotas publicas o token e ignorado (um token vencido nao derruba a rota e nenhuma identidade e repassada).
- Caminho sem rota dentro de `/api/**`: sem token **401**; com token **404**. Fora de `/api/**` (e fora do
  actuator publico) e sempre **404**, com ou sem token.
- Nenhuma rota externa leva a `/interno/**` dos servicos: caminhos com segmentos `.`/`..`, `;`, `\` ou
  `%2e`/`%2f`/`%5c` sao recusados com **400** antes de rotear.
- CORS: a origem do frontend (`FRONTEND_ORIGINS`) pode usar GET, POST, PUT, PATCH, DELETE e OPTIONS com
  qualquer cabecalho (inclusive `Authorization`); as respostas 401 tambem levam os cabecalhos CORS.
- Sem sessao e sem CSRF (API stateless, autenticada so pelo Bearer token).

## Variaveis de ambiente

| Variavel | Padrao | Para que serve |
|---|---|---|
| `AUTH0_DOMAIN` | `tigrinho-trader.us.auth0.com` | tenant do Auth0 |
| `AUTH0_AUDIENCE` | `https://api.tigrinho-trader` | identificador da API no Auth0 (claim `aud`) |
| `AUTH0_ISSUER` | `https://${AUTH0_DOMAIN}/` | emissor esperado (claim `iss`) |
| `AUTH0_JWKS_URI` | `https://${AUTH0_DOMAIN}/.well-known/jwks.json` | chaves publicas do Auth0 |
| `AUTH0_CLAIMS_NAMESPACE` | `https://tigrinho-trader/` | prefixo das claims customizadas |
| `USER_URL` | `http://localhost:8085` | user-service (rota `/api/usuarios` e checagem de status) |
| `AUTH_STATUS_CACHE` | `30s` | TTL do cache de status de conta |
| `AUTH_STATUS_TIMEOUT` | `2s` | tempo maximo da consulta de status |
| `TRADING_URL` | `http://localhost:8081` | trading-service |
| `WALLET_URL` | `http://localhost:8082` | wallet-service |
| `MARKET_DATA_URL` | `http://localhost:8083` | market-data-service |
| `NOTIFICATION_URL` | `http://localhost:8084` | notification-service |
| `FRONTEND_ORIGINS` | `http://localhost:5173` | origens liberadas no CORS (separadas por virgula) |

## Subindo a aplicacao inteira

Com os repos clonados lado a lado (`api-gateway`, `trading-service`, `wallet-service`, `market-data-service`, `user-service`, `frontend`):

```bash
docker compose up --build
# sem acesso a Binance:
SIMULADOR=true BINANCE=false docker compose up --build
```

Para o login funcionar, exporte (ou ponha num `.env` ao lado do `docker-compose.yml`) os dados do Auth0:
`AUTH0_DOMAIN`, `AUTH0_AUDIENCE`, `AUTH0_CLIENT_ID` (aplicacao SPA; vai para o build do frontend como
`VITE_AUTH0_*`) e, se quiser, `ADMIN_EMAILS=voce@exemplo.com` (vira ADMIN no primeiro login).

Exemplo de uma rodada (pegue um access token do Auth0: no frontend logado ou no painel do Auth0, em
*APIs > sua API > Test*):

```bash
export TOKEN=eyJhbGciOiJSUzI1NiIs...
curl localhost:8080/api/cotacoes                                   # publica, sem token
curl -i localhost:8080/api/carteira                                # 401 (sem token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/usuarios/me
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/carteira
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"simbolo":"BTCUSDT","tipo":"ALTA","valor":100,"duracaoSegundos":15}' localhost:8080/api/ordens
# ~15s depois
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/ordens
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/carteira/extrato
```

Mandar `X-Usuario-Id` direto nao funciona mais: o gateway descarta esse cabecalho.

Painel do RabbitMQ: http://localhost:15672 (guest/guest).

Porta 8080 ocupada na sua maquina? `GATEWAY_PORT=8090 docker compose up --build`.

### Teste de integracao

Com o ambiente no ar, roda uma rodada completa passando por todos os servicos e pelas duas filas
(`preco.atualizado` e `ordem.executada`) e sai com codigo 1 se algo falhar. Precisa de um access token em `TOKEN`
(o script manda `Authorization: Bearer $TOKEN`):

```bash
TOKEN=eyJ... ./scripts/teste-integracao.sh
TOKEN=eyJ... GATEWAY_URL=http://localhost:8090 ./scripts/teste-integracao.sh   # se mudou a porta
```

### Testes automatizados

`mvn verify` sobe o gateway com servidores HTTP falsos no lugar dos servicos, do user-service e do JWKS do Auth0
(par de chaves RSA gerado no proprio teste) e exige cobertura JaCoCo de 80% de linhas e 75% de branches.

## Repositorios do projeto

- [user-service](https://github.com/tigrinho-trader/user-service)
- [trading-service](https://github.com/tigrinho-trader/trading-service)
- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
