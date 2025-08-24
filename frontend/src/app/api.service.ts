import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../environments/environment';
import { AssetDTO, RoeHfRequest, RoeHfSeriesRequest, RoeHfResponse } from './types';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = environment.apiBaseUrl;
  constructor(private http: HttpClient) {}

  getAssets(network = 'avalanche'): Observable<AssetDTO[]> {
    return this.http.get<AssetDTO[]>(`${this.base}/assets`, { params: { network } });
  }

  roeHfSeries(body: RoeHfSeriesRequest): Observable<RoeHfResponse[]> {
    return this.http.post<RoeHfResponse[]>(`${this.base}/roe-hf/series`, body);
  }

  roeHfPoint(body: RoeHfRequest): Observable<RoeHfResponse> {
    return this.http.post<RoeHfResponse>(`${this.base}/roe-hf`, body);
  }
}
