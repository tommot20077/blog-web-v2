package dowob.xyz.blog.module.reading.model.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateProgressRequest {

    @NotNull
    @DecimalMin("0.000")
    @DecimalMax("1.000")
    private BigDecimal progress;

    @Size(max = 255)
    private String lastHeading;
}
