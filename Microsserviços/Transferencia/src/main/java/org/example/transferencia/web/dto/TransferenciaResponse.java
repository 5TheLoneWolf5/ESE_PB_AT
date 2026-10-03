package org.example.transferencia.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.example.transferencia.domain.StatusTransferencia;
import org.example.transferencia.domain.Transferencia;

public record TransferenciaResponse(
        Long id,
        Long contaOrigemId,
        Long contaDestinoId,
        BigDecimal valor,
        String moeda,
        StatusTransferencia status,
        LocalDateTime dataCriacao,
        LocalDateTime dataAtualizacao
) {
    public static TransferenciaResponse from(Transferencia t) {
        return new TransferenciaResponse(
                t.getId(),
                t.getContaOrigemId(),
                t.getContaDestinoId(),
                t.getValor(),
                t.getMoeda(),
                t.getStatus(),
                t.getDataCriacao(),
                t.getDataAtualizacao()
        );
    }
}

