package lk.slt.fieldops.dto;

import lk.slt.fieldops.entity.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PaymentService.getPaymentWithDetails(...) response — the Payment plus its per-material lines,
 * each carrying the material name/current price JOINed in from {@code materials} rather than
 * copied onto the junction row (see {@link PaymentMaterialDTO.LineResponse}, PAY-014's 2NF rule).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentDetailResponse {
    private Payment payment;
    private List<PaymentMaterialDTO.LineResponse> materials;
}
