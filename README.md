# 🔐 Self-Destructing Secret Message Service

A secure, production-ready service for sharing encrypted messages that automatically self-destruct after being read. Built with Spring Boot, Redis, and NATS messaging.

[![Java](https://upload.wikimedia.org/wikipedia/en/thumb/3/30/Java_programming_language_logo.svg/656px-Java_programming_language_logo.svg.png)
[![Spring Boot](https://i.ytimg.com/vi/4cgpu9L2AE8/maxresdefault.jpg)
[![Redis](https://i.pinimg.com/736x/14/ab/5a/14ab5a2aee25fb25e673b10ffe6b7d84.jpg)
[![NATS](https://i.etsystatic.com/54730727/r/il/a98af8/6741722785/il_340x270.6741722785_qt3d.jpg)
[![Docker](https://pbs.twimg.com/media/EIc4Y5LWkAI1Pnd?format=jpg&name=medium)

## 📋 Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Usage Examples](#usage-examples)
- [Security](#security)
- [API Reference](#api-reference)
- [Development](#development)
- [Testing](#testing)
- [Deployment](#deployment)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)

## 🎯 Overview

This service provides a secure way to share sensitive information that should only be read once. Messages are encrypted with AES-256 encryption and automatically deleted after:
- Being successfully retrieved
- Three failed decryption attempts
- A configurable expiration time (default: 2 days)

**Perfect for**: Sharing passwords, API keys, sensitive notes, or any confidential information that should not persist.

## ✨ Features

### Core Features
- 🔒 **AES-256 Encryption**: Military-grade encryption for all messages
- 🔑 **Unique Access Keys**: Each message gets a unique, randomly generated encryption key
- 💣 **Self-Destruct**: Messages automatically delete after being read
- 🛡️ **Attempt Limiting**: Maximum 3 failed decryption attempts before deletion
- ⏰ **Auto-Expiration**: Configurable message lifetime (default: 2 days)
- 📊 **Redis Caching**: Fast, reliable message storage with built-in expiration

### Security Features
- 🔐 **NATS Authentication**: Secure message broker communication
- 🔑 **Redis Password Protection**: Protected data storage
- ✅ **Input Validation**: Size limits and content validation
- 📝 **Audit Logging**: Comprehensive logging for security monitoring
- 🚫 **Rate Limiting Ready**: Documentation and architecture for rate limiting

### Operational Features
- 🐳 **Docker Ready**: Complete containerization with Docker Compose
- 🔧 **Configurable**: Environment-based configuration
- 📈 **Production Ready**: Security hardened and optimized
- 🧪 **Well Tested**: Comprehensive integration tests
- 📚 **Documented**: Extensive documentation and examples

## 🏗️ Architecture

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Client    │────────▶│  NATS Broker │────────▶│  App Server │
│             │◀────────│              │◀────────│             │
└─────────────┘         └──────────────┘         └──────┬──────┘
                                                         │
                                                         ▼
                                                   ┌──────────┐
                                                   │  Redis   │
                                                   │  Cache   │
                                                   └──────────┘
```

### Components

1. **Spring Boot Application**: Main application server handling encryption/decryption
2. **NATS**: Message broker for asynchronous communication
3. **Redis**: In-memory data store for encrypted messages with expiration
4. **Docker**: Containerization for all services

### Data Flow

**Creating a Message:**
1. Client publishes message to `save.msg` subject via NATS
2. Application generates random AES-256 key
3. Message is encrypted with the key
4. Encrypted message stored in Redis with expiration
5. Message ID and encryption key returned to client

**Retrieving a Message:**
1. Client publishes message ID + key to `receive.msg` subject
2. Application retrieves encrypted message from Redis
3. Message is decrypted using provided key
4. Original message returned to client
5. Message deleted from Redis (self-destruct)

## 📦 Prerequisites

- **Docker** (version 20.10+)
- **Docker Compose** (version 2.0+)
- **Java 21** (for local development)
- **Gradle** (wrapper included)

## 🚀 Quick Start

### 1. Clone the Repository

```bash
git clone <repository-url>
cd secret_message
```

### 2. Configure Environment Variables

Create a `.env` file in the project root:

```bash
# Redis Configuration
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=your-secure-redis-password

# NATS Configuration
NATS_URL=nats://nats:4222
NATS_USER=your-nats-username
NATS_PASS=your-secure-nats-password

# Application Configuration
DEBUG=false
```

### 3. Build and Start Services

```bash
# Build Docker images
docker compose build

# Start all services
docker compose up -d

# View logs
docker compose logs -f app
```

### 4. Verify Services

```bash
# Check service status
docker compose ps

# Test NATS connection
docker compose exec nats-box nats server check --server nats:4222 --user your-nats-username --password your-secure-nats-password
```

## ⚙️ Configuration

### Application Properties

Configure via environment variables or `application.properties`:

| Variable | Default                 | Description |
|----------|-------------------------|-------------|
| `SPRING_REDIS_HOST` | `localhost`             | Redis server hostname |
| `SPRING_REDIS_PORT` | `6379`                  | Redis server port |
| `SPRING_REDIS_PASSWORD` | -                       | Redis authentication password |
| `NATS_URL` | `nats://localhost:4222` | NATS server URL |
| `NATS_USER` | -                       | NATS authentication username |
| `NATS_PASS` | -                       | NATS authentication password |
| `app.auto-delete-days` | `2`                     | Message expiration in days |
| `app.max-tries` | `3`                     | Maximum decryption attempts |
| `app.max-message-size` | `1048576`               | Max message size (1MB) |

### Security Configuration

**Production Checklist:**
- ✅ Set strong passwords for Redis and NATS
- ✅ Disable debug port (remove `- "5005:5005"` from compose.yaml)
- ✅ Use TLS/SSL for external connections
- ✅ Implement rate limiting (see [docs/RATE_LIMITING.md](docs/RATE_LIMITING.md))
- ✅ Set up monitoring and alerts
- ✅ Regular security updates

## 💡 Usage Examples

### Using NATS Box (Recommended for Testing)

#### Save a Secret Message

```bash
# Access NATS Box container
docker compose exec nats-box /bin/sh

# Save a message
nats request save.msg "This is my secret message" --server nats:4222 --user your-nats-username --password your-secure-nats-password
```

**Response:**
```json
{
  "messageId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "aeskey": "dGhpc2lzYXNlY3VyZWtleWVuY29kZWRpbmJhc2U2NA=="
}
```

#### Retrieve a Secret Message

```bash
# Retrieve the message using the ID and key
nats request receive.msg '{"messageId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","aeskey":"dGhpc2lzYXNlY3VyZWtleWVuY29kZWRpbmJhc2U2NA=="}' --server nats:4222 --user your-nats-username --password your-secure-nats-password
```

**Response:**
```
"This is my secret message"
```

### Using NATS CLI

#### Install NATS CLI

```bash
# macOS
brew install nats-io/nats-tools/nats

# Linux
curl -sf https://binaries.nats.dev/nats-io/natscli/nats@latest | sh

# Windows
scoop install nats
```

#### Connect and Send Messages

```bash
# Set credentials
export NATS_URL=nats://localhost:4222
export NATS_USER=natsuser
export NATS_PASS=natspassword

# Save a message
nats req save.msg "My confidential data" --user $NATS_USER --password $NATS_PASS

# Retrieve a message
nats req receive.msg '{"messageId":"YOUR_MESSAGE_ID","aeskey":"YOUR_AES_KEY"}' --user $NATS_USER --password $NATS_PASS
```

### Using Programming Languages

#### Python Example

```python
import asyncio
import json
from nats.aio.client import Client as NATS

async def save_secret_message(message: str):
    nc = NATS()
    await nc.connect("nats://localhost:4222", user="natsuser", password="natspassword")
    
    response = await nc.request("save.msg", message.encode(), timeout=5)
    result = json.loads(response.data.decode())
    
    await nc.close()
    return result

async def retrieve_secret_message(message_id: str, aes_key: str):
    nc = NATS()
    await nc.connect("nats://localhost:4222", user="natsuser", password="natspassword")
    
    payload = json.dumps({"messageId": message_id, "aeskey": aes_key})
    response = await nc.request("receive.msg", payload.encode(), timeout=5)
    message = json.loads(response.data.decode())
    
    await nc.close()
    return message

# Usage
async def main():
    # Save message
    result = await save_secret_message("My secret password: P@ssw0rd123")
    print(f"Message ID: {result['messageId']}")
    print(f"AES Key: {result['aeskey']}")
    
    # Retrieve message
    message = await retrieve_secret_message(result['messageId'], result['aeskey'])
    print(f"Retrieved: {message}")

asyncio.run(main())
```

#### JavaScript/Node.js Example

```javascript
const { connect, StringCodec } = require('nats');

async function saveSecretMessage(message) {
    const nc = await connect({ 
        servers: 'nats://localhost:4222',
        user: 'natsuser',
        pass: 'natspassword'
    });
    
    const sc = StringCodec();
    const response = await nc.request('save.msg', sc.encode(message), { timeout: 5000 });
    const result = JSON.parse(sc.decode(response.data));
    
    await nc.close();
    return result;
}

async function retrieveSecretMessage(messageId, aesKey) {
    const nc = await connect({ 
        servers: 'nats://localhost:4222',
        user: 'natsuser',
        pass: 'natspassword'
    });
    
    const sc = StringCodec();
    const payload = JSON.stringify({ messageId, aeskey: aesKey });
    const response = await nc.request('receive.msg', sc.encode(payload), { timeout: 5000 });
    const message = JSON.parse(sc.decode(response.data));
    
    await nc.close();
    return message;
}

// Usage
(async () => {
    // Save message
    const result = await saveSecretMessage('My secret API key: sk-1234567890');
    console.log(`Message ID: ${result.messageId}`);
    console.log(`AES Key: ${result.aeskey}`);
    
    // Retrieve message
    const message = await retrieveSecretMessage(result.messageId, result.aeskey);
    console.log(`Retrieved: ${message}`);
})();
```

## 🔒 Security

### Encryption

- **Algorithm**: AES-256 in CBC mode with PKCS5 padding
- **Key Generation**: Cryptographically secure random key generation
- **IV**: Unique random initialization vector for each message
- **Key Storage**: Keys are never stored, only provided to the client

### Authentication

- **NATS**: Username/password authentication (configurable)
- **Redis**: Password protection (configurable)
- **Future**: OAuth2/JWT support planned

### Best Practices

1. **Use Strong Passwords**: Set strong, unique passwords for Redis and NATS
2. **Enable TLS**: Use TLS for all network communications in production
3. **Implement Rate Limiting**: See [docs/RATE_LIMITING.md](docs/RATE_LIMITING.md)
4. **Monitor Logs**: Set up centralized logging and monitoring
5. **Regular Updates**: Keep dependencies and base images updated
6. **Network Isolation**: Use Docker networks to isolate services
7. **Principle of Least Privilege**: Run containers with minimal permissions

### Known Limitations

- No built-in rate limiting (documentation provided for implementation)
- No TLS/SSL by default (must be configured externally)
- No user authentication (NATS-level auth only)

## 📚 API Reference

### NATS Subjects

#### `save.msg`
Save a new secret message.

**Request:**
- Type: String
- Content: The secret message to encrypt and store
- Max Size: 1 MB (configurable)

**Response:**
```json
{
  "messageId": "uuid-v4",
  "aeskey": "base64-encoded-key"
}
```

**Errors:**
```json
{
  "error": "Message size exceeds maximum allowed: 1048576 bytes"
}
```

#### `receive.msg`
Retrieve and decrypt a secret message.

**Request:**
```json
{
  "messageId": "uuid-v4",
  "aeskey": "base64-encoded-key"
}
```

**Response:**
- Type: String
- Content: The decrypted secret message

**Errors:**
- `"Maximum attempts reached, the message has been deleted."`
- `{"error": "Failed to retrieve secret message: <reason>"}`

## 🛠️ Development

### Local Development Setup

```bash
# Clone repository
git clone <repository-url>
cd secret_message

# Start dependencies (Redis & NATS)
docker compose up redis nats -d

# Run application locally
./gradlew bootRun

# Or with specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests SecretMessageServiceTest

# Run with coverage
./gradlew test jacocoTestReport
```

### Build Docker Image

```bash
# Build production image
docker build -t secret-message-app:latest .

# Build with specific target
docker build --target test -t secret-message-app:test .
```

### Code Style

This project follows standard Java/Spring Boot conventions:
- Use SLF4J for logging
- Follow JavaDoc conventions
- Use Lombok for boilerplate reduction
- Implement proper exception handling

## 🐳 Deployment

### Docker Compose Deployment

```bash
# Production deployment
docker compose -f compose.yaml up -d

# View logs
docker compose logs -f

# Stop services
docker compose down
```

### Kubernetes Deployment

See [README.Docker.md](README.Docker.md) for Kubernetes deployment instructions.

### Health Checks

```bash
# Application health
curl http://localhost:8080/actuator/health

# NATS monitoring
curl http://localhost:8222/varz

# Redis
docker compose exec redis redis-cli ping
```

## 🔍 Troubleshooting

### Common Issues

#### 1. Connection Refused to Redis

**Problem**: `Connection refused` error when connecting to Redis.

**Solution:**
```bash
# Check Redis is running
docker compose ps redis

# Check Redis logs
docker compose logs redis

# Verify Redis password
docker compose exec redis redis-cli -a yourpassword ping
```

#### 2. NATS Authentication Failed

**Problem**: `Authorization violation` when publishing to NATS.

**Solution:**
- Verify NATS_USER and NATS_PASS are set correctly
- Check NATS server logs: `docker compose logs nats`
- Ensure credentials match in both client and server

#### 3. Message Not Found

**Problem**: Message returns null or "not found" error.

**Possible Causes:**
- Message already retrieved (self-destructed)
- Message expired (>2 days old)
- Wrong message ID
- Maximum attempts exceeded

**Solution:**
```bash
# Check if message exists in Redis
docker compose exec redis redis-cli -a yourpassword KEYS "messages:*"

# Check message TTL
docker compose exec redis redis-cli -a yourpassword TTL "messages:your-message-id"
```

#### 4. Message Size Too Large

**Problem**: `Message size exceeds maximum allowed` error.

**Solution:**
- Current limit: 1 MB (configurable)
- Increase limit: Set `app.max-message-size` environment variable
- Consider splitting large messages

#### 5. Docker Container Exits Immediately

**Problem**: App container exits right after starting.

**Solution:**
```bash
# Check logs for errors
docker compose logs app

# Common issues:
# - Redis/NATS not available
# - Configuration errors
# - Port conflicts
```

### Debug Mode

To enable debug logging:

```bash
# Set in .env file
DEBUG=true

# Uncomment debug port in compose.yaml
# - "5005:5005"

# Restart services
docker compose restart app
```

### Getting Help

1. Check logs: `docker compose logs -f`
2. Review configuration: `docker compose config`
3. Check service status: `docker compose ps`
4. Review documentation: See [docs/](docs/) directory
5. Open an issue on GitHub

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Development Workflow

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Make your changes
4. Run tests: `./gradlew test`
5. Commit your changes: `git commit -m 'Add amazing feature'`
6. Push to the branch: `git push origin feature/amazing-feature`
7. Open a Pull Request

### Areas for Contribution

- [ ] Implement rate limiting
- [ ] Add TLS/SSL support
- [ ] Web UI for message creation/retrieval
- [ ] REST API endpoints
- [ ] User authentication system
- [ ] Message categories/tags
- [ ] Notification system
- [ ] Multi-language support

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 📞 Contact

**Felipe M.**
- Email: felipemarcelo554@gmail.com

## 🙏 Acknowledgments

- Spring Boot Team
- NATS.io Community
- Redis Team
- Docker Community

---

**⚠️ Security Notice**: This service is designed for secure message sharing but should not be used as the sole security mechanism for highly sensitive data. Always follow security best practices and conduct proper security audits before production use.

**🔧 Production Ready**: With proper configuration and security hardening, this service is production-ready. See [docs/RATE_LIMITING.md](docs/RATE_LIMITING.md) for additional security recommendations.
