package org.example.conta.domain.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

public record ContaDebitada(
        Long contaId,
        BigDecimal valor,
        String moeda,
        String chaveIdempotencia,
        Long transferenciaId,
        Instant ocorridoEm
) {
    @JsonProperty("eventType")
    public String eventType() {
        return "ContaDebitada";
    }
}

