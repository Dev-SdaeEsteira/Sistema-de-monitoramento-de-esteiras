# Configuração do SDAE — Sistema de Automação de Esteiras

## Variáveis de Ambiente (Recomendado)

O sistema possui valores padrão locais em `src/main/resources/application.properties` para facilitar o desenvolvimento, mas recomenda-se sobrescrevê-los via variáveis de ambiente antes de iniciar:

### Banco de Dados MySQL

```bash
# Variáveis de conexão com o MySQL
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/sdae?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=America/Sao_Paulo"
export SPRING_DATASOURCE_USERNAME="root"
export SPRING_DATASOURCE_PASSWORD="sua_senha_segura_aqui"
```

### Exemplo para Windows (PowerShell)

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/sdae?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=America/Sao_Paulo"
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:SPRING_DATASOURCE_PASSWORD = "sua_senha_segura_aqui"

# Depois execute com o Maven Wrapper:
.\mvnw.cmd spring-boot:run
```

### Exemplo para Linux/Mac (Bash)

```bash
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/sdae?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=America/Sao_Paulo"
export SPRING_DATASOURCE_USERNAME="root"
export SPRING_DATASOURCE_PASSWORD="sua_senha_segura_aqui"

./mvnw spring-boot:run
```

### Usando arquivo `.env` (Recomendado — carregamento automático)

O Spring Boot já está configurado com `spring.config.import=optional:file:.env[.properties]`, então basta copiar `.env.example` para `.env` na raiz do projeto:

```bash
cp .env.example .env
```

Edite o `.env` com as credenciais do seu MySQL e rode o sistema normalmente (`./mvnw spring-boot:run` ou pela IDE).

**⚠️ Importante:** O arquivo `.env` já está no `.gitignore` para nunca vazar credenciais no GitHub.

---

## Por que usuários cadastrados em um PC não aparecem em outro?

O **Git/GitHub sincroniza apenas o código-fonte**, nunca os dados gravados dentro do MySQL. Por padrão, o sistema conecta em `localhost:3306/sdae`, ou seja, **cada computador usa seu próprio banco MySQL separado**.

Para que usuários, esteiras, sensores e leituras apareçam em todos os computadores, escolha uma das opções abaixo:

### Opção 1 — Mesmo Wi-Fi / Laboratório (mais simples, sem configurar MySQL no PC 2)
1. Rode o Spring Boot normalmente no **PC Servidor** (o servidor já escuta em `0.0.0.0:8080`).
2. Descubra o IP local do PC Servidor (`ipconfig` no Windows → `Endereço IPv4`, ex.: `192.168.0.105`) e libere a porta `8080` no Firewall.
3. No **PC 2** (ou celular), abra no navegador `http://192.168.0.105:8080`. Ambos usam o mesmo servidor e o mesmo banco em tempo real.

### Opção 2 — Banco MySQL Compartilhado na Nuvem (funciona de qualquer lugar)
1. Crie um banco MySQL gratuito (ex.: **Aiven**, **Railway** ou **TiDB Cloud**).
2. Em **cada computador** da equipe, crie o arquivo `.env` (copiando de `.env.example`) apontando `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` para o **mesmo host na nuvem**.
3. A partir daí, qualquer usuário cadastrado no PC 1 já pode fazer login imediatamente no PC 2.

### Opção 3 — Transferência manual entre PCs (`mysqldump`)
Se precisar apenas levar os dados do PC 1 para o PC 2 sem internet:
```bash
# No PC 1 (exporta o banco inteiro com usuários, esteiras e leituras):
mysqldump -u root -p sdae > backup_sdae.sql

# No PC 2 (importa no MySQL local):
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS sdae;"
mysql -u root -p sdae < backup_sdae.sql
```

---

## Inicialização do Sistema

1. Certifique-se de que o **JDK 21** está ativo (`java -version`) e o **MySQL 8** está acessível (local ou compartilhado via `.env`)
2. Execute: `./mvnw spring-boot:run` (Linux/Mac) ou `.\mvnw.cmd spring-boot:run` (Windows)
3. Acesse: `http://localhost:8080` (ou `http://<IP_DO_SERVIDOR>:8080` em outros dispositivos da rede)

## Segurança

- **Nunca** comite o arquivo `.env` nem credenciais reais de produção no repositório Git
- Use `.env` local ou variáveis de ambiente para informações sensíveis
- Em produção, utilize gerenciadores de secrets (AWS Secrets Manager, Azure Key Vault, etc.) e defina `SPRING_PROFILES_ACTIVE=prod`
