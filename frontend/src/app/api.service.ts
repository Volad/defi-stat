import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../environments/environment';
import { AssetDTO, RoeHfRequest, RoeHfSeriesRequest, RoeHFHistoryPoint } from './types';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = environment.apiBaseUrl;
  constructor(private http: HttpClient) {}

  getAssets(network = 'avalanche'): Observable<AssetDTO[]> {
    return this.http.get<AssetDTO[]>(`${this.base}/assets`, { params: { network } });
  }

  roeHfSeries(body: RoeHfSeriesRequest): Observable<RoeHFHistoryPoint[]> {
    return this.http.post<RoeHFHistoryPoint[]>(`${this.base}/roe-hf/series-eulerscan`, body);
  }

  roeHfPoint(body: RoeHfRequest): Observable<RoeHFHistoryPoint> {
    return this.http.post<RoeHFHistoryPoint>(`${this.base}/roe-hf`, body);
  }
}
