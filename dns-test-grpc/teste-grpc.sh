#!/usr/bin/env bash
# Uso: ./teste-grpc.sh [segundos] [chamadas_por_ciclo]
DURACAO=${1:-90}
N=${2:-10}

for ((i=1; i<=DURACAO; i++)); do
  HORA=$(date +%H:%M:%S)
  RESP=$(curl -s --max-time 10 "http://localhost:8080/call?n=$N")
  if [ -z "$RESP" ]; then
    echo "$HORA  ERRO: sem resposta do app"
  else
    echo "$HORA  $RESP"
  fi
  sleep 1
done