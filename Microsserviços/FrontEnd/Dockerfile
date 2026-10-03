FROM node:24-alpine AS build-stage

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
ARG VITE_GATEWAY_URL=http://localhost:8085
ENV VITE_GATEWAY_URL=$VITE_GATEWAY_URL
RUN npm run build

EXPOSE 3000

CMD ["sh", "-c", "npm run preview -- --host 0.0.0.0 --port ${PORT:-3000}"]
