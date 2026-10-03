package org.example.transferencia.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.example.transferencia.domain.events.CompensacaoConfirmada;
import org.example.transferencia.domain.events.CreditoConfirmado;
import org.example.transferencia.domain.events.CreditoFalhou;
import org.example.transferencia.domain.events.DebitoConfirmado;
import org.example.transferencia.domain.events.TransferenciaIniciada;
import org.example.transferencia.domain.value_objects.Dinheiro;

@Entity
@Table(name = "transferencia")
public class Transferencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conta_origem_id", nullable = false)
    private Long contaOrigemId;

    @Column(name = "conta_destino_id", nullable = false)
    private Long contaDestinoId;

    @Column(name = "valor", nullable = false)
    private BigDecimal valor;

    @Column(name = "moeda", nullable = false)
    private String moeda;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StatusTransferencia status;

    @Version
    private Long versao;

    @Column(name = "data_criacao", updatable = false)
    private LocalDateTime dataCriacao;

    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    @Transient
    private final List<Object> eventosNaoPublicados = new ArrayList<>();

    protected Transferencia() {
    }

    @PrePersist
    protected void onCreate() {
        this.dataCriacao = LocalDateTime.now();
        this.dataAtualizacao = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.dataAtualizacao = LocalDateTime.now();
    }

    public Transferencia(Long contaOrigemId, Long contaDestinoId, Dinheiro dinheiro) {
        Objects.requireNonNull(contaOrigemId, "Conta de origem não pode ser nula.");
        Objects.requireNonNull(contaDestinoId, "Conta de destino não pode ser nula.");
        Objects.requireNonNull(dinheiro, "Dinheiro não pode ser nulo.");

        if (contaOrigemId.equals(contaDestinoId)) {
            throw new IllegalArgumentException("Conta de origem não pode ser a mesma que a de destino.");
        }

        if (dinheiro.valor().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Dinheiro de transferência deve ser maior que zero.");
        }

        this.contaOrigemId = contaOrigemId;
        this.contaDestinoId = contaDestinoId;
        this.valor = dinheiro.valor();
        this.moeda = dinheiro.moeda();
        this.status = StatusTransferencia.INICIADA;

        this.eventosNaoPublicados.add(new TransferenciaIniciada(
                this.id,
                contaOrigemId,
                contaDestinoId,
                this.valor,
                this.moeda,
                Instant.now()
        ));
    }

    private void exigirStatus(StatusTransferencia esperado) {
        if (this.status != esperado) {
            throw new IllegalStateException(
                    "Status inválido para transição: " + this.status + " (esperado: " + esperado + ")"
            );
        }
    }

    public void confirmarDebito() {
        exigirStatus(StatusTransferencia.INICIADA);
        this.status = StatusTransferencia.CONTA_ORIGEM_DEBITADA;
        this.eventosNaoPublicados.add(new DebitoConfirmado(this.id, Instant.now()));
    }

    public void confirmarCredito() {
        exigirStatus(StatusTransferencia.CONTA_ORIGEM_DEBITADA);
        this.status = StatusTransferencia.CONCLUIDA;
        this.eventosNaoPublicados.add(new CreditoConfirmado(this.id, Instant.now()));
    }

    public void falharCredito(String motivo) {
        exigirStatus(StatusTransferencia.CONTA_ORIGEM_DEBITADA);
        this.status = StatusTransferencia.CREDITO_FALHOU;
        this.eventosNaoPublicados.add(new CreditoFalhou(this.id, motivo, Instant.now()));
    }

    public void confirmarCompensacao() {
        exigirStatus(StatusTransferencia.CREDITO_FALHOU);
        this.status = StatusTransferencia.COMPENSADA;
        this.eventosNaoPublicados.add(new CompensacaoConfirmada(this.id, Instant.now()));
    }

    public void falharDebito() {
        exigirStatus(StatusTransferencia.INICIADA);
        this.status = StatusTransferencia.DEBITO_FALHOU;
    }

    public void falharCompensacao() {
        exigirStatus(StatusTransferencia.CREDITO_FALHOU);
        this.status = StatusTransferencia.COMPENSACAO_FALHOU;
    }

    public List<Object> eventosNaoPublicados() {
        List<Object> eventosAjustados = new ArrayList<>();
        for (Object evento : this.eventosNaoPublicados) {
            if (evento instanceof TransferenciaIniciada ti && ti.transferenciaId() == null && this.id != null) {
                eventosAjustados.add(new TransferenciaIniciada(
                        this.id,
                        ti.contaOrigemId(),
                        ti.contaDestinoId(),
                        ti.valor(),
                        ti.moeda(),
                        ti.ocorridoEm()
                ));
            } else {
                eventosAjustados.add(evento);
            }
        }
        return Collections.unmodifiableList(eventosAjustados);
    }

    public void limparEventos() {
        this.eventosNaoPublicados.clear();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getContaOrigemId() {
        return contaOrigemId;
    }

    public Long getContaDestinoId() {
        return contaDestinoId;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getMoeda() {
        return moeda;
    }

    public Dinheiro getDinheiro() {
        return new Dinheiro(valor, moeda);
    }

    public StatusTransferencia getStatus() {
        return status;
    }

    public Long getVersao() {
        return versao;
    }

    public LocalDateTime getDataCriacao() {
        return dataCriacao;
    }

    public LocalDateTime getDataAtualizacao() {
        return dataAtualizacao;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transferencia that = (Transferencia) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Transferencia{" +
                "id=" + id +
                ", contaOrigemId=" + contaOrigemId +
                ", contaDestinoId=" + contaDestinoId +
                ", valor=" + valor +
                ", moeda='" + moeda + '\'' +
                ", status=" + status +
                ", versao=" + versao +
                '}';
    }
}
