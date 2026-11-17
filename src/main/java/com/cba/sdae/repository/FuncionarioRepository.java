package com.cba.sdae.repository;

import com.cba.sdae.model.Funcionario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FuncionarioRepository extends JpaRepository<Funcionario, Long> {

    Optional<Funcionario> findByEmail(String email);

    Optional<Funcionario> findByMatricula(String matricula);

    boolean existsByEmail(String email);

    boolean existsByMatricula(String matricula);

    List<Funcionario> findByCargo(String cargo);

    long countByCargo(String cargo);

    List<Funcionario> findByNivelAcesso(String nivelAcesso);

    long countByNivelAcesso(String nivelAcesso);

    // Consultas nativas: ignoram o @Where (soft-delete) da entidade e
    // enxergam também funcionários excluídos, que continuam ocupando o
    // e-mail/matrícula por causa das restrições UNIQUE do banco.
    @Query(value = "SELECT COUNT(*) FROM funcionarios WHERE email = :email AND excluido_em IS NOT NULL",
           nativeQuery = true)
    long countExcluidosPorEmail(@Param("email") String email);

    @Query(value = "SELECT COUNT(*) FROM funcionarios WHERE matricula = :matricula AND excluido_em IS NOT NULL",
           nativeQuery = true)
    long countExcluidosPorMatricula(@Param("matricula") String matricula);

    Optional<Funcionario> findByCpf(String cpf);

    @Query(value = "SELECT COUNT(*) FROM funcionarios WHERE cpf = :cpf AND excluido_em IS NOT NULL",
           nativeQuery = true)
    long countExcluidosPorCpf(@Param("cpf") String cpf);
}