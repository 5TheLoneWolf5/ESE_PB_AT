package org.example.transferencia.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.example.transferencia.domain.StatusTransferencia;
import org.example.transferencia.domain.Transferencia;
import org.example.transferencia.domain.TransferenciaRepository;
import org.example.transferencia.domain.value_objects.Dinheiro;
import org.example.transferencia.infrastructure.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferenciaProcessManagerTest {

    @Mock
    private TransferenciaRepository transferenciaRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ContaCommandClient contaCommandClient;

    private ObjectMapper objectMapper;
    private TransferenciaProcessManager processManager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        processManager = new TransferenciaProcessManager(
                transferenciaRepository,
                outboxRepository,
                contaCommandClient,
                objectMapper
        );
    }

    private Transferencia criarTransferenciaValida(Long id) {
        Transferencia t = new Transferencia(1L, 2L, new Dinheiro(new BigDecimal("100.00"), "BRL"));
        t.setId(id);
        return t;
    }

    @Test
    @DisplayName("Processa ContaDebitada com eventType explicito: debita origem e invoca credito no destino")
    void processaContaDebitadaComEventType() {
        Transferencia t = criarTransferenciaValida(10L);
        when(transferenciaRepository.findById(10L)).thenReturn(Optional.of(t));

        String json = """
            {
                "eventType": "ContaDebitada",
                "transferenciaId": 10,
                "contaId": 1,
                "valor": 100.00,
                "moeda": "BRL",
                "chaveIdempotencia": "transferencia-10-debito"
            }
            """;

        processManager.processarEventoConta(json);

        assertEquals(StatusTransferencia.CONTA_ORIGEM_DEBITADA, t.getStatus());
        verify(contaCommandClient).creditar(eq(2L), eq(new BigDecimal("100.00")), eq("transferencia-10-credito"), eq(10L));
        verify(transferenciaRepository, atLeastOnce()).save(t);
    }

    @Test
    @DisplayName("Processa ContaDebitada legado (sem eventType) por inferencia de chaveIdempotencia")
    void processaContaDebitadaPorInferencia() {
        Transferencia t = criarTransferenciaValida(11L);
        when(transferenciaRepository.findById(11L)).thenReturn(Optional.of(t));

        String json = """
            {
                "transferenciaId": 11,
                "contaId": 1,
                "valor": 100.00,
                "moeda": "BRL",
                "chaveIdempotencia": "transferencia-11-debito"
            }
            """;

        processManager.processarEventoConta(json);

        assertEquals(StatusTransferencia.CONTA_ORIGEM_DEBITADA, t.getStatus());
        verify(contaCommandClient).creditar(eq(2L), eq(new BigDecimal("100.00")), eq("transferencia-11-credito"), eq(11L));
    }

    @Test
    @DisplayName("Processa ContaCreditada com sucesso: conclui a saga (CONCLUIDA)")
    void processaContaCreditadaConcluiSaga() {
        Transferencia t = criarTransferenciaValida(12L);
        t.confirmarDebito(); // Estado CONTA_ORIGEM_DEBITADA
        when(transferenciaRepository.findById(12L)).thenReturn(Optional.of(t));

        String json = """
            {
                "eventType": "ContaCreditada",
                "transferenciaId": 12,
                "contaId": 2,
                "valor": 100.00,
                "moeda": "BRL",
                "chaveIdempotencia": "transferencia-12-credito"
            }
            """;

        processManager.processarEventoConta(json);

        assertEquals(StatusTransferencia.CONCLUIDA, t.getStatus());
        verify(transferenciaRepository, atLeastOnce()).save(t);
    }

    @Test
    @DisplayName("Falha no credito dispara compensacao imediata: estorna conta de origem e vai para CREDITO_FALHOU")
    void falhaNoCreditoDisparaCompensacao() {
        Transferencia t = criarTransferenciaValida(13L);
        when(transferenciaRepository.findById(13L)).thenReturn(Optional.of(t));

        doThrow(new RuntimeException("Conta destino 2 nao existe"))
                .when(contaCommandClient)
                .creditar(eq(2L), any(), any(), eq(13L));

        String json = """
            {
                "eventType": "ContaDebitada",
                "transferenciaId": 13,
                "contaId": 1,
                "valor": 100.00,
                "moeda": "BRL",
                "chaveIdempotencia": "transferencia-13-debito"
            }
            """;

        processManager.processarEventoConta(json);

        // Deve transicionar para CREDITO_FALHOU aguardando confirmacao de estorno
        assertEquals(StatusTransferencia.CREDITO_FALHOU, t.getStatus());
        // Deve ter chamado o credito de compensacao na conta de ORIGEM (1L)
        verify(contaCommandClient).creditar(eq(1L), eq(new BigDecimal("100.00")), eq("transferencia-13-compensacao"), eq(13L));
    }

    @Test
    @DisplayName("Processa ContaCreditada de compensacao: finaliza saga como COMPENSADA")
    void processaContaCreditadaCompensacao() {
        Transferencia t = criarTransferenciaValida(14L);
        t.confirmarDebito();
        t.falharCredito("Erro qualquer"); // Estado CREDITO_FALHOU
        when(transferenciaRepository.findById(14L)).thenReturn(Optional.of(t));

        String json = """
            {
                "eventType": "ContaCreditada",
                "transferenciaId": 14,
                "contaId": 1,
                "valor": 100.00,
                "moeda": "BRL",
                "chaveIdempotencia": "transferencia-14-compensacao"
            }
            """;

        processManager.processarEventoConta(json);

        assertEquals(StatusTransferencia.COMPENSADA, t.getStatus());
        verify(transferenciaRepository, atLeastOnce()).save(t);
    }

    @Test
    @DisplayName("Processa DebitoRejeitado: transiciona para DEBITO_FALHOU")
    void processaDebitoRejeitado() {
        Transferencia t = criarTransferenciaValida(15L);
        when(transferenciaRepository.findById(15L)).thenReturn(Optional.of(t));

        String json = """
            {
                "eventType": "DebitoRejeitado",
                "transferenciaId": 15,
                "motivo": "Saldo insuficiente"
            }
            """;

        processManager.processarEventoConta(json);

        assertEquals(StatusTransferencia.DEBITO_FALHOU, t.getStatus());
        verify(transferenciaRepository).save(t);
    }
}
