export interface AssetDTO {
  vaultAddress: string;
  vaultSymbol: string;
  vaultName: string;
  underlyingAddress: string;
  underlyingSymbol: string;
  underlyingName: string;
  underlyingDecimals: number;
}

export interface RoeHfRequest {
  network: string;
  collateralVault: string;
  borrowVault: string;
  leverage: number;
  ts?: string;
  collateralRewardsApyPct: number;
  borrowRewardsApyPct: number;
  liquidationThresholdPct?: number;
  priceCollateralUSD?: number;
  priceBorrowUSD?: number;
}

export interface RoeHfSeriesRequest {
  network: string;
  collateralVault: string;
  borrowVault: string;
  leverage: number;
  from: string;
  to: string;
  tickToleranceSeconds: number;
  collateralRewardsApyPct: number;
  borrowRewardsApyPct: number;
  liquidationThresholdPct?: number;
  priceCollateralUSD?: number;
  priceBorrowUSD?: number;
}

export interface RoeHfResponse {
  network: string;
  collateralVault: string;
  borrowVault: string;
  leverage: number;
  collateralTs: string | null;
  borrowTs: string | null;
  collateralSupplyApyPct: number;
  borrowBorrowApyPct: number;
  collateralRewardsApyPct: number;
  borrowRewardsApyPct: number;
  supplyTotalPct: number;
  borrowNetPct: number;
  collateralUtilPct: number;
  borrowUtilPct: number;
  priceCollateralUSD: number;
  priceBorrowUSD: number;
  liquidationThresholdPct: number;
  roePct: number;
  hf: number;
  note?: string;
}
