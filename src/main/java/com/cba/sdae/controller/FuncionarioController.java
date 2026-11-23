package com.cba.sdae.controller;

import com.cba.sdae.model.Funcionario;
import com.cba.sdae.service.FuncionarioService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
public class FuncionarioController {

    private static final Logger log = LoggerFactory.getLogger(FuncionarioController.class);

    private final FuncionarioService funcionarioService;
    private final PasswordEncoder passwordEncoder;

    public FuncionarioController(FuncionarioService funcionarioService,
                                 PasswordEncoder passwordEncoder) {
        this.funcionarioService = funcionarioService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public String listar(Model model) {
        var funcionarios = funcionarioService.listarTodos();
        model.addAttribute("funcionarios",  funcionarios);
        model.addAttribute("totalAdmin",    funcionarioService.contarPorNivelAcesso("ADMINISTRADOR"));
        model.addAttribute("totalEng",      funcionarioService.contarPorNivelAcesso("ENGENHEIRO"));
        model.addAttribute("totalOperador", funcionarioService.contarPorNivelAcesso("OPERADOR"));
        return "admin/administracao";
    }

    @GetMapping("/novo")
    public String formularioNovo(Model model) {
        model.addAttribute("funcionario", new Funcionario());
        return "cadastro/cadastrofunc";
    }

    @GetMapping("/editar/{id}")
    public String formularioEditar(@PathVariable Long id, Model model) {
        var func = funcionarioService.buscarPorId(id);
        if (func.isEmpty()) return "redirect:/admin";
        model.addAttribute("funcionario", func.get());
        return "cadastro/editarfunc";
    }

    // Esta rota já é protegida por hasRole("ADMINISTRADOR") em /admin/** —
    // ver SecurityConfig. Só um administrador autenticado chega aqui, então
    // a mudança de nivelAcesso/cargo de terceiros é intencional e segura.
    @PostMapping("/salvar")
    public String salvar(@ModelAttribute Funcionario funcionario, Model model) {
        boolean edicao = funcionario.getId() != null;
        String telaForm = edicao ? "cadastro/editarfunc" : "cadastro/cadastrofunc";

        // Campos em branco do formulário chegam como "" — normaliza para null
        // (senão o CPF vazio falha na validação \d{11} e matrícula/CPF vazios
        // colidem nas restrições UNIQUE do banco).
        normalizar(funcionario);

        String erro = validar(funcionario, edicao);
        if (erro != null) {
            return voltarComErro(funcionario, model, telaForm, erro);
        }

        try {
            // Usa o cargo como nível de acesso se não informado
            if (funcionario.getNivelAcesso() == null || funcionario.getNivelAcesso().isBlank())
                funcionario.setNivelAcesso(funcionario.getCargo());

            if (edicao) {
                // Edição: preserva a senha atual (já em hash) se o campo vier em branco;
                // se vier preenchida, re-encoda a nova senha.
                var existenteOpt = funcionarioService.buscarPorId(funcionario.getId());
                if (existenteOpt.isEmpty()) return "redirect:/admin";
                Funcionario existente = existenteOpt.get();

                boolean eraAdmin = "ADMINISTRADOR".equalsIgnoreCase(existente.getNivelAcesso());
                boolean continuaAdmin = "ADMINISTRADOR".equalsIgnoreCase(funcionario.getNivelAcesso());
                if (eraAdmin && !continuaAdmin && funcionarioService.contarPorNivelAcesso("ADMINISTRADOR") <= 1) {
                    return voltarComErro(funcionario, model, telaForm,
                            "Não é possível alterar o nível de acesso do último administrador do sistema.");
                }

                if (funcionario.getSenha() == null || funcionario.getSenha().isBlank()) {
                    funcionario.setSenha(existente.getSenha());
                } else {
                    funcionario.setSenha(passwordEncoder.encode(funcionario.getSenha()));
                }
            } else {
                funcionario.setSenha(passwordEncoder.encode(funcionario.getSenha()));
            }

            funcionarioService.salvar(funcionario);
            return "redirect:/admin?sucesso=true";

        } catch (Exception e) {
            log.warn("Falha ao salvar funcionário '{}': {}", funcionario.getEmail(), e.toString());
            return voltarComErro(funcionario, model, telaForm, mensagemDeErro(e));
        }
    }

    /** Remove espaços, transforma "" em null e padroniza matrícula/CPF. */
    private void normalizar(Funcionario f) {
        f.setNome(limpar(f.getNome()));
        f.setEmail(limpar(f.getEmail()));
        f.setTelefone(limpar(f.getTelefone()));
        f.setDepartamento(limpar(f.getDepartamento()));

        String matricula = limpar(f.getMatricula());
        f.setMatricula(matricula != null ? matricula.toUpperCase() : null);

        // CPF aceito com ou sem pontuação; grava só os dígitos.
        String cpf = limpar(f.getCpf());
        if (cpf != null) {
            String digitos = cpf.replaceAll("\\D", "");
            f.setCpf(digitos.isEmpty() ? cpf : digitos);
        } else {
            f.setCpf(null);
        }
    }

    private static String limpar(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Retorna a mensagem de erro, ou null se estiver tudo certo. */
    private String validar(Funcionario f, boolean edicao) {
        if (f.getNome() == null || f.getNome().length() < 3)
            return "Informe o nome completo (mínimo 3 caracteres).";
        if (f.getEmail() == null)
            return "Informe o e-mail.";
        if (f.getCpf() != null && !f.getCpf().matches("\\d{11}"))
            return "CPF inválido: informe os 11 dígitos (com ou sem pontuação).";

        String senha = f.getSenha();
        if (!edicao && (senha == null || senha.isBlank()))
            return "Informe a senha.";
        if (senha != null && !senha.isBlank() && senha.length() < 6)
            return "A senha deve ter pelo menos 6 caracteres.";

        Long id = f.getId();
        if (pertenceAOutro(funcionarioService.buscarPorEmail(f.getEmail()), id))
            return "Já existe um funcionário com o e-mail " + f.getEmail() + ".";
        if (funcionarioService.existeExcluidoPorEmail(f.getEmail()))
            return "O e-mail " + f.getEmail() + " pertence a um funcionário excluído.";

        if (f.getMatricula() != null) {
            if (pertenceAOutro(funcionarioService.buscarPorMatricula(f.getMatricula()), id))
                return "Já existe um funcionário com a matrícula " + f.getMatricula() + ".";
            if (funcionarioService.existeExcluidoPorMatricula(f.getMatricula()))
                return "A matrícula " + f.getMatricula() + " pertence a um funcionário excluído.";
        }

        if (f.getCpf() != null) {
            if (pertenceAOutro(funcionarioService.buscarPorCpf(f.getCpf()), id))
                return "Já existe um funcionário com este CPF.";
            if (funcionarioService.existeExcluidoPorCpf(f.getCpf()))
                return "Este CPF pertence a um funcionário excluído.";
        }
        return null;
    }

    private static boolean pertenceAOutro(Optional<Funcionario> encontrado, Long idAtual) {
        return encontrado.isPresent() && !encontrado.get().getId().equals(idAtual);
    }

    /** Traduz exceções de validação/banco em uma mensagem legível. */
    private static String mensagemDeErro(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return cve.getConstraintViolations().stream()
                        .map(ConstraintViolation::getMessage)
                        .distinct()
                        .collect(Collectors.joining("; "));
            }
            if (t instanceof DataIntegrityViolationException) {
                return "E-mail, matrícula ou CPF já cadastrados.";
            }
        }
        return "Não foi possível salvar o funcionário. Verifique os dados e tente novamente.";
    }

    /** Reexibe o formulário com os dados preenchidos e a mensagem de erro. */
    private String voltarComErro(Funcionario f, Model model, String tela, String erro) {
        f.setSenha(null); // nunca devolve a senha para a página
        model.addAttribute("funcionario", f);
        model.addAttribute("erroMsg", erro);
        return tela;
    }

    // CORRIGIDO (S5): era @GetMapping — este é o caso mais sensível de todos,
    // já que exclui contas de funcionários (inclusive administradores).
    // O template admin/administracao.html precisa passar a usar um
    // <form method="post"> em vez de <a href="...">.
    @PostMapping("/deletar/{id}")
    public String deletar(@PathVariable Long id) {
        try {
            funcionarioService.deletar(id);
            return "redirect:/admin?sucesso=true";
        } catch (IllegalArgumentException e) {
            return "redirect:/admin?erro=true";
        }
    }
}