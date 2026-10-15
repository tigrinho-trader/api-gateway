#!/bin/bash
# Roda a cada "docker compose up" (servico keycloak-config), depois que o Keycloak fica saudavel.
# Mantem o reino em dia mesmo quando ele ja existe no volume (o --import-realm so cria reinos novos):
#  - tema de login e nome do jogo;
#  - login com Google e Microsoft, ligado so quando as credenciais estao no ambiente (.env).
set -euo pipefail

KCADM=/opt/keycloak/bin/kcadm.sh
REINO=tigrinho

$KCADM config credentials --server "${KC_URL:-http://keycloak:8080}" --realm master \
  --user "${KC_ADMIN:-admin}" --password "${KC_ADMIN_SENHA:-admin}" >/dev/null

$KCADM update "realms/$REINO" -s loginTheme=flecha-certeira -s 'displayName=Flecha Certeira' \
  -s 'displayNameHtml=Flecha Certeira' -s registrationAllowed=true
echo "reino $REINO: tema e nome conferidos"

# Producao (docker-compose.easypanel.yml): o cliente do jogo aceita so o endereco publico, o reino exige HTTPS
# para acessos de fora e o usuario de desenvolvimento (admin/admin123) e apagado.
if [ -n "${JOGO_URL:-}" ]; then
  CLIENTE=$($KCADM get clients -r "$REINO" -q clientId=flecha-certeira --fields id --format csv --noquotes)
  $KCADM update "clients/$CLIENTE" -r "$REINO" \
    -s "redirectUris=[\"$JOGO_URL/*\",\"$JOGO_URL\"]" -s "webOrigins=[\"$JOGO_URL\"]" \
    -s "attributes.\"post.logout.redirect.uris\"=$JOGO_URL/*"
  $KCADM update "realms/$REINO" -s sslRequired=external
  echo "cliente flecha-certeira: so $JOGO_URL"
fi
if [ "${REMOVER_USUARIO_DEV:-false}" = "true" ]; then
  USUARIO_DEV=$($KCADM get users -r "$REINO" -q username=admin -q exact=true --fields id --format csv --noquotes)
  if [ -n "$USUARIO_DEV" ]; then
    $KCADM delete "users/$USUARIO_DEV" -r "$REINO"
    echo "usuario de desenvolvimento removido"
  fi
fi

# configurar <alias> <providerId> <nome na tela> <client id> <client secret> [config extra...]
configurar() {
  local alias=$1 provedor=$2 nome=$3 id=$4 segredo=$5
  shift 5
  local existe=false
  if $KCADM get "identity-provider/instances/$alias" -r "$REINO" >/dev/null 2>&1; then
    existe=true
  fi

  if [ -z "$id" ] || [ -z "$segredo" ]; then
    if $existe; then
      $KCADM update "identity-provider/instances/$alias" -r "$REINO" -s enabled=false
      echo "$alias: sem credenciais no .env, desligado"
    else
      echo "$alias: sem credenciais no .env, ignorado"
    fi
    return
  fi

  local campos=(-s "alias=$alias" -s "providerId=$provedor" -s enabled=true -s "displayName=$nome"
    -s trustEmail=true -s storeToken=false -s 'firstBrokerLoginFlowAlias=first broker login'
    -s "config.clientId=$id" -s "config.clientSecret=$segredo" -s config.syncMode=IMPORT
    -s 'config.defaultScope=openid profile email' "$@")
  if $existe; then
    $KCADM update "identity-provider/instances/$alias" -r "$REINO" "${campos[@]}"
    echo "$alias: atualizado e ligado"
  else
    $KCADM create identity-provider/instances -r "$REINO" "${campos[@]}" >/dev/null
    echo "$alias: criado e ligado"
  fi
}

configurar google google "Google" "${GOOGLE_CLIENT_ID:-}" "${GOOGLE_CLIENT_SECRET:-}"
# "common" = contas pessoais (Outlook/Hotmail) e de trabalho/escola
configurar microsoft microsoft "Microsoft" "${MICROSOFT_CLIENT_ID:-}" "${MICROSOFT_CLIENT_SECRET:-}" \
  -s "config.tenantId=${MICROSOFT_TENANT:-common}"
