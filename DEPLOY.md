# Deploy na Nuvem — SDAE (Render / Railway + MySQL)

Como o SDAE é um backend **Java 21 + Spring Boot + MySQL**, ele precisa de uma hospedagem de containers/Java (não funciona no Netlify, que serve apenas sites estáticos).

Com o sistema na nuvem, **todos os computadores, celulares e o ESP32 acessam o mesmo servidor e o mesmo banco de dados** — resolvendo automaticamente a sincronização de usuários, esteiras e leituras entre PCs.

---

## Opção 1 — Render (Gratuito) + Aiven MySQL (Gratuito)

### Passo 1: Criar o banco MySQL gratuito no Aiven
1. Crie uma conta gratuita em [aiven.io](https://aiven.io) (não exige cartão).
2. Clique em **Create service** → escolha **MySQL** → plano **Free**.
3. Quando o banco ficar **Running**, copie na aba **Overview**:
   - **Host** (ex.: `mysql-xxxx.aivencloud.com`)
   - **Port** (ex.: `21456`)
   - **User** (ex.: `avnadmin`)
   - **Password**
   - **Database name** (ex.: `defaultdb`)
4. Monte a URL JDBC substituindo os dados acima:
   ```text
   jdbc:mysql://SEU-HOST:SUA-PORTA/defaultdb?useSSL=true&requireSSL=true&serverTimezone=America/Sao_Paulo
   ```

### Passo 2: Subir o SDAE no Render
1. Crie uma conta em [render.com](https://render.com) conectando sua conta do GitHub.
2. Clique em **New +** → **Blueprint** (ou **Web Service** → selecione `Dev-SdaeEsteira/Sistema-de-monitoramento-de-esteiras`, runtime **Docker**).
3. O Render lerá automaticamente o [Dockerfile](Dockerfile) e o [render.yaml](render.yaml).
4. Preencha as variáveis de ambiente solicitadas:
   - `SPRING_PROFILES_ACTIVE` = `prod`
   - `SPRING_DATASOURCE_URL` = *(URL JDBC do Passo 1)*
   - `SPRING_DATASOURCE_USERNAME` = *(User do Passo 1)*
   - `SPRING_DATASOURCE_PASSWORD` = *(Password do Passo 1)*
   - `ADMIN_INITIAL_EMAIL` = `admin@sdae.com.br` *(ou o e-mail que preferir)*
   - `ADMIN_INITIAL_PASSWORD` = *(crie uma senha forte para o primeiro login)*
5. Clique em **Deploy**. Ao finalizar, o Render gera sua URL pública, ex.:
   `https://sdae-monitoramento-esteiras.onrender.com`

> **Observação (plano gratuito do Render):** após 15 min sem acessos, o container hiberna e leva ~30–50s para acordar na primeira requisição. Antes de apresentar o TCC, abra a URL no navegador 1 minuto antes para mantê-lo acordado.

---

## Opção 2 — Railway (App + MySQL no mesmo painel)

1. Crie uma conta em [railway.app](https://railway.app) com seu GitHub.
2. Clique em **New Project** → **Deploy from GitHub repo** → escolha `Sistema-de-monitoramento-de-esteiras`.
3. No mesmo projeto, clique em **+ New** → **Database** → **Add MySQL**.
4. No serviço do SDAE, vá em **Variables** e adicione:
   ```text
   SPRING_PROFILES_ACTIVE=prod
   SPRING_DATASOURCE_URL=jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo
   SPRING_DATASOURCE_USERNAME=${{MySQL.MYSQLUSER}}
   SPRING_DATASOURCE_PASSWORD=${{MySQL.MYSQLPASSWORD}}
   ADMIN_INITIAL_EMAIL=admin@sdae.com.br
   ADMIN_INITIAL_PASSWORD=SUA_SENHA_FORTE
   ```
5. Em **Settings → Networking**, clique em **Generate Domain** para obter a URL HTTPS pública.

---

## Conectando o ESP32 / Ponte USB ao servidor na nuvem

### 1. ESP32 via Wi-Fi (HTTPS direto)
No topo de [esp32/sdae_mpu6050_esteira/sdae_mpu6050_esteira.ino](esp32/sdae_mpu6050_esteira/sdae_mpu6050_esteira.ino), altere:
```cpp
#define MODO_ENVIO_WIFI 1
#define USAR_HTTPS      1

const char* SERVER_HOST = "sdae-monitoramento-esteiras.onrender.com"; // sem https://
const int   SERVER_PORT = 443;
const long  SENSOR_ID   = 1;
const char* API_KEY     = "SUA_CHAVE_COPIADA_DO_SITE";
```

### 2. Ponte USB (Serial → HTTPS)
Basta passar `--url` com o domínio público:
```bash
python esp32/ponte_usb/sdae_usb_bridge.py \
  --port COM3 \
  --url https://sdae-monitoramento-esteiras.onrender.com \
  --sensor-id 1 \
  --api-key SUA_CHAVE_COPIADA_DO_SITE
```
