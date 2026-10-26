package com.orderflow.inventory.reservation;

import com.orderflow.inventory.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StockReservationControllerTest {
    @Test void conflictsReturn409AndUnknownIdentityReturns404() throws Exception {
        var service=mock(StockReservationService.class);UUID id=UUID.randomUUID();
        when(service.release(id)).thenThrow(new ReservationConflictException("already deducted"));
        when(service.get(id)).thenThrow(new ReservationNotFoundException());
        var mvc=MockMvcBuilders.standaloneSetup(new StockReservationController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/inventory/reservations/"+id+"/release")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/inventory/reservations/"+id)).andExpect(status().isNotFound());
    }
    @Test void rejectsEmptyBatchAndInvalidQuantity() throws Exception {
        var service=mock(StockReservationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new StockReservationController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String path="/api/v1/inventory/reservations/"+UUID.randomUUID();
        mvc.perform(put(path).contentType("application/json").content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path).contentType("application/json").content("{\"items\":[{\"productId\":\""+UUID.randomUUID()+"\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
