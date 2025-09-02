cd ./backend
./gradlew clean build
docker build -t defi-stat-backend:latest .


cd ../frontend
npm install
docker build -t defi-frontend:latest .

cd ..
docker compose up --build -d

docker tag defi-stat-backend:latest volad/defi-stat-backend:latest
docker push volad/defi-stat-backend:latest

docker tag defi-frontend:latest volad/defi-stat-frontend:latest
docker push volad/defi-stat-frontend:latest