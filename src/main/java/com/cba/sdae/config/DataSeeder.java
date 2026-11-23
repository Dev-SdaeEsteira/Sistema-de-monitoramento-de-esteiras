package com.cba.sdae.config;

import com.cba.sdae.model.Funcionario;
import com.cba.sdae.repository.FuncionarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
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
@Profile({"dev", "test"})
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
            log.info("Nenhum funcionário cadastrado — criando apenas o administrador inicial.");
            Funcionario admin = new Funcionario("Administrador", "admin@sdae.com.br", "ADMINISTRADOR", "MANHA");
            admin.setMatricula("ADMIN001");
            admin.setNivelAcesso("ADMINISTRADOR");
            admin.setDepartamento("TI");
            admin.setSenha(passwordEncoder.encode("admin123"));
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
