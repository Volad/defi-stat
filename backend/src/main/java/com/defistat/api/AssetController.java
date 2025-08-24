package com.defistat.api;

import com.defistat.api.dto.AssetDTO;
import com.defistat.service.AssetService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Synchronous REST endpoint that returns Euler assets list from subgraph.
 * Example:
 *   GET /api/v1/assets?network=avalanche&verified=false&pageSize=500
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @GetMapping
    public List<AssetDTO> listAssets(
            @RequestParam(defaultValue = "avalanche") String network,
            @RequestParam(defaultValue = "false") boolean verified,
            @RequestParam(defaultValue = "500") int pageSize
    ) {
        return service.fetchAllAssets(network, verified, pageSize);
    }
}