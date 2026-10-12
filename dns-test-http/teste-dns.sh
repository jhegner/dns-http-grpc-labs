#!/usr/bin/env bash
# Uso: ./teste-dns.sh [segundos] [url]
DURACAO=${1:-60}
URL=${2:-http://localhost:8080/call}

for ((i=1; i<=DURACAO; i++)); do
  HORA=$(date +%H:%M:%S)
  RESP=$(curl -s --max-time 3 "$URL")

  if [ -z "$RESP" ]; then
    echo "$HORA  ERRO: sem resposta (timeout ou conexão recusada)"
  else
    echo "$HORA  $RESP"
  fi
  sleep 1
done