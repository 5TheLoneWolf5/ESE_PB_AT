package org.example.transferencia.domain.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record CompensacaoConfirmada(
        Long transferenciaId,
        Instant ocorridoEm
) {
    @JsonProperty("eventType")
    public String eventType() {
        return "CompensacaoConfirmada";
    }
}

