# DeFi Frontend (Angular CLI, Angular 18, ng2-charts 8)

## Quick start
```bash
npm install
npm start
# open http://localhost:4200
```

The app calls your backend:
  - GET  /api/v1/assets          (to populate collateral/borrow lists)
  - POST /api/v1/roe-hf/series   (to build the chart for selected range)
  - POST /api/v1/roe-hf          (to refresh the latest point every minute)

Configure the API base URL in `src/environments/environment.ts`.
