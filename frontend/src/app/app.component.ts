import {Component, OnInit, OnDestroy, ViewChild} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule, ReactiveFormsModule, FormControl} from '@angular/forms';
import {ApiService} from './api.service';
import {AssetDTO, RoeHfResponse} from './types';

// ng2-charts
import {BaseChartDirective, NgChartsModule} from 'ng2-charts';
import {ChartConfiguration} from 'chart.js';

// Angular Material
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';
import {MatAutocompleteModule} from '@angular/material/autocomplete';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';

const DEFAULT_COLLATERAL = '0x39dE0f00189306062D79eDEC6DcA5bb6bFd108f9';
const DEFAULT_BORROW = '0xA45189636c04388ADBb4D865100DD155e55682EC';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [
        CommonModule, FormsModule, ReactiveFormsModule,
        // charts
        NgChartsModule,
        // material
        MatFormFieldModule, MatInputModule, MatAutocompleteModule, MatButtonModule, MatIconModule
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

    // Entire series kept here for the table
    series: RoeHfResponse[] = [];
    // Latest row (derived from series tail or point endpoint)
    latest?: RoeHfResponse;

    // Chart binding
    @ViewChild(BaseChartDirective) chart?: BaseChartDirective;
    lineChartData: ChartConfiguration<'line'>['data'] = {
        labels: [] as string[],
        datasets: [
            {data: [] as number[], label: 'Collateral Supply APY (%)', tension: 0.2},
            {data: [] as number[], label: 'Debt Borrow APY (%)', tension: 0.2},
            {data: [] as number[], label: 'ROE (%)', tension: 0.2}
        ]
    };
    lineChartOptions: ChartConfiguration<'line'>['options'] = {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {mode: 'index', intersect: false},
        plugins: {legend: {position: 'top'}, tooltip: {enabled: true}},
        scales: {y: {title: {display: true, text: 'APR (%)'}}}
    };

    private timer?: any;

    constructor(private api: ApiService) {
    }

    ngOnInit(): void {
        this.computeRange('1d');
        this.loadAssets();

        // filter options live
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

        // init inputs with defaults
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

            // Теперь, когда assets есть, задаём дефолтные значения контролов:
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
            this.setSeries(this.series);
            this.setupAutoRefresh();
        });
    }

    private setSeries(series: RoeHfResponse[]) {
        const labels = series.map(p => {
            const iso = p.collateralTs || p.borrowTs;
            return iso ? new Date(iso).toLocaleString() : '';
        });
        const dSupply = series.map(p => p.collateralSupplyApyPct ?? 0);
        const dBorrow = series.map(p => p.borrowBorrowApyPct ?? 0);
        const dRoe = series.map(p => p.roePct ?? 0);

        // Replace references so ng2-charts detects changes
        this.lineChartData = {
            labels,
            datasets: [
                {...this.lineChartData.datasets[0], data: dSupply},
                {...this.lineChartData.datasets[1], data: dBorrow},
                {...this.lineChartData.datasets[2], data: dRoe}
            ]
        };
        this.chart?.update();
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
            // обновляем latest и ДОБАВЛЯЕМ/ОБНОВЛЯЕМ в общей серии (для таблицы и графика)
            this.latest = point;

            const label = (point.collateralTs || point.borrowTs)
                ? new Date(point.collateralTs || point.borrowTs!).toISOString()
                : '';

            // найдём есть ли точка с таким же ts в series
            const idx = this.series.findIndex(x => {
                const tsX = (x.collateralTs || x.borrowTs) ?? '';
                return tsX === (point.collateralTs || point.borrowTs);
            });

            if (idx >= 0) {
                // заменяем точку
                const next = [...this.series];
                next[idx] = point;
                this.series = next;
            } else {
                // добавляем в конец
                this.series = [...this.series, point];
            }

            // пересобрать чарт из series
            this.setSeries(this.series);
        });
    }

    displayAsset = (value: string | { vaultAddress?: string } | null): string => {
        if (!value) return '';
        const addr = typeof value === 'string' ? value : (value?.vaultAddress ?? '');
        if (!addr) return '';

        const list = this.assets ?? [];                      // страховка от undefined
        const a = list.find(x => x.vaultAddress?.toLowerCase() === addr.toLowerCase());
        return a?.vaultSymbol + " - " + addr.substring(0, 10) ?? addr;                       // если не нашли — показываем адрес
    };
}