# ---- Build stage (Node) ----
FROM node:20-alpine AS build

WORKDIR /app

# Install deps with caching
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

# Copy sources and build
COPY . .
# If your build uses Angular CLI:
#   1) ensure @angular/cli is in devDependencies or globally installed here
#   2) by default, `npm run build` should call `ng build --configuration=production`
RUN npm run build

# Detect Angular dist path (adjust if your project name differs)
# Typical Angular 17/18 output is: dist/<project>/browser
# We'll copy later from /app/dist to nginx

# ---- Runtime stage (Nginx) ----
FROM nginx:alpine AS runtime

# Copy custom nginx config for SPA
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built app to nginx html
# Adjust the path below if your dist folder name differs:
# e.g., if output is dist/defi-frontend/browser
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]