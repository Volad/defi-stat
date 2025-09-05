import { Component, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import {FormsModule, ReactiveFormsModule, FormControl, FormGroup} from '@angular/forms';
import { ApiService } from './api.service';
import { AssetDTO, RoeHFHistoryPoint } from './types';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
// Charts
import { BaseChartDirective, NgChartsModule } from 'ng2-charts';
import { Chart, ChartConfiguration } from 'chart.js';
import zoomPlugin from 'chartjs-plugin-zoom';
import 'chartjs-adapter-date-fns';

// Angular Material
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';

Chart.register(zoomPlugin);

const DEFAULT_COLLATERAL = '0x39dE0f00189306062D79eDEC6DcA5bb6bFd108f9';
const DEFAULT_BORROW     = '0xA45189636c04388ADBb4D865100DD155e55682EC';

export interface TableRow {
    timestamp: Date;
    supply: number;
    supplyReward: number;
    borrow: number;
    borrowReward: number;
    roe: number;
}

type ResampleMode = 'last' | 'avg';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [
        CommonModule, FormsModule, ReactiveFormsModule,
        NgChartsModule,
        MatFormFieldModule, MatInputModule, MatAutocompleteModule, MatButtonModule, MatIconModule,
        MatTableModule, MatSortModule, MatCheckboxModule, MatSelectModule,MatSliderModule,
        MatDatepickerModule, MatNativeDateModule
    ],
    templateUrl: './app.component.html',
    styleUrls: ['../styles.css']
})
export class AppComponent implements OnInit, OnDestroy {
    title = 'DeFi ROE/HF Monitor';

    // network selector
    network = 'avalanche';
    networks = ['avalanche','base','ethereum','arbitrum','bsc','unichain','tac'];
    brushMin = 0;
    brushMax = 1000;
    brushStart = 0;     // left thumb position (0..1000)
    brushEnd = 1000;    // right thumb position (0..1000)


    assets: AssetDTO[] = [];
    collateralCtrl = new FormControl<string>('');
    borrowCtrl = new FormControl<string>('');
    collateralOptions: AssetDTO[] = [];
    borrowOptions: AssetDTO[] = [];

    collateral = DEFAULT_COLLATERAL;
    borrow     = DEFAULT_BORROW;

    leverage = 3.0;
    rewardCollateral = 1.87;
    rewardBorrow = 0.0;

    rangeKey: '1d' | '30d' | '360d' = '1d';
    fromISO = '';
    toISO   = '';

    series: RoeHFHistoryPoint[] = [];
    latest?: RoeHFHistoryPoint;

    smoothing = true;
    samplesPerPixel = 0.6;

    // --- date range (defaults to last 7 days) ---
    dateFrom = new Date(Date.now() - 7 * 24 * 3600_000);
    dateTo   = new Date();

    dateRange = new FormGroup({
        start: new FormControl<Date | null>(this.dateFrom),
        end:   new FormControl<Date | null>(this.dateTo),
    });

    @ViewChild(BaseChartDirective) chart?: BaseChartDirective;

    lineChartData: ChartConfiguration<'line'>['data'] = {
        datasets: [
            { data: [] as {x:number;y:number}[], label: 'Collateral Supply APY (%)', tension: 0.35, hidden: false,
                borderColor: '#1f77b4', backgroundColor: '#1f77b4', pointBackgroundColor:'#1f77b4', pointBorderColor:'#1f77b4', fill:false },
            { data: [] as {x:number;y:number}[], label: 'Debt Borrow APY (%)', tension: 0.35, hidden: false,
                borderColor: '#d62728', backgroundColor: '#d62728', pointBackgroundColor:'#d62728', pointBorderColor:'#d62728', fill:false },
            { data: [] as {x:number;y:number}[], label: 'ROE (%)', tension: 0.00, hidden: false,
                borderColor: '#2ca02c', backgroundColor: '#2ca02c', pointBackgroundColor:'#2ca02c', pointBorderColor:'#2ca02c', fill:false },
            { data: [] as {x:number;y:number}[], label: 'Supply reward (%)', tension: 0.35, hidden: true,
                borderColor: '#9467bd', backgroundColor: '#9467bd', pointBackgroundColor:'#9467bd', pointBorderColor:'#9467bd', fill:false },
            { data: [] as {x:number;y:number}[], label: 'Borrow reward (%)', tension: 0.35, hidden: true,
                borderColor: '#ff7f0e', backgroundColor: '#ff7f0e', pointBackgroundColor:'#ff7f0e', pointBorderColor:'#ff7f0e', fill:false }
        ]
    };

    lineChartOptions: ChartConfiguration<'line'>['options'] = {
        responsive: true,
        maintainAspectRatio: false,
        parsing: false, // we pass {x,y} pairs
        interaction: { mode: 'index', intersect: false },
        elements: { point: { radius: 0 } },
        // add some room for tick labels at the bottom
        layout: {
            padding: { left: 12, right: 12, bottom: 18 }
        },
        scales: {
            x: {
                type: 'time',
                bounds: 'data',
                // Показываем редкие, равномерные тики
                ticks: {
                    display: true,
                    autoSkip: true,
                    maxTicksLimit: 8,     // <= ключ: ограничиваем количество подписей
                    maxRotation: 0,       // без наклона
                    sampleSize: 5,         // ускоряет расчёт тиков на больших массивах
                    padding: 6
                },
                time: {
                    // Адаптер (date-fns) сам подберёт формат по масштабу,
                    // но можно подсказать дефолты:
                    displayFormats: {
                        hour: 'MMM d HH:mm',
                        day: 'MMM d',
                        week: 'MMM d',
                        month: 'MMM yyyy'
                    },
                    tooltipFormat: 'yyyy-MM-dd HH:mm'
                },
                grid: { drawTicks: false }
            },
            y: {
                title: { display: true, text: 'APR (%)' },
                beginAtZero: false
            }
        },
        plugins: {
            legend: { position: 'top' },
            tooltip: { enabled: true },
            decimation: { enabled: true, algorithm: 'lttb', samples: 500 },
            zoom: ({
                limits: { x: { min: 'original', max: 'original' } },
                pan:   { enabled: true, mode: 'x', modifierKey: 'shift' },
                zoom:  { wheel: { enabled: true }, pinch: { enabled: true }, mode: 'x' },
                onZoomComplete: () => {
                    this.resampleForCurrentView();
                    this.recomputeDecimationSamples();
                    this.recomputeVisibleAverages();
                },
                onPanComplete:  () => {
                    this.resampleForCurrentView();
                    this.recomputeDecimationSamples();
                    this.recomputeVisibleAverages();
                }
            } as any)
        }
    };

    dataSource = new MatTableDataSource<TableRow>([]);
    displayedColumns = ['timestamp', 'supply', 'supplyReward', 'borrow', 'borrowReward', 'roe'];
    @ViewChild(MatSort, { static: true }) set matSort(sort: MatSort) {
        if (sort) {
            this.dataSource.sort = sort;
            this.dataSource.sortingDataAccessor = (item: TableRow, property: string): string | number => {
                switch (property) {
                    case 'timestamp':    return item.timestamp?.getTime?.() ?? 0;
                    case 'supply':       return item.supply ?? 0;
                    case 'supplyReward': return item.supplyReward ?? 0;
                    case 'borrow':       return item.borrow ?? 0;
                    case 'borrowReward': return item.borrowReward ?? 0;
                    case 'roe':          return item.roe ?? 0;
                    default:             return 0;
                }
            };
        }
    }

    // Панель агрегатов над графиком
    stats = {
        avg7d:   { supply: 0, borrow: 0, roe: 0 },
        avg30d:  { supply: 0, borrow: 0, roe: 0 },
        // avgVisible — СРЕДНИЕ ДЛЯ ТЕКУЩЕГО МАСШТАБА/ОКНА (то, что ты просил)
        avgVisible: { supply: 0, borrow: 0, roe: 0 }
    };

    private timer?: any;

    constructor(private api: ApiService) {}

    ngOnInit(): void {
        // init ISO range from date pickers
        this.fromISO = this.dateFrom.toISOString();
        this.toISO   = this.dateTo.toISOString();

        this.loadAssets();

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
    }

    ngOnDestroy(): void {
        if (this.timer) clearInterval(this.timer);
    }

    // --- network selector
    onNetworkChange() {
        this.assets = [];
        this.collateralOptions = [];
        this.borrowOptions = [];
        this.collateralCtrl.setValue('');
        this.borrowCtrl.setValue('');
        this.loadAssets();
    }

    loadAssets() {
        this.api.getAssets(this.network).subscribe(list => {
            this.assets = (list || []).sort((a, b) => (a.vaultSymbol || '').localeCompare(b.vaultSymbol || ''));
            this.collateralOptions = this.assets;
            this.borrowOptions = this.assets;
            if (!this.collateralCtrl.value) this.collateralCtrl.setValue(this.collateral);
            if (!this.borrowCtrl.value)     this.borrowCtrl.setValue(this.borrow);
        });
    }

    onSelectCollateral(addr: string) { this.collateral = addr; }
    onSelectBorrow(addr: string)     { this.borrow = addr; }

    computeRange(key: '1d' | '30d' | '360d') {
        this.rangeKey = key;
        const now = new Date();
        const to   = now;
        const from = new Date(now);
        if (key === '1d')   from.setDate(from.getDate() - 1);
        if (key === '30d')  from.setDate(from.getDate() - 30);
        if (key === '360d') from.setDate(from.getDate() - 360);
        this.fromISO = from.toISOString();
        this.toISO   = to.toISOString();
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

            this.resampleForFullExtent();
            this.updateTableFromSeries(this.series);
            this.recomputePresetStats();
            this.recomputeVisibleAverages();

            // this.setupAutoRefresh();
        });
    }

    /** Called on slider input; enforces ordering and applies the window. */
    onBrushChange(): void {
        // keep thumbs ordered and at least 1 step apart
        const s = Math.max(this.brushMin, Math.min(this.brushStart, this.brushMax));
        const e = Math.max(this.brushMin, Math.min(this.brushEnd,   this.brushMax));
        this.brushStart = Math.min(s, e - 1);
        this.brushEnd   = Math.max(e, this.brushStart + 1);

        this.applyBrushWindow();
    }

    /** Apply the picked date range and rebuild the series. */
    applyPickedRange(): void {
        // If end is not selected yet, assume "now"
        const start = this.dateRange.value.start ?? this.dateFrom;
        const end   = this.dateRange.value.end   ?? new Date();

        // Guard: ensure start <= end
        const s = start.getTime() <= end.getTime() ? start : end;
        const e = start.getTime() <= end.getTime() ? end   : start;

        this.dateFrom = s;
        this.dateTo   = e;
        this.fromISO  = s.toISOString();
        this.toISO    = e.toISOString();

        // Refetch series for the new range
        // this.buildSeries();
    }

    /** Converts [brushStart, brushEnd] into [minMs, maxMs] and resamples + recomputes stats. */
    private applyBrushWindow(): void {
        if (!this.series?.length) return;

        // Full series extent in ms (uses your helper if present)
        const ext = this.seriesExtentMs
            ? this.seriesExtentMs(this.series)
            : this.computeSeriesExtentFallback();

        const span = Math.max(1, ext.max - ext.min);

        // Map slider [0..1000] to time window within [ext.min..ext.max]
        const fracL = (this.brushStart - this.brushMin) / (this.brushMax - this.brushMin);
        const fracR = (this.brushEnd   - this.brushMin) / (this.brushMax - this.brushMin);
        const minMs = Math.floor(ext.min + span * Math.max(0, Math.min(1, fracL)));
        const maxMs = Math.floor(ext.min + span * Math.max(0, Math.min(1, fracR)));

        // Resample datasets for the current view window
        if (typeof (this as any).resampleAndApply === 'function') {
            (this as any).resampleAndApply(minMs, maxMs);
        }

        // Recompute "AVG SUPPLY/DEBT/ROE" for just the visible window
        this.recomputeStatsInWindow(minMs, maxMs);

        this.chart?.update('none');
    }

    /** Recomputes the top-panel averages for an arbitrary [minMs, maxMs] window. */
    private recomputeStatsInWindow(minMs: number, maxMs: number): void {
        const inWin = (p: RoeHFHistoryPoint) => {
            const iso = p.collateralTs || p.borrowTs;
            if (!iso) return false;
            const t = new Date(iso).getTime();
            return !Number.isNaN(t) && t >= minMs && t <= maxMs;
        };

        const subset = this.series.filter(inWin);
        const avg = (arr: number[]) => (arr.length ? arr.reduce((a,b)=>a+b,0) / arr.length : 0);

        const supply = subset.map(p => p.collateralSupplyApyPct ?? 0);
        const borrow = subset.map(p => p.borrowBorrowApyPct   ?? 0);
        const roe    = subset.map(p => p.roePct               ?? 0);

        // Update only the "AVG (visible)" block; keep your 7d/30d if you show them elsewhere.
        this.stats.avgVisible = {
            supply: avg(supply),
            borrow: avg(borrow),
            roe:    avg(roe)
        };
    }

    /** Fallback extent if you don't have seriesExtentMs helper for some reason. */
    private computeSeriesExtentFallback() {
        let min = Number.POSITIVE_INFINITY;
        let max = Number.NEGATIVE_INFINITY;
        for (const p of this.series) {
            const iso = p.collateralTs || p.borrowTs;
            if (!iso) continue;
            const t = new Date(iso).getTime();
            if (Number.isNaN(t)) continue;
            if (t < min) min = t;
            if (t > max) max = t;
        }
        if (!isFinite(min) || !isFinite(max)) {
            const now = Date.now();
            return { min: now - 24 * 3600_000, max: now };
        }
        return { min, max };
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

            this.resampleForCurrentView();
            this.updateTableFromSeries(this.series);
            this.recomputePresetStats();
            this.recomputeVisibleAverages(); // обновить средние по текущему масштабу
        });
    }

    // ---------- resampling utilities ----------

    private seriesExtentMs(series: RoeHFHistoryPoint[]) {
        let min = Number.POSITIVE_INFINITY;
        let max = Number.NEGATIVE_INFINITY;
        for (const p of series) {
            const iso = p.collateralTs || p.borrowTs;
            if (!iso) continue;
            const t = new Date(iso).getTime();
            if (Number.isNaN(t)) continue;
            if (t < min) min = t;
            if (t > max) max = t;
        }
        if (!isFinite(min) || !isFinite(max)) {
            const now = Date.now();
            return { min: now - 24*3600_000, max: now };
        }
        return { min, max };
    }

    /**
     * Decide bucket size and aggregation mode based on the selected window duration.
     *
     * Rules:
     *  - if selected < 7 days  -> show minimal granularity (1h), use 'last' point in each hour
     *  - if selected > 7 days  -> 2h average
     *  - if selected > 30 days -> 5h average
     *  - if selected > 50 days -> 1 day average
     *
     * Notes:
     *  - Boundaries are strict: e.g. exactly 7d uses the "2h avg" branch (because it's not < 7d).
     */
    private pickBucketByDuration(ms: number): { bucketHours: number; mode: ResampleMode } {
        const dayMs = 24 * 3600_000;

        // < 7d  -> 1h buckets, take 'last' (preserves your finest backend resolution)
        if (ms < 7 * dayMs) {
            return { bucketHours: 1, mode: 'last' };
        }

        // 7d .. 30d -> 2h average
        if (ms <= 30 * dayMs) {
            return { bucketHours: 2, mode: 'avg' };
        }

        // 30d .. 50d -> 5h average
        if (ms <= 50 * dayMs) {
            return { bucketHours: 5, mode: 'avg' };
        }

        // > 50d -> 24h (1 day) average
        return { bucketHours: 24, mode: 'avg' };
    }

    private resampleForCurrentView() {
        if (this.chart?.chart) {
            const scaleX: any = (this.chart.chart as any).scales?.x;
            const min = typeof scaleX?.min === 'number' ? scaleX.min : undefined;
            const max = typeof scaleX?.max === 'number' ? scaleX.max : undefined;
            if (min !== undefined && max !== undefined && max > min) {
                this.resampleAndApply(min, max);
                return;
            }
        }
        this.resampleForFullExtent();
    }

    private resampleForFullExtent() {
        const ext = this.seriesExtentMs(this.series);
        this.resampleAndApply(ext.min, ext.max);
    }

    private resampleAndApply(minMs: number, maxMs: number) {
        const windowed = this.series.filter(p => {
            const iso = p.collateralTs || p.borrowTs;
            if (!iso) return false;
            const t = new Date(iso).getTime();
            return !Number.isNaN(t) && t >= minMs && t <= maxMs;
        });

        const duration = Math.max(1, maxMs - minMs);
        const { bucketHours, mode } = this.pickBucketByDuration(duration);

        const res = this.resampleSeries(windowed, bucketHours, mode);
        const keepHidden = (i: number) => this.lineChartData.datasets?.[i]?.hidden ?? false;

        this.lineChartData = {
            datasets: [
                { ...this.lineChartData.datasets[0], data: res.supply,   hidden: keepHidden(0) },
                { ...this.lineChartData.datasets[1], data: res.borrow,   hidden: keepHidden(1) },
                { ...this.lineChartData.datasets[2], data: res.roe,      hidden: keepHidden(2) },
                { ...this.lineChartData.datasets[3], data: res.sReward,  hidden: keepHidden(3) },
                { ...this.lineChartData.datasets[4], data: res.bReward,  hidden: keepHidden(4) }
            ]
        };

        this.applySmoothing();
        this.chart?.update('none');
    }

    private resampleSeries(series: RoeHFHistoryPoint[], bucketHours: number, mode: ResampleMode) {
        if (!series?.length) {
            return { supply:[], borrow:[], roe:[], sReward:[], bReward:[] } as Record<string,{x:number;y:number}[]>;
        }

        const msPerBucket = bucketHours * 3600_000;
        const buckets = new Map<number, RoeHFHistoryPoint[]>();

        for (const p of series) {
            const iso = p.collateralTs || p.borrowTs;
            if (!iso) continue;
            const t = new Date(iso).getTime();
            const bucketStart = Math.floor(t / msPerBucket) * msPerBucket;
            const arr = buckets.get(bucketStart) || [];
            arr.push(p);
            buckets.set(bucketStart, arr);
        }

        const avg = (nums: number[]) => nums.length ? nums.reduce((a,b)=>a+b,0) / nums.length : 0;
        const pickLast = (pts: RoeHFHistoryPoint[], sel: (p:RoeHFHistoryPoint)=>number) => {
            let best: RoeHFHistoryPoint | undefined;
            let bestT = -1;
            for (const p of pts) {
                const iso = p.collateralTs || p.borrowTs;
                if (!iso) continue;
                const t = new Date(iso).getTime();
                if (t >= bestT) { bestT = t; best = p; }
            }
            return best ? sel(best) : 0;
        };

        const toVal = {
            supply: (p: RoeHFHistoryPoint) => p.collateralSupplyApyPct ?? 0,
            borrow: (p: RoeHFHistoryPoint) => p.borrowBorrowApyPct ?? 0,
            roe:    (p: RoeHFHistoryPoint) => p.roePct ?? 0,
            sReward:(p: RoeHFHistoryPoint) => p.collateralRewardsApyPct ?? 0,
            bReward:(p: RoeHFHistoryPoint) => p.borrowRewardsApyPct ?? 0,
        };

        const result = {
            supply: [] as {x:number;y:number}[],
            borrow: [] as {x:number;y:number}[],
            roe:    [] as {x:number;y:number}[],
            sReward: [] as {x:number;y:number}[],
            bReward: [] as {x:number;y:number}[],
        };

        const keys = Array.from(buckets.keys()).sort((a,b)=>a-b);

        for (const k of keys) {
            const pts = buckets.get(k)!;

            if (mode === 'last') {
                result.supply.push ({ x: k, y: pickLast(pts, toVal.supply)  });
                result.borrow.push ({ x: k, y: pickLast(pts, toVal.borrow)  });
                result.roe.push    ({ x: k, y: pickLast(pts, toVal.roe)     });
                result.sReward.push({ x: k, y: pickLast(pts, toVal.sReward) });
                result.bReward.push({ x: k, y: pickLast(pts, toVal.bReward) });
            } else {
                result.supply.push ({ x: k, y: avg(pts.map(toVal.supply))   });
                result.borrow.push ({ x: k, y: avg(pts.map(toVal.borrow))   });
                result.roe.push    ({ x: k, y: avg(pts.map(toVal.roe))      });
                result.sReward.push({ x: k, y: avg(pts.map(toVal.sReward))  });
                result.bReward.push({ x: k, y: avg(pts.map(toVal.bReward))  });
            }
        }

        return result;
    }

    toggleDatasetVisibility(idx: number) {
        const ds = this.lineChartData.datasets[idx];
        if (!ds) return;
        ds.hidden = !ds.hidden;
        this.chart?.update();
        // если включили/выключили линии supply/borrow/roe — сразу пересчитать видимые средние
        this.recomputeVisibleAverages();
    }

    applySmoothing() {
        const t = this.smoothing ? 0.35 : 0.0;
        this.lineChartData.datasets = this.lineChartData.datasets.map(ds => {
            if (ds.label?.startsWith('ROE')) return { ...ds, tension: 0.0 };
            return { ...ds, tension: t };
        });
    }

    private recomputeDecimationSamples() {
        if (!this.chart?.chart) return;
        const c = this.chart.chart;
        const plotWidth = (c.chartArea?.right ?? c.width) - (c.chartArea?.left ?? 0);
        const target = Math.max(80, Math.min(3000, Math.floor(plotWidth * this.samplesPerPixel)));
        (c.options.plugins as any).decimation.samples = target;
        c.update('none');
    }

    // ---------- table & stats ----------

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
        this.dataSource.data = rows;
    }

    private recomputePresetStats() {
        const now = Date.now();
        const tsOf = (p: RoeHFHistoryPoint) => new Date((p.collateralTs || p.borrowTs)!).getTime();
        const withinDays = (days: number) => {
            const minTs = now - days * 24 * 3600_000;
            return this.series.filter(p => {
                const t = tsOf(p);
                return !Number.isNaN(t) && t >= minTs && t <= now;
            });
        };
        const avg = (arr: number[]) => (arr.length ? arr.reduce((a,b)=>a+b,0) / arr.length : 0);
        const pickVals = (src: RoeHFHistoryPoint[]) => ({
            supply: src.map(p => p.collateralSupplyApyPct ?? 0),
            borrow: src.map(p => p.borrowBorrowApyPct ?? 0),
            roe:    src.map(p => p.roePct ?? 0),
        });

        const s7  = withinDays(7);  const v7  = pickVals(s7);
        this.stats.avg7d.supply = avg(v7.supply);
        this.stats.avg7d.borrow = avg(v7.borrow);
        this.stats.avg7d.roe    = avg(v7.roe);

        const s30 = withinDays(30); const v30 = pickVals(s30);
        this.stats.avg30d.supply = avg(v30.supply);
        this.stats.avg30d.borrow = avg(v30.borrow);
        this.stats.avg30d.roe    = avg(v30.roe);
    }

    /**
     * ГЛАВНОЕ: пересчёт средних по ТЕКУЩЕМУ ВИДИМОМУ ОКНУ X.
     * Берём данные именно из текущих datasets (после ресэмплинга)
     * и усредняем точки, попадающие в диапазон шкалы X.
     */
    private recomputeVisibleAverages() {
        if (!this.chart?.chart) {
            this.stats.avgVisible = { supply: 0, borrow: 0, roe: 0 };
            return;
        }
        const c: any = this.chart.chart;
        const scaleX = c.scales?.x;
        const min = typeof scaleX?.min === 'number' ? scaleX.min : undefined;
        const max = typeof scaleX?.max === 'number' ? scaleX.max : undefined;

        // если скейл не готов — считаем по всему набору точек, который сейчас в datasets
        const inRange = (x: number) =>
            min === undefined || max === undefined ? true : (x >= min && x <= max);

        const pick = (idx: number) => {
            const ds = this.lineChartData.datasets[idx]?.data as {x:number;y:number}[] || [];
            const vals = ds.filter(p => inRange(p.x)).map(p => p.y);
            if (!vals.length) return 0;
            const s = vals.reduce((a,b)=>a+b,0);
            return s / vals.length;
        };

        this.stats.avgVisible.supply = pick(0); // supply
        this.stats.avgVisible.borrow = pick(1); // borrow
        this.stats.avgVisible.roe    = pick(2); // roe
    }

    // ---- UI helpers ----
    displayAsset = (value: string | { vaultAddress?: string } | null): string => {
        if (!value) return '';
        const addr = typeof value === 'string' ? value : (value?.vaultAddress ?? '');
        if (!addr) return '';
        const list = this.assets ?? [];
        const a = list.find(x => x.vaultAddress?.toLowerCase() === addr.toLowerCase());
        return a ? `${a.vaultSymbol ?? ''} - ${addr.substring(0, 10)}` : addr;
    };
}