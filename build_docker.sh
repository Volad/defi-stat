cd ./backend
./gradlew clean build
docker build -t defi-stat-backend:latest .


cd ../frontend
npm install
docker build -t defi-frontend:latest .

cd ..
docker compose up --build -d