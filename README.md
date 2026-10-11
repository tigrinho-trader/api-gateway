# api-gateway

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Porta de entrada unica da aplicacao (Spring Cloud Gateway). Valida o token JWT emitido pelo provedor OpenID Connect
(por padrao um Keycloak local; Auth0 ou outro so por configuracao) antes de rotear a chamada pro servico correto e repassa a identidade do usuario aos servicos. E o unico ponto que o frontend enxerga; nenhum servico interno e exposto direto.


### Tela de login com a cara do jogo

O reino usa o tema `flecha-certeira` (`infra/keycloak/themes/flecha-certeira/login`): herda o `keycloak.v2` e troca
so o visual (cenario do Vilarejo de fundo, card de madeira, fontes e botoes do jogo, textos como "Entrar no jogo").
O Keycloak em `start-dev` nao faz cache de tema: editar o `flecha.css` ou os textos vale ao recarregar a pagina.

## Como interage com os outros servicos

- Roteia (sincrono, REST) para user-service, trading-service, wallet-service, market-data-service e notification-service.
- Valida o JWT do provedor OpenID Connect nas rotas protegidas e repassa a identidade do usuario em cabecalhos `X-Usuario-*`.
- Consulta o user-service (`/interno/usuarios/{id}/status`) para barrar contas bloqueadas ou excluidas.

## Autenticacao (OpenID Connect)

O gateway aceita access tokens de qualquer provedor OpenID Connect que assine com RS256 e publique um JWKS.
Por padrao e o **Keycloak local** do `docker-compose.yml` (reino `tigrinho`); o Auth0 continua funcionando so
trocando variaveis de ambiente (ver [Usando o Auth0](#usando-o-auth0-em-vez-do-keycloak)).

Fluxo:

1. O frontend faz login no provedor (Authorization Code + PKCE) e recebe um **access token** (JWT RS256) cuja
   claim `aud` contem `AUTH_AUDIENCE` (no Keycloak: `tigrinho-api`).
2. Toda chamada ao gateway leva `Authorization: Bearer <access token>`.
3. O gateway valida o token: assinatura (chaves publicas do JWKS em `AUTH_JWKS_URI`, buscadas so na primeira
   validacao e guardadas em cache), emissor (`iss` = `AUTH_ISSUER`), audiencia (`aud` contem `AUTH_AUDIENCE`) e
   validade (`exp`/`nbf`, 60 s de tolerancia). Token ausente ou invalido numa rota protegida -> **401**
   `application/problem+json`.
4. O gateway **apaga qualquer cabecalho `X-Usuario-*` mandado pelo cliente** (em todas as rotas) e, se a
   requisicao esta autenticada, preenche os cabecalhos de identidade a partir do token.
5. Antes de rotear, confere se a conta nao esta bloqueada/excluida (ver abaixo).
6. Os servicos confiam nos cabecalhos (so o gateway e exposto) e decodificam com `URLDecoder.decode(v, UTF_8)`.

### Cabecalhos de identidade

| Cabecalho | Valor |
|---|---|
| `X-Usuario-Id` | claim `sub` (Keycloak: UUID; Auth0: `auth0\|abc123`, `google-oauth2\|123`), sem codificacao |
| `X-Usuario-Email` | claim `<ns>email` ou, se nao houver, `email`; URL-encoded UTF-8; omitido se nao houver **ou se `email_verified` vier `false`** |
| `X-Usuario-Nome` | claim `<ns>name` ou `name`; se nao houver, `preferred_username`; URL-encoded UTF-8; omitido se nao houver |
| `X-Usuario-Avatar` | claim `<ns>picture` ou `picture`; URL-encoded UTF-8; omitido se nao houver |

`<ns>` = `AUTH_CLAIMS_NAMESPACE` (padrao `https://tigrinho-trader/`). As claims com namespace sao do Auth0 e tem
prioridade; o Keycloak manda as claims OIDC padrao (`email`, `email_verified`, `name`, `preferred_username`).

**E-mail nao verificado:** se a claim `email_verified` (ou `<ns>email_verified`) existir e for `false` (booleano
ou o texto `"false"`), o gateway nao envia `X-Usuario-Email`. Isso protege o `ADMIN_EMAILS` do user-service:
ninguem vira ADMIN se cadastrando com o e-mail de outra pessoa. Sem a claim, o e-mail segue normalmente.
No Keycloak local nao ha servidor de e-mail (`verifyEmail: false`), entao **quem se cadastra pela tela de
registro fica com `email_verified = false`** e entra sem e-mail no user-service (o apelido sai do nome).
Para marcar um e-mail como verificado: console do Keycloak > reino `tigrinho` > *Users* > usuario >
*Email verified*.

## Keycloak local (desenvolvimento)

O `docker-compose.yml` sobe o servico `keycloak` (`quay.io/keycloak/keycloak:26.0.7`, `start-dev --import-realm`)
na porta **8180** e importa o reino de `infra/keycloak/tigrinho-realm.json` na primeira subida.

| Item | Valor |
|---|---|
| Emissor (`iss`) | `http://localhost:8180/realms/tigrinho` (fixo por `KC_HOSTNAME`, mesmo quando o gateway acessa por `keycloak:8080`) |
| JWKS | `http://localhost:8180/realms/tigrinho/protocol/openid-connect/certs` (no compose: `http://keycloak:8080/...`) |
| Cliente do jogo | `flecha-certeira`: publico, Authorization Code + PKCE S256, sem client secret |
| Audiencia da API | `tigrinho-api` (mapper de audiencia do cliente, so no access token) |
| Redirect / web origins | `http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:3000`, `http://127.0.0.1:3000` |
| Cadastro | tela "Registrar" liberada, login com usuario ou e-mail, sem verificacao de e-mail, idioma `pt-BR` |

- **Login do jogo:** abra o frontend (`http://localhost:3000` no compose ou `http://localhost:5173` no
  `npm run dev`) e clique em entrar; voce cai na tela de login do Keycloak. A conta do usuario (perfil, senha)
  fica em http://localhost:8180/realms/tigrinho/account.
- **Usuario de desenvolvimento:** `admin` / senha `admin123` (e-mail `admin@tigrinho.local`, ja verificado). No
  compose o `ADMIN_EMAILS` do user-service ja vem com esse e-mail, entao ele vira ADMIN no primeiro login.
- **Console de administracao do Keycloak:** http://localhost:8180 com `admin` / `admin` (reino `master`; troque
  para o reino `tigrinho` no seletor para ver usuarios e o cliente).
- **Essas senhas sao so para a maquina local.** Nao use este reino nem o `start-dev` em servidor exposto.
- O reino so e importado se ainda nao existir. Mudou o `tigrinho-realm.json`? Recrie o container:
  `docker compose up -d --force-recreate keycloak` (o `start-dev` guarda os dados dentro do container).

## Usando o Auth0 em vez do Keycloak

Basta trocar as variaveis (no shell ou no `.env` ao lado do `docker-compose.yml`):

```bash
AUTH_ISSUER=https://<tenant>.us.auth0.com/
AUTH_JWKS_URI=https://<tenant>.us.auth0.com/.well-known/jwks.json
AUTH_AUDIENCE=https://api.tigrinho-trader
# frontend (variaveis de build do Vite)
VITE_OIDC_AUTHORITY=https://<tenant>.us.auth0.com/
VITE_OIDC_CLIENT_ID=<client id da aplicacao SPA>
VITE_OIDC_AUDIENCE=https://api.tigrinho-trader
VITE_OIDC_PROVEDOR=auth0
```

O Auth0 nao coloca email/nome no access token por padrao: uma Action pos-login adiciona essas claims com o
namespace, por exemplo:

```js
exports.onExecutePostLogin = async (event, api) => {
  const ns = 'https://tigrinho-trader/';
  api.accessToken.setCustomClaim(ns + 'email', event.user.email);
  api.accessToken.setCustomClaim(ns + 'email_verified', event.user.email_verified);
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
| `AUTH_ISSUER` | `http://localhost:8180/realms/tigrinho` | emissor esperado (claim `iss`) |
| `AUTH_JWKS_URI` | `${AUTH_ISSUER}/protocol/openid-connect/certs` | chaves publicas do provedor (JWKS) |
| `AUTH_AUDIENCE` | `tigrinho-api` | valor que precisa estar na claim `aud` |
| `AUTH_CLAIMS_NAMESPACE` | `https://tigrinho-trader/` | prefixo das claims customizadas (Auth0) |
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

O login funciona sem configurar nada: o compose sobe o Keycloak local (ver
[Keycloak local](#keycloak-local-desenvolvimento)). `ADMIN_EMAILS=voce@exemplo.com` (padrao
`admin@tigrinho.local`) vira ADMIN no primeiro login.

### Pegando um access token para testar com curl

O cliente `flecha-certeira` e publico e so aceita Authorization Code + PKCE: o *password grant*
(`grant_type=password`) fica **desligado** de proposito, entao nao da para pedir token direto com `curl` usuario
e senha. O jeito simples e copiar o token da sessao do navegador:

1. Abra o jogo (`http://localhost:3000`) e entre com `admin` / `admin123`.
2. Abra o DevTools (F12) > aba **Network** (Rede), filtre por `api/`, clique numa chamada (ex.: `carteira`) e
   copie o valor do cabecalho `Authorization` sem o `Bearer `.
3. `export TOKEN=<valor copiado>`. O access token do Keycloak vale 5 minutos; quando vencer, recarregue o jogo
   e copie de novo.

(Se precisar de token sem navegador, crie no console do Keycloak um cliente separado so de dev com *Direct access
grants* ligado e um mapper de audiencia `tigrinho-api`; nao ligue isso no `flecha-certeira`.)

Exemplo de uma rodada:

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
(o script manda `Authorization: Bearer $TOKEN`; veja acima como pegar um do Keycloak local):

```bash
TOKEN=eyJ... ./scripts/teste-integracao.sh
TOKEN=eyJ... GATEWAY_URL=http://localhost:8090 ./scripts/teste-integracao.sh   # se mudou a porta
```

### Testes automatizados

`mvn verify` sobe o gateway com servidores HTTP falsos no lugar dos servicos, do user-service e do JWKS do
provedor (par de chaves RSA gerado no proprio teste, tokens no formato do Keycloak e do Auth0), confere o
`infra/keycloak/tigrinho-realm.json` e exige cobertura JaCoCo de 80% de linhas e 75% de branches.

## Repositorios do projeto

- [user-service](https://github.com/tigrinho-trader/user-service)
- [trading-service](https://github.com/tigrinho-trader/trading-service)
- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
