/* =========================================================================
   SDAE — Firmware ESP32 / Arduino com Sensor MPU-6050
   (Acelerômetro + Giroscópio para Detecção de Movimento da Esteira)
   =========================================================================
   Objetivo do Hardware:
     Módulo GY-521 (MPU-6050) fixado na estrutura/motor ou rolete da esteira.
     O firmware lê aceleração (3 eixos) e velocidade angular (3 eixos) via I2C
     a 50 Hz (20 ms), remove automaticamente a gravidade (filtro passa-alta
     EMA) e compensa o offset do giroscópio na calibração inicial.

   Compatibilidade com a API atual do SDAE (sem alterar o backend Java):
     Endpoint: POST /api/sensores/{SENSOR_ID}/leitura
     Header:   X-API-Key: <API_KEY>
     Body JSON:
       {
         "valor": 18.5,           // Índice de intensidade/risco (0 a 100%)
         "valorLdr": 758,         // Escala equivalente 0..4095 para o gráfico
         "laserDetectado": true,  // true = MPU-6050 comunicando OK no I2C
         "pulsoDetectado": true   // true = movimento da esteira detectado
       }

   Modos de Envio suportados:
     1) WI-FI (padrão no ESP32): envia HTTP POST direto ao servidor Spring Boot
        e também imprime a linha SDAE_JSON:{...} na USB.
     2) USB (Serial): defina MODO_ENVIO_WIFI como 0 (ou use em Arduino Uno/Nano)
        e rode o script esp32/ponte_usb/sdae_usb_bridge.py no computador.

   Ligação Física (I2C):
     ESP32:
       MPU-6050 VCC -> 3V3
       MPU-6050 GND -> GND
       MPU-6050 SDA -> GPIO 21
       MPU-6050 SCL -> GPIO 22
       MPU-6050 AD0 -> GND (ou solto; endereço I2C 0x68)

     Arduino Uno / Nano (se usado com MODO_ENVIO_WIFI 0):
       MPU-6050 VCC -> 5V (módulo GY-521 tem regulador 3.3V) ou 3.3V
       MPU-6050 GND -> GND
       MPU-6050 SDA -> A4
       MPU-6050 SCL -> A5

   Bibliotecas necessárias:
     NENHUMA biblioteca externa de MPU-6050! Usa apenas <Wire.h> nativo
     (+ <WiFi.h> e <HTTPClient.h> nativos do pacote ESP32 no modo Wi-Fi).

   Calibração:
     Ao ligar, mantenha a esteira PARADA por ~2 segundos. O firmware mede
     automaticamente o vetor gravidade e o bias do giroscópio.
     Abra o Monitor Serial (115200 baud) para acompanhar as leituras em tempo
     real e ajustar os limiares (seção 5) se necessário.
   ========================================================================= */

#include <Wire.h>
#include <math.h>

// ── 0. MODO DE COMUNICAÇÃO ──────────────────────────────────────────────
// 1 = Envio direto via Wi-Fi (ESP32) + saída Serial USB (SDAE_JSON)
// 0 = Somente Serial USB (use com esp32/ponte_usb/sdae_usb_bridge.py)
#define MODO_ENVIO_WIFI 1

// 0 = Servidor local na rede Wi-Fi via HTTP (ex.: 192.168.0.105:8080)
// 1 = Servidor na nuvem via HTTPS (ex.: seu-app.onrender.com na porta 443)
#define USAR_HTTPS 0

#if MODO_ENVIO_WIFI && defined(ESP32)
  #include <WiFi.h>
  #include <HTTPClient.h>
  #if USAR_HTTPS
    #include <WiFiClientSecure.h>
  #endif
  #define USAR_WIFI_ATIVO 1
#else
  #define USAR_WIFI_ATIVO 0
#endif

// ── 1. CONFIGURAÇÃO DA REDE WI-FI (usada apenas com USAR_WIFI_ATIVO = 1) ─
#if USAR_WIFI_ATIVO
const char* WIFI_SSID     = "NOME_DA_SUA_REDE";
const char* WIFI_PASSWORD = "SENHA_DA_SUA_REDE";

// ── 2. CONFIGURAÇÃO DO SERVIDOR (Spring Boot) ───────────────────────────
// Local (USAR_HTTPS = 0): IP local do PC (ex.: "192.168.0.105", porta 8080)
// Nuvem (USAR_HTTPS = 1): domínio Render/Railway (ex.: "sdae-monitoramento-esteiras.onrender.com", porta 443)
const char* SERVER_HOST = "192.168.0.105";
const int   SERVER_PORT = 8080;

// ── 3. IDENTIFICAÇÃO DO SENSOR NO SDAE ──────────────────────────────────
// SENSOR_ID e API_KEY ficam na tela de edição do sensor (/sensores/editar/{id})
const long  SENSOR_ID = 1;
const char* API_KEY   = "COLE_AQUI_A_CHAVE_DO_SENSOR";
#endif

// ── 4. PINOS I2C E ENDEREÇO DO MPU-6050 ─────────────────────────────────
#ifdef ESP32
const int PINO_SDA = 21;
const int PINO_SCL = 22;
#endif

// Registradores do MPU-6050
const uint8_t MPU_ADDR_PADRAO      = 0x68; // AD0 em GND (padrão GY-521)
const uint8_t MPU_ADDR_ALTERNATIVO = 0x69; // AD0 em 3V3
const uint8_t REG_PWR_MGMT_1       = 0x6B;
const uint8_t REG_SMPLRT_DIV       = 0x19;
const uint8_t REG_CONFIG_DLPF      = 0x1A;
const uint8_t REG_GYRO_CONFIG      = 0x1B;
const uint8_t REG_ACCEL_CONFIG     = 0x1C;
const uint8_t REG_ACCEL_XOUT_H     = 0x3B;
const uint8_t REG_WHO_AM_I         = 0x75;

// Fatores de escala (±2g e ±250 °/s — alta sensibilidade para vibração)
const float ESCALA_ACCEL_LSB_G   = 16384.0f;
const float ESCALA_GIRO_LSB_DPS  = 131.0f;

// ── 5. LIMIARES DE DETECÇÃO DE MOVIMENTO E RISCO ────────────────────────
// Ajuste observando "VibRMS(g)" e "GiroPico(dps)" no Monitor Serial:
//   - Com a esteira PARADA, veja o ruído máximo (normalmente < 0.02g e < 2 dps).
//   - Com a esteira LIGADA, veja os valores normais de vibração/rotação.
const float LIMIAR_MOVIMENTO_G   = 0.06f; // >= 0.06g: esteira em movimento (pulso)
const float LIMIAR_ATENCAO_G     = 0.45f; // >= 0.45g: vibração elevada -> ATENCAO (35%)
const float LIMIAR_CRITICO_G     = 0.90f; // >= 0.90g: impacto/vibração severa -> CRITICO (70%)
const float LIMIAR_MAXIMO_G      = 1.50f; // >= 1.50g: teto de 100%

const float LIMIAR_MOVIMENTO_DPS = 8.0f;   // >= 8 °/s: rotação detectada (pulso)
const float LIMIAR_ATENCAO_DPS   = 60.0f;  // >= 60 °/s -> ATENCAO (35%)
const float LIMIAR_CRITICO_DPS   = 130.0f; // >= 130 °/s -> CRITICO (70%)
const float LIMIAR_MAXIMO_DPS    = 220.0f; // >= 220 °/s -> 100%

// Filtro passa-alta EMA para remover gravidade em qualquer ângulo de montagem
const float ALPHA_EMA_GRAVIDADE  = 0.05f;

// Quantidade de amostras ativas consecutivas (a 50Hz: 2 amostras = 40ms)
// para evitar falso positivo por um único ruído elétrico isolado
const int AMOSTRAS_CONFIRMACAO_MOV = 2;

// ── 6. TEMPOS E AMOSTRAGEM (milissegundos) ──────────────────────────────
const unsigned long INTERVALO_AMOSTRAGEM = 20;    // 50 Hz (20 ms)
const unsigned long INTERVALO_ENVIO      = 2500;  // Telemetria/Heartbeat a cada 2.5s
const unsigned long STOP_TIMEOUT         = 15000; // 15s sem movimento = Esteira Parada
const unsigned long DEBOUNCE_PULSO_MS    = 150;   // Intervalo mínimo entre pulsos válidos

// ── VARIÁVEIS DE ESTADO INTERNO ─────────────────────────────────────────
uint8_t enderecoMpu = MPU_ADDR_PADRAO;
bool mpuConectado   = false;
int  falhasI2cConsecutivas = 0;

// Linha de base (gravidade filtrada + bias do giroscópio)
float emaAx = 0.0f, emaAy = 0.0f, emaAz = 1.0f;
float biasGx = 0.0f, biasGy = 0.0f, biasGz = 0.0f;

// Últimas leituras instantâneas (em g e °/s)
float ultimoAx = 0.0f, ultimoAy = 0.0f, ultimoAz = 0.0f;
float ultimoGx = 0.0f, ultimoGy = 0.0f, ultimoGz = 0.0f;

// Acumuladores da janela de 2.5s
float somaQuadradosVibG  = 0.0f;
float picoVibGJanela     = 0.0f;
float picoGiroDpsJanela  = 0.0f;
unsigned int amostrasNaJanela = 0;
int amostrasAtivasSeguidas    = 0;

bool pulsoDetectadoNestaJanela = false;
unsigned int  pulsosNaJanela   = 0;
unsigned long pulsosAcumulados = 0;

unsigned long ultimoTempoAmostra = 0;
unsigned long ultimoTempoEnvio   = 0;
unsigned long tempoUltimoPulso   = 0;

// ── COMUNICAÇÃO I2C COM O MPU-6050 ──────────────────────────────────────
bool escreverRegistradorMpu(uint8_t reg, uint8_t valor) {
    Wire.beginTransmission(enderecoMpu);
    Wire.write(reg);
    Wire.write(valor);
    return (Wire.endTransmission() == 0);
}

bool testarEnderecoMpu(uint8_t addr) {
    Wire.beginTransmission(addr);
    Wire.write(REG_WHO_AM_I);
    if (Wire.endTransmission(false) != 0) {
        return false;
    }
    uint8_t lidos = Wire.requestFrom(addr, (uint8_t)1, (uint8_t)true);
    if (lidos != 1) {
        return false;
    }
    uint8_t whoAmI = Wire.read();
    // MPU-6050 original retorna 0x68 (ou 0x69 com AD0 alto); alguns clones retornam 0x70/0x72/0x98
    return (whoAmI == 0x68 || whoAmI == 0x69 || whoAmI == 0x70 || whoAmI == 0x72 || whoAmI == 0x98);
}

bool inicializarMpu6050() {
    if (testarEnderecoMpu(MPU_ADDR_PADRAO)) {
        enderecoMpu = MPU_ADDR_PADRAO;
    } else if (testarEnderecoMpu(MPU_ADDR_ALTERNATIVO)) {
        enderecoMpu = MPU_ADDR_ALTERNATIVO;
    } else {
        mpuConectado = false;
        return false;
    }

    // 1. Acorda o MPU-6050 usando oscilador PLL do eixo X (mais estável)
    if (!escreverRegistradorMpu(REG_PWR_MGMT_1, 0x01)) {
        mpuConectado = false;
        return false;
    }
    delay(50);

    // 2. Filtro passa-baixa digital (DLPF_CFG = 3 -> largura de banda ~44 Hz)
    escreverRegistradorMpu(REG_CONFIG_DLPF, 0x03);

    // 3. Taxa de amostragem interna: 1 kHz / (1 + 9) = 100 Hz
    escreverRegistradorMpu(REG_SMPLRT_DIV, 0x09);

    // 4. Giroscópio em ±250 °/s (FS_SEL = 0)
    escreverRegistradorMpu(REG_GYRO_CONFIG, 0x00);

    // 5. Acelerômetro em ±2g (AFS_SEL = 0)
    escreverRegistradorMpu(REG_ACCEL_CONFIG, 0x00);

    mpuConectado = true;
    falhasI2cConsecutivas = 0;
    return true;
}

bool lerMpu6050(float &ax, float &ay, float &az, float &gx, float &gy, float &gz) {
    Wire.beginTransmission(enderecoMpu);
    Wire.write(REG_ACCEL_XOUT_H);
    if (Wire.endTransmission(false) != 0) {
        return false;
    }

    uint8_t recebidos = Wire.requestFrom(enderecoMpu, (uint8_t)14, (uint8_t)true);
    if (recebidos != 14) {
        return false;
    }

    int16_t rawAx   = (int16_t)((Wire.read() << 8) | Wire.read());
    int16_t rawAy   = (int16_t)((Wire.read() << 8) | Wire.read());
    int16_t rawAz   = (int16_t)((Wire.read() << 8) | Wire.read());
    int16_t rawTemp = (int16_t)((Wire.read() << 8) | Wire.read()); // não usado
    int16_t rawGx   = (int16_t)((Wire.read() << 8) | Wire.read());
    int16_t rawGy   = (int16_t)((Wire.read() << 8) | Wire.read());
    int16_t rawGz   = (int16_t)((Wire.read() << 8) | Wire.read());
    (void)rawTemp;

    ax = rawAx / ESCALA_ACCEL_LSB_G;
    ay = rawAy / ESCALA_ACCEL_LSB_G;
    az = rawAz / ESCALA_ACCEL_LSB_G;

    gx = rawGx / ESCALA_GIRO_LSB_DPS;
    gy = rawGy / ESCALA_GIRO_LSB_DPS;
    gz = rawGz / ESCALA_GIRO_LSB_DPS;

    return true;
}

// Calibração inicial (~2 segundos com a esteira parada)
void calibrarMpu6050() {
    if (!mpuConectado) return;

    Serial.println("[MPU-6050] Calibrando repouso (mantenha a esteira parada por 2s)...");
    const int TOTAL_AMOSTRAS = 100;
    float somaAx = 0.0f, somaAy = 0.0f, somaAz = 0.0f;
    float somaGx = 0.0f, somaGy = 0.0f, somaGz = 0.0f;
    int validas = 0;

    for (int i = 0; i < TOTAL_AMOSTRAS; i++) {
        float ax, ay, az, gx, gy, gz;
        if (lerMpu6050(ax, ay, az, gx, gy, gz)) {
            somaAx += ax; somaAy += ay; somaAz += az;
            somaGx += gx; somaGy += gy; somaGz += gz;
            validas++;
        }
        delay(20);
    }

    if (validas > 0) {
        emaAx  = somaAx / validas;
        emaAy  = somaAy / validas;
        emaAz  = somaAz / validas;
        biasGx = somaGx / validas;
        biasGy = somaGy / validas;
        biasGz = somaGz / validas;

        Serial.print("[MPU-6050] Calibrado! Gravidade base=(");
        Serial.print(emaAx, 2); Serial.print(", ");
        Serial.print(emaAy, 2); Serial.print(", ");
        Serial.print(emaAz, 2); Serial.print(")g | Bias giro=(");
        Serial.print(biasGx, 2); Serial.print(", ");
        Serial.print(biasGy, 2); Serial.print(", ");
        Serial.print(biasGz, 2); Serial.println(") dps");
    } else {
        Serial.println("[MPU-6050] Aviso: falha ao ler amostras durante a calibração.");
    }
}

// ── MAPEAMENTO DE INTENSIDADE PARA PORCENTAGEM DE RISCO (0..100%) ───────
// Alinhado ao SensorService.calcularNivel():
//   < 35%  -> NORMAL
//   35-69% -> ATENCAO
//   >= 70% -> CRITICO
float mapearFaixaLinear(float x, float xMin, float xMax, float yMin, float yMax) {
    if (x <= xMin) return yMin;
    if (x >= xMax) return yMax;
    return yMin + ((x - xMin) / (xMax - xMin)) * (yMax - yMin);
}

float calcularIndicePct(float valor, float limMov, float limAtencao, float limCritico, float limMax) {
    if (valor <= 0.0f)       return 0.0f;
    if (valor < limMov)      return mapearFaixaLinear(valor, 0.0f,       limMov,     0.0f,  9.5f);
    if (valor < limAtencao)  return mapearFaixaLinear(valor, limMov,     limAtencao, 10.0f, 34.0f);
    if (valor < limCritico)  return mapearFaixaLinear(valor, limAtencao, limCritico, 35.0f, 69.0f);
    return mapearFaixaLinear(valor, limCritico, limMax, 70.0f, 100.0f);
}

// ── CONEXÃO WI-FI (APENAS ESP32 COM MODO_ENVIO_WIFI = 1) ────────────────
#if USAR_WIFI_ATIVO
void conectarWifi() {
    if (WiFi.status() == WL_CONNECTED) return;

    Serial.print("[WiFi] Conectando a ");
    Serial.print(WIFI_SSID);

    WiFi.disconnect();
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    int tentativas = 0;
    while (WiFi.status() != WL_CONNECTED && tentativas < 20) {
        delay(300);
        Serial.print(".");
        tentativas++;
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.print("[WiFi] Conectado! IP do ESP32: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println("[WiFi] Falha ao conectar. Continuando em modo USB Serial...");
    }
}
#endif

// ── AMOSTRAGEM DO MPU-6050 E DETECÇÃO DE MOVIMENTO (50 Hz) ──────────────
void amostrarMpu6050() {
    if (!mpuConectado) {
        return;
    }

    float ax, ay, az, gx, gy, gz;
    if (!lerMpu6050(ax, ay, az, gx, gy, gz)) {
        falhasI2cConsecutivas++;
        if (falhasI2cConsecutivas >= 10) {
            mpuConectado = false;
            Serial.println("[MPU-6050] ERRO: perda de comunicação I2C!");
        }
        return;
    }
    falhasI2cConsecutivas = 0;

    // Atualiza linha de base da gravidade (passa-baixa EMA)
    emaAx = (1.0f - ALPHA_EMA_GRAVIDADE) * emaAx + ALPHA_EMA_GRAVIDADE * ax;
    emaAy = (1.0f - ALPHA_EMA_GRAVIDADE) * emaAy + ALPHA_EMA_GRAVIDADE * ay;
    emaAz = (1.0f - ALPHA_EMA_GRAVIDADE) * emaAz + ALPHA_EMA_GRAVIDADE * az;

    // Aceleração dinâmica (vibração / movimento real sem gravidade)
    float dax = ax - emaAx;
    float day = ay - emaAy;
    float daz = az - emaAz;
    float vibInstG = sqrtf(dax * dax + day * day + daz * daz);

    // Velocidade angular sem bias (em graus por segundo)
    float cgx = gx - biasGx;
    float cgy = gy - biasGy;
    float cgz = gz - biasGz;
    float giroMagDps = sqrtf(cgx * cgx + cgy * cgy + cgz * cgz);

    ultimoAx = ax;  ultimoAy = ay;  ultimoAz = az;
    ultimoGx = cgx; ultimoGy = cgy; ultimoGz = cgz;

    // Acumula estatísticas da janela
    somaQuadradosVibG += (vibInstG * vibInstG);
    if (vibInstG > picoVibGJanela)        picoVibGJanela = vibInstG;
    if (giroMagDps > picoGiroDpsJanela)   picoGiroDpsJanela = giroMagDps;
    amostrasNaJanela++;

    // Detecção de movimento acima do limiar com confirmação e debounce
    bool movimentoInstantaneo = (vibInstG >= LIMIAR_MOVIMENTO_G) || (giroMagDps >= LIMIAR_MOVIMENTO_DPS);
    if (movimentoInstantaneo) {
        amostrasAtivasSeguidas++;
        if (amostrasAtivasSeguidas >= AMOSTRAS_CONFIRMACAO_MOV) {
            unsigned long agora = millis();
            if (tempoUltimoPulso == 0 || (agora - tempoUltimoPulso) >= DEBOUNCE_PULSO_MS) {
                pulsoDetectadoNestaJanela = true;
                pulsosNaJanela++;
                pulsosAcumulados++;
                tempoUltimoPulso = agora;
            }
        }
    } else {
        amostrasAtivasSeguidas = 0;
    }
}

// ── ENVIO DE TELEMETRIA (USB SERIAL + WI-FI HTTP POST) ──────────────────
void enviarTelemetria() {
    // Se perdeu o MPU-6050, tenta reinicializar automaticamente
    if (!mpuConectado) {
        if (inicializarMpu6050()) {
            Serial.println("[MPU-6050] Comunicação I2C restabelecida!");
            calibrarMpu6050();
        }
    }

    float vibRmsG = (amostrasNaJanela > 0)
        ? sqrtf(somaQuadradosVibG / (float)amostrasNaJanela)
        : 0.0f;

    // Combina RMS (vibração contínua) e 65% do pico (trancos/impactos)
    float vibEfetivaG = (vibRmsG > (picoVibGJanela * 0.65f))
        ? vibRmsG
        : (picoVibGJanela * 0.65f);

    float pctAcel = calcularIndicePct(vibEfetivaG, LIMIAR_MOVIMENTO_G, LIMIAR_ATENCAO_G, LIMIAR_CRITICO_G, LIMIAR_MAXIMO_G);
    float pctGiro = calcularIndicePct(picoGiroDpsJanela, LIMIAR_MOVIMENTO_DPS, LIMIAR_ATENCAO_DPS, LIMIAR_CRITICO_DPS, LIMIAR_MAXIMO_DPS);
    float valorPct = (pctAcel > pctGiro) ? pctAcel : pctGiro;

    bool sensorOk = mpuConectado;
    bool pulso    = sensorOk && pulsoDetectadoNestaJanela;

    // Se o MPU-6050 falhar (cabo solto / erro I2C), eleva risco para CRÍTICO
    if (!sensorOk) {
        valorPct = 95.0f;
        pulso    = false;
    }

    int valorEscala4095 = (int)(valorPct * 40.95f + 0.5f);
    if (valorEscala4095 < 0)    valorEscala4095 = 0;
    if (valorEscala4095 > 4095) valorEscala4095 = 4095;

    unsigned long agora = millis();
    unsigned long tempoSemPulsos = (tempoUltimoPulso > 0) ? (agora - tempoUltimoPulso) : STOP_TIMEOUT;
    bool esteiraParada = (tempoUltimoPulso == 0 || tempoSemPulsos >= STOP_TIMEOUT);

    // 1. Log legível no Monitor Serial (para calibração e apresentação)
    Serial.print("MPU: ");
    Serial.print(sensorOk ? "OK" : "ERRO_I2C");
    Serial.print(" | VibRMS(g): ");
    Serial.print(vibRmsG, 3);
    Serial.print(" | VibPico(g): ");
    Serial.print(picoVibGJanela, 3);
    Serial.print(" | GiroPico(dps): ");
    Serial.print(picoGiroDpsJanela, 1);
    Serial.print(" | Indice(%): ");
    Serial.print(valorPct, 1);
    Serial.print(" | Movimento/Pulso: ");
    Serial.print(pulso ? "SIM" : "NAO");
    Serial.print(" | Status: ");
    Serial.println(!sensorOk ? "FALHA_SENSOR" : (esteiraParada ? "ESTEIRA PARADA" : "ESTEIRA FUNCIONANDO"));

    // 2. Payload JSON completo (usado tanto na saída USB Serial quanto no HTTP POST Wi-Fi)
    String jsonTelemetria = "{";
    jsonTelemetria += "\"valor\":" + String(valorPct, 1) + ",";
    jsonTelemetria += "\"valorLdr\":" + String(valorEscala4095) + ",";
    jsonTelemetria += "\"laserDetectado\":" + String(sensorOk ? "true" : "false") + ",";
    jsonTelemetria += "\"pulsoDetectado\":" + String(pulso ? "true" : "false") + ",";
    jsonTelemetria += "\"sensorOk\":" + String(sensorOk ? "true" : "false") + ",";
    jsonTelemetria += "\"vibracaoRmsG\":" + String(vibRmsG, 3) + ",";
    jsonTelemetria += "\"vibracaoPicoG\":" + String(picoVibGJanela, 3) + ",";
    jsonTelemetria += "\"gyroPicoDps\":" + String(picoGiroDpsJanela, 1) + ",";
    jsonTelemetria += "\"ax\":" + String(ultimoAx, 2) + ",";
    jsonTelemetria += "\"ay\":" + String(ultimoAy, 2) + ",";
    jsonTelemetria += "\"az\":" + String(ultimoAz, 2) + ",";
    jsonTelemetria += "\"gx\":" + String(ultimoGx, 1) + ",";
    jsonTelemetria += "\"gy\":" + String(ultimoGy, 1) + ",";
    jsonTelemetria += "\"gz\":" + String(ultimoGz, 1);
    jsonTelemetria += "}";

    Serial.print("SDAE_JSON:");
    Serial.println(jsonTelemetria);

    // 3. Envio direto via Wi-Fi (HTTP ou HTTPS POST para a API do Spring Boot)
#if USAR_WIFI_ATIVO
    if (WiFi.status() != WL_CONNECTED) {
        conectarWifi();
    }

    if (WiFi.status() == WL_CONNECTED) {
        HTTPClient http;
#if USAR_HTTPS
        WiFiClientSecure clienteTls;
        clienteTls.setInsecure(); // Aceita certificado TLS da nuvem (Render/Railway/Koyeb) sem pinning manual
        String url = String("https://") + SERVER_HOST
                     + (SERVER_PORT != 443 ? (String(":") + SERVER_PORT) : String(""))
                     + "/api/sensores/" + String(SENSOR_ID) + "/leitura";
        http.begin(clienteTls, url);
        http.setTimeout(5000);
#else
        String url = String("http://") + SERVER_HOST + ":" + SERVER_PORT
                     + "/api/sensores/" + String(SENSOR_ID) + "/leitura";
        http.begin(url);
        http.setTimeout(2500);
#endif
        http.addHeader("Content-Type", "application/json");
        http.addHeader("X-API-Key", API_KEY);

        int codigoResposta = http.POST(jsonTelemetria);
        if (codigoResposta > 0) {
            Serial.print("[HTTP] Enviado! Código: ");
            Serial.print(codigoResposta);
            Serial.print(" -> ");
            Serial.println(http.getString());
        } else {
            Serial.print("[HTTP] Falha no envio: ");
            Serial.println(http.errorToString(codigoResposta).c_str());
        }
        http.end();
    } else {
        Serial.println("[HTTP] Sem Wi-Fi — leitura emitida via USB Serial (SDAE_JSON).");
    }
#endif

    // 4. Reseta acumuladores para a próxima janela
    somaQuadradosVibG         = 0.0f;
    picoVibGJanela            = 0.0f;
    picoGiroDpsJanela         = 0.0f;
    amostrasNaJanela          = 0;
    pulsoDetectadoNestaJanela = false;
    pulsosNaJanela            = 0;
}

// ── SETUP INICIAL ───────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("\n==============================================");
    Serial.println("  SDAE — Monitoramento de Esteira (MPU-6050)");
    Serial.println("  Acelerômetro + Giroscópio via I2C");
    Serial.println("==============================================");

#ifdef ESP32
    Wire.begin(PINO_SDA, PINO_SCL);
    Serial.print("Placa: ESP32 | I2C SDA=GPIO ");
    Serial.print(PINO_SDA);
    Serial.print(" / SCL=GPIO ");
    Serial.println(PINO_SCL);
#else
    Wire.begin();
    Serial.println("Placa: Arduino (AVR) | I2C SDA=A4 / SCL=A5");
#endif
    Wire.setClock(400000); // I2C Fast Mode (400 kHz)

#if USAR_WIFI_ATIVO
    Serial.println("Modo de envio: Wi-Fi (HTTP/HTTPS POST) + USB Serial");
    Serial.print("Sensor ID: "); Serial.println(SENSOR_ID);
    Serial.print("Servidor: ");
    Serial.print(USAR_HTTPS ? "https://" : "http://");
    Serial.print(SERVER_HOST); Serial.print(":"); Serial.println(SERVER_PORT);
#else
    Serial.println("Modo de envio: Somente USB Serial (use sdae_usb_bridge.py)");
#endif
    Serial.println("==============================================\n");

    if (inicializarMpu6050()) {
        Serial.print("[MPU-6050] Sensor detectado no endereço 0x");
        Serial.println(enderecoMpu, HEX);
        calibrarMpu6050();
    } else {
        Serial.println("[MPU-6050] ERRO: sensor não encontrado no barramento I2C!");
        Serial.println("           Verifique ligações VCC (3V3), GND, SDA e SCL.");
    }

#if USAR_WIFI_ATIVO
    conectarWifi();
#endif

    ultimoTempoAmostra = millis();
    ultimoTempoEnvio   = millis();
}

// ── LOOP PRINCIPAL NÃO-BLOQUEANTE ──────────────────────────────────────
void loop() {
    unsigned long agora = millis();

    // 1. Amostragem de alta frequência do MPU-6050 (50 Hz)
    if (agora - ultimoTempoAmostra >= INTERVALO_AMOSTRAGEM) {
        ultimoTempoAmostra = agora;
        amostrarMpu6050();
    }

    // 2. Envio periódico de telemetria e heartbeat (2.5s)
    if (agora - ultimoTempoEnvio >= INTERVALO_ENVIO) {
        ultimoTempoEnvio = agora;
        enviarTelemetria();
    }

    delay(1);
}
