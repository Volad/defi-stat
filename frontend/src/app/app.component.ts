import { Component, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormControl } from '@angular/forms';
import { ApiService } from './api.service';
import { AssetDTO, RoeHFHistoryPoint } from './types';

// ng2-charts
import { BaseChartDirective, NgChartsModule } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';

// Angular Material (inputs, autocomplete, buttons, icons)
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';


// Angular Material (table + sorting)
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSort, MatSortModule } from '@angular/material/sort';

const DEFAULT_COLLATERAL = '0x39dE0f00189306062D79eDEC6DcA5bb6bFd108f9';
const DEFAULT_BORROW = '0xA45189636c04388ADBb4D865100DD155e55682EC';

export interface TableRow {
    /** Chosen timestamp for the row (prefer collateralTs, then borrowTs). */
    timestamp: Date;
    /** Collateral supply APR (%) */
    supply: number;
    /** Collateral rewards APR (%) */
    supplyReward: number;
    /** Borrow APR (%) */
    borrow: number;
    /** Borrow rewards APR (%) */
    borrowReward: number;
    /** Computed ROE (%) */
    roe: number;
}

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [
        CommonModule, FormsModule, ReactiveFormsModule,
        // charts
        NgChartsModule,
        // material (inputs, autocomplete, buttons, icons)
        MatFormFieldModule, MatInputModule, MatAutocompleteModule, MatButtonModule, MatIconModule,
        // material table + sorting
        MatTableModule, MatSortModule,MatCheckboxModule
    ],
    templateUrl: './app.component.html',
    styleUrls: ['../styles.css']
})
export class AppComponent implements OnInit, OnDestroy {
    title = 'DeFi ROE/HF Monitor';

    network = 'avalanche';
    assets: AssetDTO[] = [];

    // Material autocomplete controls
    collateralCtrl = new FormControl<string>('');
    borrowCtrl = new FormControl<string>('');
    collateralOptions: AssetDTO[] = [];
    borrowOptions: AssetDTO[] = [];

    // Selected addresses (used in API calls)
    collateral = DEFAULT_COLLATERAL;
    borrow = DEFAULT_BORROW;

    leverage = 3.0;
    rewardCollateral = 1.87;
    rewardBorrow = 0.0;

    // Range quick buttons
    rangeKey: '1d' | '30d' | '360d' = '1d';
    fromISO = '';
    toISO = '';

    // Entire series kept here for the chart + table
    series: RoeHFHistoryPoint[] = [];
    // Latest row (derived from series tail or point endpoint)
    latest?: RoeHFHistoryPoint;

    // ==== Chart binding ====
    @ViewChild(BaseChartDirective) chart?: BaseChartDirective;
    // --- chart datasets: 5 lines with default visibility as requested ---
    lineChartData: ChartConfiguration<'line'>['data'] = {
        labels: [] as string[],
        datasets: [
            {
                data: [] as number[],
                label: 'Collateral Supply APY (%)',
                tension: 0.2,
                hidden: false,
                borderColor: '#1f77b4',          // blue
                backgroundColor: '#1f77b4',
                pointBackgroundColor: '#1f77b4',
                pointBorderColor: '#1f77b4',
                fill: false
            },
            {
                data: [] as number[],
                label: 'Debt Borrow APY (%)',
                tension: 0.2,
                hidden: false,
                borderColor: '#d62728',          // red
                backgroundColor: '#d62728',
                pointBackgroundColor: '#d62728',
                pointBorderColor: '#d62728',
                fill: false
            },
            {
                data: [] as number[],
                label: 'ROE (%)',
                tension: 0.2,
                hidden: false,
                borderColor: '#2ca02c',          // green
                backgroundColor: '#2ca02c',
                pointBackgroundColor: '#2ca02c',
                pointBorderColor: '#2ca02c',
                fill: false
            },
            {
                data: [] as number[],
                label: 'Supply reward (%)',
                tension: 0.2,
                hidden: true,
                borderColor: '#9467bd',          // purple
                backgroundColor: '#9467bd',
                pointBackgroundColor: '#9467bd',
                pointBorderColor: '#9467bd',
                fill: false
            },
            {
                data: [] as number[],
                label: 'Borrow reward (%)',
                tension: 0.2,
                hidden: true,
                borderColor: '#ff7f0e',          // orange
                backgroundColor: '#ff7f0e',
                pointBackgroundColor: '#ff7f0e',
                pointBorderColor: '#ff7f0e',
                fill: false
            }
        ]
    };

    lineChartOptions: ChartConfiguration<'line'>['options'] = {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: { position: 'top' }, tooltip: { enabled: true } },
        scales: { y: {
                title: { display: true, text: 'APR (%)' }
            },
            x: {
                // Hide all tick labels on X axis
                ticks: {
                    display: false
                },
                grid: {
                    // Optional: if you also want to hide vertical grid lines
                    drawTicks: false
                }
            } }
    };

    // ==== Table binding (Material) ====
    /** Data source for the Material table. */
    dataSource = new MatTableDataSource<TableRow>([]);
    /** Displayed columns order. */
    displayedColumns = ['timestamp', 'supply', 'supplyReward', 'borrow', 'borrowReward', 'roe'];
    /** Sorting reference (attached in ngAfterViewInit via setter to avoid timing issues). */
    @ViewChild(MatSort, { static: true }) set matSort(sort: MatSort) {
        if (sort) {
            this.dataSource.sort = sort;
            // Provide custom accessor to sort by Date properly.
            this.dataSource.sortingDataAccessor = (item: TableRow, property: string): string | number => {
                switch (property) {
                    case 'timestamp':
                        return item.timestamp?.getTime?.() ?? 0;
                    case 'supply':
                        return item.supply ?? 0;
                    case 'supplyReward':
                        return item.supplyReward ?? 0;
                    case 'borrow':
                        return item.borrow ?? 0;
                    case 'borrowReward':
                        return item.borrowReward ?? 0;
                    case 'roe':
                        return item.roe ?? 0;
                    default:
                        return 0;
                }
            };
        }
    }

    private timer?: any;

    constructor(private api: ApiService) {}

    ngOnInit(): void {
        this.computeRange('1d');
        this.loadAssets();

        // Live filter options for autocomplete
        this.collateralCtrl.valueChanges.subscribe(val => {
            const q = (val || '').toLowerCase().trim();
            this.collateralOptions = this.assets.filter(a => !q ||
                a.vaultAddress.toLowerCase().includes(q) ||
                (a.vaultSymbol || '').toLowerCase().includes(q) ||
                (a.vaultName || '').toLowerCase().includes(q)
            );
        });
        this.borrowCtrl.valueChanges.subscribe(val => {
            const q = (val || '').toLowerCase().trim();
            this.borrowOptions = this.assets.filter(a => !q ||
                a.vaultAddress.toLowerCase().includes(q) ||
                (a.vaultSymbol || '').toLowerCase().includes(q) ||
                (a.vaultName || '').toLowerCase().includes(q)
            );
        });

        // Initialize inputs with defaults once assets arrive (see loadAssets()).
        // this.collateralCtrl.setValue(this.collateral);
        // this.borrowCtrl.setValue(this.borrow);
    }

    ngOnDestroy(): void {
        if (this.timer) clearInterval(this.timer);
    }

    loadAssets() {
        this.api.getAssets(this.network).subscribe(list => {
            this.assets = (list || []).sort((a, b) => (a.vaultSymbol || '').localeCompare(b.vaultSymbol || ''));
            this.collateralOptions = this.assets;
            this.borrowOptions = this.assets;

            // Assign default values to inputs after assets are ready
            if (!this.collateralCtrl.value) this.collateralCtrl.setValue(this.collateral);
            if (!this.borrowCtrl.value) this.borrowCtrl.setValue(this.borrow);
        });
    }

    onSelectCollateral(addr: string) {
        this.collateral = addr;
    }

    onSelectBorrow(addr: string) {
        this.borrow = addr;
    }

    computeRange(key: '1d' | '30d' | '360d') {
        this.rangeKey = key;
        const now = new Date();
        const to = now;
        const from = new Date(now);
        if (key === '1d') from.setDate(from.getDate() - 1);
        if (key === '30d') from.setDate(from.getDate() - 30);
        if (key === '360d') from.setDate(from.getDate() - 360);
        this.fromISO = from.toISOString();
        this.toISO = to.toISOString();
    }

    buildSeries() {
        if (!this.collateral || !this.borrow) return;
        const body = {
            network: this.network,
            collateralVault: this.collateral,
            borrowVault: this.borrow,
            leverage: this.leverage,
            from: this.fromISO,
            to: this.toISO,
            tickToleranceSeconds: 60,
            collateralRewardsApyPct: this.rewardCollateral,
            borrowRewardsApyPct: this.rewardBorrow
        };
        this.api.roeHfSeries(body as any).subscribe(series => {
            this.series = series ?? [];
            this.latest = this.series.length ? this.series[this.series.length - 1] : undefined;

            // Rebuild chart and table from the series
            this.setSeries(this.series);
            this.updateTableFromSeries(this.series);

            // Start auto-refresh loop
            this.setupAutoRefresh();
        });
    }

    private setSeries(series: RoeHFHistoryPoint[]) {
        const labels = series.map(p => {
            const iso = p.collateralTs || p.borrowTs;
            return iso ? new Date(iso).toLocaleString() : '';
        });

        // Core lines
        const dSupply       = series.map(p => p.collateralSupplyApyPct   ?? 0);
        const dBorrow       = series.map(p => p.borrowBorrowApyPct       ?? 0);
        const dRoe          = series.map(p => p.roePct                   ?? 0);

        // Rewards lines
        const dSupplyReward = series.map(p => p.collateralRewardsApyPct  ?? 0);
        const dBorrowReward = series.map(p => p.borrowRewardsApyPct      ?? 0);

        // Preserve current hidden flags while refreshing data
        const keep = (i: number) => this.lineChartData.datasets?.[i]?.hidden ?? false;

        this.lineChartData = {
            labels,
            datasets: [
                { ...this.lineChartData.datasets[0], data: dSupply,       hidden: keep(0) },
                { ...this.lineChartData.datasets[1], data: dBorrow,       hidden: keep(1) },
                { ...this.lineChartData.datasets[2], data: dRoe,          hidden: keep(2) },
                { ...this.lineChartData.datasets[3], data: dSupplyReward, hidden: keep(3) },
                { ...this.lineChartData.datasets[4], data: dBorrowReward, hidden: keep(4) }
            ]
        };

        this.chart?.update();
    }

    toggleDatasetVisibility(idx: number) {
        const ds = this.lineChartData.datasets[idx];
        if (!ds) return;
        ds.hidden = !ds.hidden;
        this.chart?.update();
    }

    /** Builds Material table rows from the backend series. */
    private updateTableFromSeries(series: RoeHFHistoryPoint[]) {
        const rows: TableRow[] = (series || []).map(p => {
            const tsStr = p.collateralTs || p.borrowTs || new Date().toISOString();
            const ts = new Date(tsStr);
            return {
                timestamp: ts,
                supply: p.collateralSupplyApyPct ?? 0,
                supplyReward: p.collateralRewardsApyPct ?? 0,
                borrow: p.borrowBorrowApyPct ?? 0,
                borrowReward: p.borrowRewardsApyPct ?? 0,
                roe: p.roePct ?? 0
            };
        });

        // Feed data into the table; sorting is already wired via @ViewChild(MatSort)
        this.dataSource.data = rows;
    }

    private setupAutoRefresh() {
        if (this.timer) clearInterval(this.timer);
        this.timer = setInterval(() => this.fetchLatestPoint(), 60_000);
        this.fetchLatestPoint();
    }

    private fetchLatestPoint() {
        if (!this.collateral || !this.borrow) return;
        const body = {
            network: this.network,
            collateralVault: this.collateral,
            borrowVault: this.borrow,
            leverage: this.leverage,
            collateralRewardsApyPct: this.rewardCollateral,
            borrowRewardsApyPct: this.rewardBorrow
        };
        this.api.roeHfPoint(body as any).subscribe(point => {
            this.latest = point;

            // Insert or replace the point in the local series (by matching timestamp)
            const idx = this.series.findIndex(x => {
                const tsX = (x.collateralTs || x.borrowTs) ?? '';
                const tsP = (point.collateralTs || point.borrowTs) ?? '';
                return tsX === tsP && tsX !== '';
            });

            if (idx >= 0) {
                const next = [...this.series];
                next[idx] = point;
                this.series = next;
            } else {
                this.series = [...this.series, point];
            }

            // Rebuild chart and table after the update
            this.setSeries(this.series);
            this.updateTableFromSeries(this.series);
        });
    }

    /** Display function for mat-autocomplete: prefer symbol + short address. */
    displayAsset = (value: string | { vaultAddress?: string } | null): string => {
        if (!value) return '';
        const addr = typeof value === 'string' ? value : (value?.vaultAddress ?? '');
        if (!addr) return '';
        const list = this.assets ?? [];
        const a = list.find(x => x.vaultAddress?.toLowerCase() === addr.toLowerCase());
        // Show "SYMBOL - 0x1234..." if available, otherwise address
        return a ? `${a.vaultSymbol ?? ''} - ${addr.substring(0, 10)}` : addr;
    };
}