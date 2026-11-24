package com.cba.sdae.config;

import com.cba.sdae.model.Funcionario;
import com.cba.sdae.repository.FuncionarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Inicialização mínima do banco.
 *
 * NÃO insere esteiras, sensores, alertas nem usuários de exemplo — todos os
 * dados operacionais devem ser cadastrados manualmente pelo sistema.
 *
 * Faz apenas duas coisas:
 *  1. Se a tabela de funcionários estiver TOTALMENTE vazia, cria um único
 *     administrador inicial — sem ele ninguém conseguiria entrar no sistema
 *     para cadastrar o restante.
 *  2. Converte para BCrypt qualquer senha legada gravada em texto puro.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final FuncionarioRepository funcionarioRepository;
    private final PasswordEncoder       passwordEncoder;

    public DataSeeder(FuncionarioRepository funcionarioRepository,
                      PasswordEncoder passwordEncoder) {
        this.funcionarioRepository = funcionarioRepository;
        this.passwordEncoder       = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (funcionarioRepository.count() == 0) {
            String emailInicial = System.getenv("ADMIN_INITIAL_EMAIL");
            if (emailInicial == null || emailInicial.isBlank()) {
                emailInicial = "admin@sdae.com.br";
            }
            String senhaInicial = System.getenv("ADMIN_INITIAL_PASSWORD");
            if (senhaInicial == null || senhaInicial.isBlank()) {
                senhaInicial = "admin123";
            }

            log.info("Nenhum funcionário cadastrado — criando administrador inicial ({}).", emailInicial);
            Funcionario admin = new Funcionario("Administrador", emailInicial.trim(), "ADMINISTRADOR", "MANHA");
            admin.setMatricula("ADMIN001");
            admin.setNivelAcesso("ADMINISTRADOR");
            admin.setDepartamento("TI");
            admin.setSenha(passwordEncoder.encode(senhaInicial));
            funcionarioRepository.save(admin);
            return;
        }

        // Converte qualquer senha que esteja em texto puro para BCrypt
        funcionarioRepository.findAll().forEach(func -> {
            if (func.getSenha() != null && !func.getSenha().startsWith("$2")) {
                func.setSenha(passwordEncoder.encode(func.getSenha()));
                funcionarioRepository.save(func);
                log.info("Senha em texto puro do usuário '{}' foi convertida automaticamente para BCrypt.", func.getEmail());
            }
        });
    }
}
