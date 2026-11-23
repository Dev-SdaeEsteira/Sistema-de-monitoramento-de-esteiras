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

### Usando arquivo `.env` (opcional, com Spring Boot)

Se quiser usar um arquivo `.env`, adicione a dependência `spring-dotenv` ao `pom.xml` e crie um arquivo `.env` na raiz do projeto:

```
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/sdae?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=America/Sao_Paulo
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=sua_senha_segura_aqui
```

**⚠️ Importante:** Nunca versione o arquivo `.env` ou arquivos contendo credenciais reais de produção. Adicione-os ao `.gitignore`.

## Inicialização do Sistema

1. Certifique-se de que o **JDK 21** está ativo (`java -version`) e o **MySQL 8** está rodando
2. Configure as variáveis de ambiente conforme descrito acima (se sua senha/host diferir do padrão local)
3. Execute: `./mvnw spring-boot:run` (Linux/Mac) ou `.\mvnw.cmd spring-boot:run` (Windows)
4. Acesse: `http://localhost:8080`

## Segurança

- **Nunca** comite credenciais reais de produção no repositório Git
- Use variáveis de ambiente para informações sensíveis
- Em produção, utilize gerenciadores de secrets (AWS Secrets Manager, Azure Key Vault, etc.) e defina `SPRING_PROFILES_ACTIVE=prod`
