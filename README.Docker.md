# Docker Deployment Guide

This guide covers building, running, and deploying the Secret Message Service using Docker and Docker Compose.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Docker Compose Setup](#docker-compose-setup)
- [Building the Application](#building-the-application)
- [Running the Application](#running-the-application)
- [Configuration](#configuration)
- [Production Deployment](#production-deployment)
- [Cloud Deployment](#cloud-deployment)
- [Kubernetes Deployment](#kubernetes-deployment)
- [Monitoring and Logging](#monitoring-and-logging)
- [Troubleshooting](#troubleshooting)

## Prerequisites

- Docker Engine 20.10 or higher
- Docker Compose V2 (2.0+)
- Minimum 2GB RAM available for containers
- Minimum 1GB disk space

### Installation

#### Docker Desktop (Mac/Windows)
Download from: https://www.docker.com/products/docker-desktop

#### Docker Engine (Linux)
```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# Install Docker Compose
sudo apt-get update
sudo apt-get install docker-compose-plugin
```

Verify installation:
```bash
docker --version
docker compose version
```

## Docker Compose Setup

### Architecture Overview

The Docker Compose setup includes four services:

1. **app**: Spring Boot application (Port 8080)
2. **redis**: Redis cache (Port 6379)
3. **nats**: NATS message broker (Ports 4222, 8222)
4. **nats-box**: NATS utility container

### Network Configuration

All services run on the `nats` bridge network:
```yaml
networks:
  nats:
    driver: bridge
```

## Building the Application

### Basic Build

```bash
# Build all services
docker compose build

# Build specific service
docker compose build app

# Build with no cache (force rebuild)
docker compose build --no-cache

# Build for specific platform
docker compose build --platform linux/amd64 app
```

### Multi-Stage Build

The Dockerfile uses a multi-stage build process:

1. **Build Stage**: Compiles Java application with Gradle
2. **Test Stage**: Runs unit and integration tests
3. **Final Stage**: Creates production-ready minimal image

```dockerfile
# Build stage (includes source and build tools)
FROM eclipse-temurin:21-jdk-jammy AS build

# Test stage (runs tests)
FROM build AS test

# Final stage (minimal production image)
FROM eclipse-temurin:21-jre-jammy AS final
```

### Build Arguments

```bash
# Build specific stage
docker compose build --target test

# Custom UID for non-root user
docker build --build-arg UID=10001 -t secret-message:latest .
```

## Running the Application

### Development Mode

```bash
# Start all services in foreground
docker compose up

# Start all services in background (detached)
docker compose up -d

# Start specific services
docker compose up -d redis nats

# View logs
docker compose logs -f
docker compose logs -f app

# Follow logs for specific service
docker compose logs -f app --tail=100
```

### Production Mode

```bash
# Start with production configuration
docker compose --env-file .env.production up -d

# Scale application instances
docker compose up -d --scale app=3

# View service status
docker compose ps

# Check resource usage
docker compose stats
```

### Stopping Services

```bash
# Stop services (containers remain)
docker compose stop

# Stop and remove containers
docker compose down

# Stop, remove containers and volumes
docker compose down -v

# Stop, remove everything including images
docker compose down --rmi all -v
```

## Configuration

### Environment Variables

Create a `.env` file in the project root:

```bash
# Redis Configuration
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=your-secure-redis-password-change-this

# NATS Configuration
NATS_URL=nats://nats:4222
NATS_USER=your-nats-username
NATS_PASS=your-secure-nats-password-change-this

# Application Configuration
DEBUG=false

# Security (Production Only)
# Uncomment for debug mode
# DEBUG=true
```

### Security Configuration

#### 1. Redis Authentication

Update `compose.yaml`:
```yaml
redis:
  environment:
    REDIS_PASSWORD: ${REDIS_PASSWORD:-secure-password}
  command: redis-server --requirepass ${REDIS_PASSWORD:-secure-password}
```

Configure app to use password:
```yaml
app:
  environment:
    SPRING_REDIS_PASSWORD: ${REDIS_PASSWORD:-secure-password}
```

#### 2. NATS Authentication

Update `compose.yaml`:
```yaml
nats:
  environment:
    NATS_USER: ${NATS_USER:-natsuser}
    NATS_PASS: ${NATS_PASS:-natspassword}
  command: ["-DV", "--http_port", "8222", "--trace", "--user", "$NATS_USER", "--pass", "$NATS_PASS"]
```

#### 3. Debug Mode

**Development** (debug enabled):
```yaml
# Uncomment in compose.yaml
ports:
  - "8080:8080"
  - "5005:5005"  # Debug port

environment:
  DEBUG: "true"
```

**Production** (debug disabled):
```yaml
ports:
  - "8080:8080"
  # Debug port removed

environment:
  DEBUG: "false"
```

### Volume Mounts

#### Persistent Redis Data
```yaml
redis:
  volumes:
    - redis-data:/data

volumes:
  redis-data:
    driver: local
```

#### Application Logs
```yaml
app:
  volumes:
    - ./logs:/app/logs
```

## Production Deployment

### Pre-Deployment Checklist

- [ ] Change all default passwords
- [ ] Disable debug mode (`DEBUG=false`)
- [ ] Remove or comment debug port mapping
- [ ] Enable TLS/SSL (external reverse proxy)
- [ ] Configure resource limits
- [ ] Set up monitoring and logging
- [ ] Configure backups for Redis
- [ ] Implement rate limiting
- [ ] Review security settings
- [ ] Test disaster recovery

### Resource Limits

Add resource constraints to `compose.yaml`:

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '2.0'
          memory: 2G
        reservations:
          cpus: '1.0'
          memory: 1G
    restart: unless-stopped

  redis:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
        reservations:
          cpus: '0.5'
          memory: 512M
    restart: unless-stopped

  nats:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
        reservations:
          cpus: '0.5'
          memory: 256M
    restart: unless-stopped
```

### Health Checks

Add health checks to `compose.yaml`:

```yaml
app:
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/status"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 40s

redis:
  healthcheck:
    test: ["CMD", "redis-cli", "--raw", "incr", "ping"]
    interval: 30s
    timeout: 10s
    retries: 3

nats:
  healthcheck:
    test: ["CMD", "wget", "--spider", "-q", "http://localhost:8222/healthz"]
    interval: 30s
    timeout: 10s
    retries: 3
```

### Reverse Proxy (Nginx)

Example Nginx configuration:

```nginx
upstream secret_message {
    server app:8080;
}

server {
    listen 80;
    server_name yourdomain.com;
    
    # Redirect to HTTPS
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name yourdomain.com;

    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    
    # Security headers
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    
    location / {
        proxy_pass http://secret_message;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## Cloud Deployment

### AWS ECS

1. **Build and push image to ECR**:
```bash
# Authenticate to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com

# Build for AMD64 (ECS uses AMD64)
docker buildx build --platform linux/amd64 -t <account-id>.dkr.ecr.us-east-1.amazonaws.com/secret-message:latest .

# Push to ECR
docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/secret-message:latest
```

2. **Create ECS Task Definition**: See `deploy/aws-ecs-task-definition.json`

3. **Deploy to ECS**:
```bash
aws ecs create-service --cluster production --service-name secret-message --task-definition secret-message:1 --desired-count 2
```

### Google Cloud Run

```bash
# Set project
gcloud config set project PROJECT_ID

# Build and push to GCR
docker build --platform linux/amd64 -t gcr.io/PROJECT_ID/secret-message:latest .
docker push gcr.io/PROJECT_ID/secret-message:latest

# Deploy to Cloud Run
gcloud run deploy secret-message \
  --image gcr.io/PROJECT_ID/secret-message:latest \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --set-env-vars="REDIS_HOST=<redis-ip>,NATS_URL=nats://<nats-ip>:4222"
```

### Azure Container Instances

```bash
# Login to Azure
az login

# Create resource group
az group create --name secret-message-rg --location eastus

# Create container registry
az acr create --resource-group secret-message-rg --name secretmessageacr --sku Basic

# Build and push
az acr build --registry secretmessageacr --image secret-message:latest .

# Deploy container
az container create \
  --resource-group secret-message-rg \
  --name secret-message-app \
  --image secretmessageacr.azurecr.io/secret-message:latest \
  --cpu 2 --memory 4 \
  --ports 8080 \
  --environment-variables \
    REDIS_HOST=<redis-host> \
    NATS_URL=nats://<nats-host>:4222
```

### DigitalOcean App Platform

```bash
# Install doctl
brew install doctl

# Authenticate
doctl auth init

# Create app
doctl apps create --spec deploy/digitalocean-app.yaml
```

## Kubernetes Deployment

### Prerequisites

- Kubernetes cluster (v1.24+)
- kubectl configured
- Helm 3 (optional)

### Deployment Files

Create `k8s/` directory with the following files:

#### 1. Namespace
```yaml
# k8s/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: secret-message
```

#### 2. ConfigMap
```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: secret-message-config
  namespace: secret-message
data:
  REDIS_HOST: "redis-service"
  REDIS_PORT: "6379"
  NATS_URL: "nats://nats-service:4222"
```

#### 3. Secrets
```yaml
# k8s/secrets.yaml
apiVersion: v1
kind: Secret
metadata:
  name: secret-message-secrets
  namespace: secret-message
type: Opaque
data:
  redis-password: <base64-encoded-password>
  nats-user: <base64-encoded-username>
  nats-password: <base64-encoded-password>
```

#### 4. Deployment
```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: secret-message-app
  namespace: secret-message
spec:
  replicas: 3
  selector:
    matchLabels:
      app: secret-message
  template:
    metadata:
      labels:
        app: secret-message
    spec:
      containers:
      - name: app
        image: secret-message:latest
        ports:
        - containerPort: 8080
        env:
        - name: REDIS_HOST
          valueFrom:
            configMapKeyRef:
              name: secret-message-config
              key: REDIS_HOST
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: secret-message-secrets
              key: redis-password
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
```

#### 5. Service
```yaml
# k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: secret-message-service
  namespace: secret-message
spec:
  type: LoadBalancer
  selector:
    app: secret-message
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
```

### Deploy to Kubernetes

```bash
# Apply all configurations
kubectl apply -f k8s/

# Check deployment status
kubectl get pods -n secret-message
kubectl get services -n secret-message

# View logs
kubectl logs -f deployment/secret-message-app -n secret-message

# Scale deployment
kubectl scale deployment secret-message-app --replicas=5 -n secret-message
```

## Monitoring and Logging

### Docker Compose Logging

```bash
# View all logs
docker compose logs

# Follow logs
docker compose logs -f

# Last N lines
docker compose logs --tail=100 app

# Since timestamp
docker compose logs --since="2024-01-01T00:00:00"

# Export logs
docker compose logs > logs/app-$(date +%Y%m%d).log
```

### Centralized Logging (ELK Stack)

Add to `compose.yaml`:
```yaml
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.9.0
    environment:
      - discovery.type=single-node
    ports:
      - "9200:9200"
      
  logstash:
    image: docker.elastic.co/logstash/logstash:8.9.0
    volumes:
      - ./logstash.conf:/usr/share/logstash/pipeline/logstash.conf
      
  kibana:
    image: docker.elastic.co/kibana/kibana:8.9.0
    ports:
      - "5601:5601"
```

### Prometheus Monitoring

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"
      
  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
```

## Troubleshooting

### Common Issues

#### 1. Port Already in Use
```bash
# Find process using port
lsof -i :8080

# Kill process
kill -9 <PID>

# Or change port in compose.yaml
ports:
  - "8081:8080"
```

#### 2. Build Failures
```bash
# Clear Docker cache
docker system prune -a

# Rebuild without cache
docker compose build --no-cache

# Check disk space
docker system df
```

#### 3. Container Exits Immediately
```bash
# Check logs
docker compose logs app

# Run container interactively
docker compose run app /bin/sh

# Check health status
docker compose ps
```

#### 4. Network Issues
```bash
# Inspect network
docker network inspect secret_message_nats

# Test connectivity
docker compose exec app ping redis
docker compose exec app ping nats

# Recreate network
docker compose down
docker network prune
docker compose up -d
```

#### 5. Redis Connection Issues
```bash
# Test Redis connection
docker compose exec redis redis-cli -a yourpassword ping

# Check Redis logs
docker compose logs redis

# Verify password
docker compose exec redis redis-cli -a wrongpassword ping
```

### Debug Commands

```bash
# Execute command in running container
docker compose exec app /bin/sh

# View container processes
docker compose top

# Inspect container
docker compose exec app env

# Check resource usage
docker compose stats

# Validate compose file
docker compose config

# View service dependencies
docker compose config --services
```

### Performance Tuning

#### JVM Options
```yaml
app:
  environment:
    JAVA_OPTS: "-Xms512m -Xmx2g -XX:+UseG1GC"
```

#### Redis Performance
```yaml
redis:
  command: >
    redis-server
    --maxmemory 1gb
    --maxmemory-policy allkeys-lru
    --save ""
```

#### NATS Performance
```yaml
nats:
  command: >
    nats-server
    --max_payload 8388608
    --max_connections 10000
```

## Best Practices

1. **Use specific image tags** instead of `latest`
2. **Implement health checks** for all services
3. **Set resource limits** to prevent resource exhaustion
4. **Use secrets management** for sensitive data
5. **Enable logging** with log rotation
6. **Regular backups** of Redis data
7. **Monitor resource usage** and set up alerts
8. **Use multi-stage builds** to minimize image size
9. **Run containers as non-root user**
10. **Keep base images updated** for security patches

## Additional Resources

- [Docker Documentation](https://docs.docker.com/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [Spring Boot Docker Guide](https://spring.io/guides/gs/spring-boot-docker/)
- [Production Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)

---

For application-specific documentation, see the main [README.md](README.md).
