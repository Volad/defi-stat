# DeFi-Stat (EULER ONLY)

**DeFi-Stat** is a full-stack project for monitoring and analyzing DeFi metrics such as **Supply APY, Borrow APY, ROE (Return on Equity), Health Factor**, and reward yields.  
It combines a Java/Spring Boot backend, an Angular frontend, and MongoDB storage, fully containerized with Docker.

## Disclaimer

> ⚡ The majority of the code (backend and frontend) was generated with the assistance of **ChatGPT** and later slightly refined manually.


> ❗❗ This project was created **only for personal use and experimental purposes**.  
It does **not guarantee stability, future functionality, or ongoing support**.  
❗❗


## How to Use

1. **Choose network**
    - Use the **Network** selector to pick `avalanche`, `base`, or `ethereum`.
    - The app currently integrates **only with Euler**, using **EulerScan** as the data source.

2. **Pick two vaults**
    - **Collateral vault** — the asset you supply.
    - **Borrow vault** — the asset you borrow.
    - You can search by address, symbol, or name.

3. **Set parameters**
    - **Leverage** — used to compute ROE.
    - **Rewards (Collateral % / Borrow %)** — manual defaults for reward APR.
    - These are applied **only if** Merkl rewards data is missing for a period **and** the corresponding campaign is still active (see `com.defistat.service.RewardAprResolver`).
    - As soon as actual Merkl data is ingested, the real values override the manual defaults.
    - This is useful if you deposited into a vault **before** Defi-Stat started running. For snapshots before Merkl ingestion, your manual % will be used. After ingestion, actual data takes precedence.

4. **Pick a date range**
    - Default is last **7 days**.
    - Use the date picker and click **Build Chart**.
    - The chart resamples automatically depending on the window:
        - `< 7 days`: hourly
        - `> 7 days`: 2h average
        - `> 30 days`: 5h average
        - `> 50 days`: daily average
    - You can zoom, pan, or adjust with the range slider.  
      The **AVG Supply/Debt/ROE** in the summary panel updates to match the visible range.

5. **Read the outputs**
    - **Chart**: Supply APY, Borrow APY, ROE (and optional reward lines).
    - **Averages panel**: 7d, 30d, and visible window averages.
    - **Table**: Raw or bucketed points aligned with backend snapshots.
    - 
---

## Technology Stack

- **Backend**
    - Java 21, Spring Boot 3
    - MongoDB for persistence
    - REST API endpoints (`/api/v1/...`)
    - Integration:
        - **Euler protocol only** (no other DeFi protocols supported)
        - [EulerScan API](https://api.eulerscan.xyz/) for historical hourly snapshots
        - web3j for on-chain calls if needed

- **Frontend**
    - Angular 18 + Angular Material
    - ng2-charts (Chart.js v4)
    - Responsive UI with filtering, date range picker, and resampling logic

- **Database**
    - MongoDB (running locally or via Docker)

- **Containerization**
    - Dockerfile for backend and frontend
    - Docker Compose for full local stack

---

## Features

- **Vault selection**
    - Choose collateral and borrow assets from available Euler vaults.
    - Filter by symbol, name, or address.

- **Metrics visualization**
    - Line charts for:
        - Collateral Supply APY
        - Borrow APY
        - ROE (with leverage and reward APY taken into account)
    - Interactive resampling (hourly, 2h, 5h, daily) depending on selected time window.
    - Zoom, pan, and range slider for fine-grained control.

- **Rewards integration**
    - (Experimental) Merkl rewards data can be incorporated.
    - Stored in MongoDB for use in ROE calculations.

- **Health factor**
    - Calculate and visualize liquidation risks based on chosen leverage.

- **Historical series**
    - **Powered by EulerScan**: hourly vault APY snapshots are fetched from EulerScan API.
    - Data is stored in MongoDB and used to render charts and tables.

- **Summary dashboard**
    - Tabular view with sortable columns.

- **Full containerization**
    - Run the entire stack (backend, frontend, MongoDB) with a single `docker-compose -f docker-compose-remote.yml -p defistat-stack up -d`.

---

## Running Locally

### Backend
```bash
cd backend
./gradlew bootRun
```

Backend runs on http://localhost:8080.

### Frontend
```bash
cd frontend
npm install
npm start
```
Frontend runs on http://localhost:4300.

### MongoDB
```bash
docker run -d \
--name mongo \
-p 27017:27017 \
-v mongo_data:/data/db \
mongo:7.0
```

This will start:
•	Backend at http://localhost:8080
•	Frontend at http://localhost:4300
•	MongoDB at localhost:27017


## Acknowledgment

This project is currently integrated only with the Euler protocol and relies on EulerScan as the primary data source for historical vault data.

Most of the backend and frontend code was generated with the help of ChatGPT, and then iteratively refined, debugged, and styled manually.


## Running with Docker Compose (full stack)

This project includes a ready-to-use `docker-compose.yml` that starts **MongoDB**, **Backend**, and **Frontend** together.

### Prerequisites
- [Docker](https://docs.docker.com/get-docker/) installed
- [Docker Compose](https://docs.docker.com/compose/) (v2 recommended)
- Create mongo-data folder in root folder

### Start the stack
```bash
docker-compose -f docker-compose-remote.yml -p defistat-stack up -d
```
This will start:
- MongoDB at `localhost:27017`  
- Backend at `http://localhost:8080`
- Frontend at `http://localhost:4300`