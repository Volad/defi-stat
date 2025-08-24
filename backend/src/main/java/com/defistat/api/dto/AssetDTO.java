package com.defistat.api.dto;

import lombok.Data;

@Data
public class AssetDTO {
    public String vaultAddress;   // id в сабграфе
    public String vaultSymbol;
    public String vaultName;
}