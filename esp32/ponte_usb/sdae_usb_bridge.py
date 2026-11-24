#!/usr/bin/env python3
"""
Ponte USB (Serial) -> HTTP para o sistema SDAE.

Lê as linhas "SDAE_JSON:{...}" enviadas pelo ESP32 ou Arduino (com MPU-6050)
pelo cabo USB e encaminha via HTTP POST para a API do Spring Boot:
    POST http://<host>:<port>/api/sensores/<sensor_id>/leitura
    Header: X-API-Key: <api_key>

Dependência única:
    pip install pyserial

Exemplos de uso:
    python sdae_usb_bridge.py --list-ports
    python sdae_usb_bridge.py --port COM3 --sensor-id 1 --api-key SUA_CHAVE
    python sdae_usb_bridge.py --sensor-id 1 --api-key SUA_CHAVE   # autodetecta porta USB
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from typing import Any, Optional

PREFIXO_JSON = "SDAE_JSON:"
TERMOS_PLACAS_USB = (
    "cp210",
    "ch340",
    "ch910",
    "ftdi",
    "usb serial",
    "usb-serial",
    "usb2.0-serial",
    "arduino",
    "esp32",
    "silicon labs",
    "wch",
)


def importar_pyserial():
    try:
        import serial  # type: ignore
        from serial.tools import list_ports  # type: ignore
        return serial, list_ports
    except ImportError:
        print(
            "[ERRO] Biblioteca 'pyserial' não encontrada.\n"
            "Instale com:\n"
            "    pip install pyserial\n",
            file=sys.stderr,
        )
        sys.exit(1)


def listar_portas() -> None:
    _, list_ports = importar_pyserial()
    portas = list(list_ports.comports())
    if not portas:
        print("Nenhuma porta serial (USB) encontrada.")
        return

    print("Portas seriais disponíveis:")
    for p in portas:
        desc = p.description or "Sem descrição"
        hwid = p.hwid or ""
        print(f"  - {p.device:15s} | {desc} ({hwid})")


def detectar_porta_automatica() -> Optional[str]:
    _, list_ports = importar_pyserial()
    portas = list(list_ports.comports())
    for p in portas:
        texto = f"{p.device} {p.description or ''} {p.manufacturer or ''}".lower()
        if any(termo in texto for termo in TERMOS_PLACAS_USB):
            return p.device
    if len(portas) == 1:
        return portas[0].device
    return None


def extrair_payload_serial(linha: str) -> Optional[dict[str, Any]]:
    """Extrai o dicionário JSON de uma linha começando com 'SDAE_JSON:'."""
    texto = linha.strip()
    if not texto.startswith(PREFIXO_JSON):
        return None
    bruto = texto[len(PREFIXO_JSON):].strip()
    try:
        dados = json.loads(bruto)
    except json.JSONDecodeError:
        return None
    return dados if isinstance(dados, dict) else None


def montar_payload_api(dados_serial: dict[str, Any]) -> dict[str, Any]:
    """Monta o corpo JSON completo aceito por POST /api/sensores/{id}/leitura."""
    chaves_aceitas = (
        "valor",
        "valorLdr",
        "laserDetectado",
        "pulsoDetectado",
        "sensorOk",
        "vibracaoRmsG",
        "vibracaoPicoG",
        "gyroPicoDps",
        "ax",
        "ay",
        "az",
        "gx",
        "gy",
        "gz",
    )
    payload: dict[str, Any] = {}
    for chave in chaves_aceitas:
        if chave in dados_serial and dados_serial[chave] is not None:
            payload[chave] = dados_serial[chave]
    return payload


def enviar_leitura_http(
    base_url: str,
    sensor_id: int,
    api_key: str,
    payload: dict[str, Any],
    timeout: float = 3.0,
) -> tuple[int, str]:
    url = f"{base_url.rstrip('/')}/api/sensores/{sensor_id}/leitura"
    corpo = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=corpo,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-API-Key": api_key,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        detalhe = e.read().decode("utf-8", errors="replace")
        return e.code, detalhe
    except urllib.error.URLError as e:
        return 0, f"Erro de conexão com {url}: {e.reason}"
    except Exception as e:
        return 0, f"Erro inesperado ao enviar leitura: {e}"


def formatar_resumo_mpu(dados: dict[str, Any]) -> str:
    partes = []
    if "vibracaoRmsG" in dados:
        partes.append(f"VibRMS={dados['vibracaoRmsG']}g")
    if "gyroPicoDps" in dados:
        partes.append(f"Giro={dados['gyroPicoDps']}dps")
    if "valor" in dados:
        partes.append(f"Indice={dados['valor']}%")
    if "pulsoDetectado" in dados:
        partes.append(f"Mov={'SIM' if dados['pulsoDetectado'] else 'NAO'}")
    return " | ".join(partes)


def executar_ponte(
    porta: Optional[str],
    baud: int,
    base_url: str,
    sensor_id: int,
    api_key: str,
    mostrar_serial: bool,
    timeout_http: float,
) -> None:
    serial_mod, _ = importar_pyserial()

    porta_alvo = porta or detectar_porta_automatica()
    if not porta_alvo:
        print(
            "[ERRO] Não foi possível detectar a porta USB automaticamente.\n"
            "Rode com --list-ports para ver as portas disponíveis e informe --port COMx.",
            file=sys.stderr,
        )
        sys.exit(1)

    print("============================================================")
    print("  SDAE — Ponte USB (Serial) -> API REST Spring Boot")
    print("============================================================")
    print(f"  Porta USB : {porta_alvo} ({baud} baud)")
    print(f"  Servidor  : {base_url.rstrip('/')}/api/sensores/{sensor_id}/leitura")
    print("  Pressione Ctrl+C para encerrar.")
    print("============================================================\n")

    while True:
        try:
            with serial_mod.Serial(porta_alvo, baudrate=baud, timeout=1.0) as ser:
                print(f"[{hora_atual()}] Conectado à porta {porta_alvo}. Aguardando leituras do MPU-6050...")
                while True:
                    linha_bytes = ser.readline()
                    if not linha_bytes:
                        continue
                    linha = linha_bytes.decode("utf-8", errors="replace").strip()
                    if not linha:
                        continue

                    dados = extrair_payload_serial(linha)
                    if dados is None:
                        if mostrar_serial:
                            print(f"  [SERIAL] {linha}")
                        continue

                    payload_api = montar_payload_api(dados)
                    if not payload_api:
                        continue

                    status, resposta = enviar_leitura_http(
                        base_url=base_url,
                        sensor_id=sensor_id,
                        api_key=api_key,
                        payload=payload_api,
                        timeout=timeout_http,
                    )

                    resumo = formatar_resumo_mpu(dados)
                    if 200 <= status < 300:
                        estado = extrair_campo_resposta(resposta, "estadoEsteira")
                        risco = extrair_campo_resposta(resposta, "nivelRisco")
                        print(
                            f"[{hora_atual()}] OK ({status}) | {resumo} -> "
                            f"Esteira={estado} | Risco={risco}"
                        )
                    elif status == 401:
                        print(
                            f"[{hora_atual()}] ERRO 401 (Chave de API inválida para o sensor {sensor_id}). "
                            "Confira o parâmetro --api-key."
                        )
                    elif status == 404:
                        print(
                            f"[{hora_atual()}] ERRO 404 (Sensor ID {sensor_id} não encontrado no sistema)."
                        )
                    else:
                        print(f"[{hora_atual()}] FALHA HTTP ({status}) | {resumo} -> {resposta}")

        except KeyboardInterrupt:
            print("\n[INFO] Ponte USB encerrada pelo usuário.")
            break
        except serial_mod.SerialException as e:
            print(f"[{hora_atual()}] Conexão USB perdida ({e}). Tentando reconectar em 3s...")
            time.sleep(3)


def extrair_campo_resposta(resposta_json: str, campo: str) -> str:
    try:
        obj = json.loads(resposta_json)
        if isinstance(obj, dict) and campo in obj:
            return str(obj[campo])
    except json.JSONDecodeError:
        pass
    return "?"


def hora_atual() -> str:
    return datetime.now().strftime("%H:%M:%S")


def criar_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Ponte USB Serial (ESP32/Arduino + MPU-6050) para a API do SDAE."
    )
    parser.add_argument(
        "--list-ports",
        action="store_true",
        help="Lista todas as portas seriais (COM / /dev/ttyUSB*) disponíveis e sai.",
    )
    parser.add_argument(
        "--port",
        help="Porta serial (ex.: COM3 no Windows ou /dev/ttyUSB0 no Linux). Se omitida, tenta detectar automaticamente.",
    )
    parser.add_argument(
        "--baud",
        type=int,
        default=115200,
        help="Velocidade da porta serial em baud (padrão: 115200).",
    )
    parser.add_argument(
        "--url",
        default="http://localhost:8080",
        help="URL base do servidor Spring Boot (padrão: http://localhost:8080).",
    )
    parser.add_argument(
        "--sensor-id",
        type=int,
        help="ID do sensor cadastrado no SDAE (ex.: 1).",
    )
    parser.add_argument(
        "--api-key",
        help="Chave de API (X-API-Key) do sensor, visível na tela /sensores/editar/{id}.",
    )
    parser.add_argument(
        "--mostrar-serial",
        action="store_true",
        help="Também imprime no terminal as linhas de log de texto enviadas pela placa.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=3.0,
        help="Timeout das requisições HTTP em segundos (padrão: 3.0).",
    )
    return parser


def main() -> None:
    parser = criar_parser()
    args = parser.parse_args()

    if args.list_ports:
        listar_portas()
        return

    if not args.sensor_id or not args.api_key:
        parser.error("Informe --sensor-id e --api-key (ou use --list-ports para listar as portas).")

    executar_ponte(
        porta=args.port,
        baud=args.baud,
        base_url=args.url,
        sensor_id=args.sensor_id,
        api_key=args.api_key,
        mostrar_serial=args.mostrar_serial,
        timeout_http=args.timeout,
    )


if __name__ == "__main__":
    main()
