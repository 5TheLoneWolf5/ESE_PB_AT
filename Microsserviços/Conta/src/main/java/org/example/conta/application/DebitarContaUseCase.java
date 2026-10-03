package org.example.conta.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.example.conta.domain.Conta;
import org.example.conta.domain.ContaRepository;
import org.example.conta.domain.SaldoInsuficienteException;
import org.example.conta.domain.events.DebitoRejeitado;
import org.example.conta.infrastructure.persistence.ChaveIdempotencia;
import org.example.conta.infrastructure.persistence.ChaveIdempotenciaRepository;
import org.example.conta.infrastructure.persistence.ContaOutboxMessage;
import org.example.conta.infrastructure.persistence.ContaOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DebitarContaUseCase {

    private static final Logger log = LoggerFactory.getLogger(DebitarContaUseCase.class);

    private final ContaRepository contaRepository;
    private final ContaOutboxRepository outboxRepository;
    private final ChaveIdempotenciaRepository idempotenciaRepository;
    private final ObjectMapper objectMapper;

    public DebitarContaUseCase(
            ContaRepository contaRepository,
            ContaOutboxRepository outboxRepository,
            ChaveIdempotenciaRepository idempotenciaRepository,
            ObjectMapper objectMapper
    ) {
        this.contaRepository = contaRepository;
        this.outboxRepository = outboxRepository;
        this.idempotenciaRepository = idempotenciaRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = SaldoInsuficienteException.class)
    public void execute(Long contaId, BigDecimal valor, String chaveIdempotencia, Long transferenciaId) {
        if (chaveIdempotencia != null && !chaveIdempotencia.isBlank()) {
            if (idempotenciaRepository.existsById(chaveIdempotencia)) {
                log.info("Comando de debito duplicado ignorado (chave idempotente ja processada: {})", chaveIdempotencia);
                return;
            }
        }

        Conta conta = contaRepository.findById(contaId)
                .orElseThrow(() -> new IllegalArgumentException("Conta não encontrada com o ID: " + contaId));

        try {
            conta.debitar(valor, chaveIdempotencia, transferenciaId);
            contaRepository.save(conta);

            List<Object> eventos = conta.eventosNaoPublicados();
            for (Object evento : eventos) {
                String payload = objectMapper.writeValueAsString(evento);
                ContaOutboxMessage outbox = new ContaOutboxMessage("Conta", conta.getId(), evento.getClass().getSimpleName(), payload);
                outboxRepository.save(outbox);
            }
            conta.limparEventos();

            if (chaveIdempotencia != null && !chaveIdempotencia.isBlank()) {
                idempotenciaRepository.save(new ChaveIdempotencia(chaveIdempotencia, "DEBITO", contaId, "SUCESSO"));
            }
        } catch (SaldoInsuficienteException e) {
            log.warn("Saldo insuficiente para debito na conta {}: {}", contaId, e.getMessage());
            if (transferenciaId != null) {
                try {
                    DebitoRejeitado rejeitado = new DebitoRejeitado(contaId, valor, e.getMessage(), chaveIdempotencia, transferenciaId, Instant.now());
                    String payload = objectMapper.writeValueAsString(rejeitado);
                    outboxRepository.save(new ContaOutboxMessage("Conta", contaId, "DebitoRejeitado", payload));
                } catch (Exception ex) {
                    log.error("Erro ao salvar DebitoRejeitado no outbox", ex);
                }
            }
            if (chaveIdempotencia != null && !chaveIdempotencia.isBlank()) {
                idempotenciaRepository.save(new ChaveIdempotencia(chaveIdempotencia, "DEBITO", contaId, "SALDO_INSUFICIENTE"));
            }
            throw e;
        } catch (Exception e) {
            log.error("Erro inesperado ao debitar conta {}: {}", contaId, e.getMessage(), e);
            throw new RuntimeException("Erro ao processar débito: " + e.getMessage(), e);
        }
    }
}
