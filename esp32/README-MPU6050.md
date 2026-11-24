# Integração MPU-6050 (Acelerômetro e Giroscópio) — SDAE

Este guia explica como usar o módulo **MPU-6050 (GY-521)** com **ESP32** (ou **Arduino**) para detectar o movimento da esteira e enviar os dados ao sistema web SDAE via **Wi-Fi** ou via **USB (Serial)**, mantendo total compatibilidade com a API atual (sem precisar alterar o backend Java).

---

## 1. Como o MPU-6050 detecta o movimento da esteira

O módulo **MPU-6050** mede em 3 eixos (`X`, `Y`, `Z`):
- **Acelerômetro (±2g):** detecta vibração e trepidação produzidas pelo motor e pela movimentação da lona/roletes.
- **Giroscópio (±250 °/s):** detecta variação angular / rotação caso o sensor seja acoplado a um rolete, haste ou estrutura móvel.

Para funcionar em qualquer posição de montagem:
1. **Calibração automática (2 s ao ligar):** com a esteira parada, o firmware mede a gravidade inicial e o *bias* (desvio em repouso) do giroscópio.
2. **Filtro passa-alta (EMA):** remove continuamente o vetor gravidade (`1g`), deixando apenas a **aceleração dinâmica (vibração real)** nos 3 eixos.
3. **Amostragem a 50 Hz (20 ms) com confirmação + debounce:** quando a vibração (`>= 0.06g`) ou rotação (`>= 8 °/s`) se mantém por 2 amostras consecutivas (40 ms), o firmware marca `pulsoDetectado = true`.
4. **Envio a cada 2,5 segundos:** envia telemetria/heartbeat ao Spring Boot:
   - **Esteira em movimento normal:** `pulsoDetectado = true`, índice `10%–34%` → estado **`RUNNING`** (`NORMAL`).
   - **Vibração elevada / desalinhamento:** índice `35%–69%` → **`ATENÇÃO`** (gera alerta automático).
   - **Impacto / vibração severa ou falha I2C do sensor:** índice `>= 70%` → **`CRÍTICO`** (gera alerta crítico).
   - **Esteira parada:** `pulsoDetectado = false`; após **15 segundos** (`STOP_TIMEOUT`), o servidor marca automaticamente **`STOPPED`** e dispara alerta crítico.
   - **Placa desligada / sem conexão:** após **15 segundos** (`OFFLINE_TIMEOUT`), o servidor marca **`OFFLINE`** e dispara alerta crítico.

---

## 2. Ligação física (Barramento I2C)

### Com ESP32 (Recomendado — Wi-Fi + USB)

| Pino do MPU-6050 (GY-521) | Pino no ESP32 | Observação |
|---|---|---|
| `VCC` | `3V3` | Use 3,3V para manter níveis lógicos seguros no ESP32 |
| `GND` | `GND` | Terra comum |
| `SDA` | `GPIO 21` | Dados I2C |
| `SCL` | `GPIO 22` | Clock I2C |
| `AD0` | `GND` (ou solto) | Define endereço I2C padrão `0x68` |

> **Dica de montagem física:** fixe o módulo MPU-6050 firmemente (com fita dupla face rígida ou parafusos) na carcaça próxima ao motor, mancal ou rolete da esteira — cabos frouxos ou fita mole amortecem a vibração.

### Caso teste com Arduino Uno / Nano (somente USB)

| Pino do MPU-6050 (GY-521) | Pino no Arduino Uno / Nano |
|---|---|
| `VCC` | `5V` (o módulo GY-521 possui regulador interno) ou `3.3V` |
| `GND` | `GND` |
| `SDA` | `A4` |
| `SCL` | `A5` |

---

## 3. Passo a passo na Arduino IDE

1. No sistema web SDAE (rodando em `http://localhost:8080`):
   - Cadastre a esteira em `/esteiras/nova`.
   - Cadastre o sensor em `/sensores/novo` (ex.: modelo `ESP32-MPU6050-01`).
   - Na tela de edição (`/sensores/editar/{id}`), anote o **ID do Sensor** e copie a **Chave de API**.
2. Abra o arquivo [sdae_mpu6050_esteira.ino](sdae_mpu6050_esteira/sdae_mpu6050_esteira.ino) na **Arduino IDE**.
   - **Não é necessário instalar biblioteca externa de MPU-6050** — o código usa apenas `Wire.h` nativo.
3. No topo do arquivo `.ino`, escolha o modo de envio e configure:
   - **Modo Wi-Fi (padrão no ESP32):** mantenha `#define MODO_ENVIO_WIFI 1` e preencha `WIFI_SSID`, `WIFI_PASSWORD`, `SERVER_HOST` (IP local do computador rodando o Spring Boot), `SENSOR_ID` e `API_KEY`.
   - **Modo somente USB:** altere para `#define MODO_ENVIO_WIFI 0` (não precisa configurar Wi-Fi).
4. Selecione a placa (**ESP32 Dev Module** ou **Arduino Uno**) e a porta COM em **Ferramentas**, e clique em **Carregar (Upload)**.
5. Abra o **Monitor Serial** em **115200 baud** para verificar a calibração e os valores em tempo real.

---

## 4. Como receber os dados no sistema (Wi-Fi vs USB)

### Opção A — Via Wi-Fi (ESP32 direto para o Spring Boot)
```
[MPU-6050] --I2C--> [ESP32] --Wi-Fi (HTTP POST)--> [Spring Boot :8080] --> [MySQL + Dashboard]
```
- O próprio ESP32 envia `POST http://<IP_DO_PC>:8080/api/sensores/{SENSOR_ID}/leitura` com o cabeçalho `X-API-Key`.
- O ESP32 e o PC precisam estar na **mesma rede Wi-Fi** (e o Firewall do Windows precisa liberar a porta `8080`).
- Descubra o IP do PC com `ipconfig` (Windows) ou `hostname -I` (Linux).

### Opção B — Via cabo USB (Serial + Ponte Python)
Ideal se a rede Wi-Fi da faculdade/laboratório bloquear comunicação entre dispositivos, se você estiver usando Arduino Uno/Nano, ou como plano B seguro na apresentação do TCC:

```
[MPU-6050] --I2C--> [ESP32 / Arduino] --USB Serial--> [sdae_usb_bridge.py] --HTTP localhost:8080--> [Spring Boot]
```

1. **Feche o Monitor Serial da Arduino IDE** (apenas um programa pode usar a porta COM por vez).
2. Instale a única dependência Python:
   ```bash
   pip install -r esp32/ponte_usb/requirements.txt
   ```
3. Liste as portas USB conectadas:
   ```bash
   python esp32/ponte_usb/sdae_usb_bridge.py --list-ports
   ```
4. Execute a ponte apontando para o seu sensor:
   ```bash
   python esp32/ponte_usb/sdae_usb_bridge.py \
     --port COM3 \
     --sensor-id 1 \
     --api-key COLE_AQUI_A_CHAVE_DO_SENSOR
   ```
   *(No Linux, use `--port /dev/ttyUSB0` ou omita `--port` para detecção automática.)*

---

## 5. Integração nativa com o Backend e Telas (6 eixos + vibração)

O endpoint `POST /api/sensores/{id}/leitura` recebe e persiste nativamente todos os dados do **MPU-6050** (mantendo 100% de compatibilidade com sensores `Laser + LDR`):

```json
{
  "valor": 18.5,
  "valorLdr": 758,
  "laserDetectado": true,
  "pulsoDetectado": true,
  "sensorOk": true,
  "vibracaoRmsG": 0.145,
  "vibracaoPicoG": 0.210,
  "gyroPicoDps": 14.2,
  "ax": 0.02,
  "ay": -0.01,
  "az": 1.00,
  "gx": 1.2,
  "gy": -0.8,
  "gz": 14.2
}
```

| Campo da API | Origem no MPU-6050 | Efeito no SDAE |
|---|---|---|
| `ax`, `ay`, `az` | Acelerômetro 3 eixos (`g`) | Gravados no MySQL e exibidos no card 6 eixos (`/sensores/detalhe/{id}`) e no histórico (`/leituras`) |
| `gx`, `gy`, `gz` | Giroscópio 3 eixos (`°/s`) | Gravados no MySQL e exibidos no card 6 eixos |
| `vibracaoRmsG`, `vibracaoPicoG` | Vibração dinâmica sem gravidade (`g`) | Exibidos nos cards/tabelas e plotados nos gráficos do Dashboard, Esteira e Sensor |
| `gyroPicoDps` | Velocidade angular resultante (`°/s`) | Plotado no eixo secundário dos gráficos e usado no cálculo de risco |
| `pulsoDetectado` (ou `movimentoDetectado`) | Movimento acima do limiar | Mantém a esteira em `RUNNING` (sem movimento por 15s → `STOPPED` + alerta crítico) |
| `sensorOk` | Saúde do barramento I2C | `false` gera evento `MPU_FAULT` e alerta crítico imediato |

### Teste rápido da API (sem hardware ligado)
```bash
curl -X POST http://localhost:8080/api/sensores/1/leitura \
  -H "Content-Type: application/json" \
  -H "X-API-Key: COLE_AQUI_A_CHAVE_DO_SENSOR" \
  -d "{\"vibracaoRmsG\": 0.145, \"vibracaoPicoG\": 0.210, \"gyroPicoDps\": 14.2, \"ax\": 0.02, \"ay\": -0.01, \"az\": 1.00, \"gx\": 1.2, \"gy\": -0.8, \"gz\": 14.2, \"pulsoDetectado\": true, \"sensorOk\": true}"
```

---

## 6. Ajuste fino de sensibilidade (Calibração)

Com o Monitor Serial aberto (`115200 baud`), observe a linha:
```text
MPU: OK | VibRMS(g): 0.012 | VibPico(g): 0.025 | GiroPico(dps): 1.4 | Indice(%): 2.0 | Movimento/Pulso: NAO | Status: ESTEIRA PARADA
```
1. **Com a esteira parada:** anote o maior `VibRMS(g)` e `GiroPico(dps)` (ruído de fundo).
2. **Com a esteira ligada:** anote os valores típicos de funcionamento.
3. No `.ino`, ajuste `LIMIAR_MOVIMENTO_G` e `LIMIAR_MOVIMENTO_DPS` para um valor intermediário entre o estado parado e o estado em movimento.
