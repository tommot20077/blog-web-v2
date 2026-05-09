package dowob.xyz.blog.module.reading.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateHighlightRequest {

    @NotBlank
    @Size(max = 500)
    private String snippet;

    @Size(max = 64)
    private String prefix = "";

    @Size(max = 64)
    private String suffix = "";

    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String color;

    @Size(max = 2000)
    private String note;
}
