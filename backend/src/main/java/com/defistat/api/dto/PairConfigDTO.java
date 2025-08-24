package com.defistat.api.dto;

import jakarta.validation.constraints.*;

public class PairConfigDTO {
    @NotBlank public String collateralVault;
    @NotBlank public String borrowVault;
    @Positive public double leverage;
    @Min(30) public int intervalSeconds = 600;
    public boolean enabled = true;
    public String labelCollateral;
    public String labelBorrow;
}
