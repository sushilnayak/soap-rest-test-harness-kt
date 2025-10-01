# Test Harness for bulk testing REST & SOAP Services

A production-grade, non-blocking Kotlin Spring Boot microservice built with WebFlux, R2DBC, and JWT authentication. This service demonstrates modern reactive programming patterns with functional error handling using Arrow-kt.

## Tech Stack

- **Language**: Kotlin (JVM 21)
- **Framework**: Spring Boot 3.x with WebFlux (non-blocking)
- **Build**: Gradle Kotlin DSL
- **Database**: PostgreSQL with R2DBC (reactive)
- **Security**: Stateless JWT authentication with Spring Security
- **Concurrency**: Kotlin Coroutines throughout
- **Error Handling**: Arrow-kt Either for functional error handling
- **Testing**: JUnit 5, Testcontainers, WebTestClient
- **Code Quality**: ktlint, detekt, Jacoco
- **Documentation**: OpenAPI 3 with Swagger UI
- **Containerization**: Docker with multi-stage builds

## Features

- User registration and JWT-based authentication
- Project CRUD operations with REST/SOAP metadata storage
- Dynamic request execution to downstream services with authentication
- Bulk execution using Excel templates with format conversion
- Test generation (Gatling performance tests and Cucumber features)
- Reactive security with WebFlux SecurityFilterChain
- Functional error handling with Arrow Either
- Comprehensive integration tests with Testcontainers
- Production-ready Docker setup with PostgreSQL

## Architecture

The application follows a clean hexagonal architecture:

```
src/main/kotlin/com/example/app/
├── App.kt                           # Main application class
├── config/                          # Configuration classes
├── security/                        # JWT service and filters
├── user/                           # User domain (auth)
│   ├── domain/User.kt              # User entity
│   ├── repo/UserRepository.kt      # R2DBC repository
│   ├── app/UserService.kt          # Business logic with Either
│   └── api/UserController.kt       # REST endpoints
├── project/                        # Project domain
│   ├── domain/Project.kt           # Project entity with JSONB
│   ├── repo/ProjectRepository.kt   # R2DBC repository
│   ├── app/ProjectService.kt       # Business logic with Either
│   └── api/ProjectController.kt    # Secured REST endpoints
└── common/                         # Shared utilities
    ├── errors/DomainError.kt       # Domain error types
    ├── http/ApiResponse.kt         # Response envelope
    └── mapping/ErrorHandlers.kt    # Error to HTTP mapping
```

## Quick Start

### Prerequisites

- Java 21
- Docker & Docker Compose
- (Optional) Make

### 1. Start PostgreSQL

```bash
docker-compose up postgres -d
```

### 2. Set Environment Variables

```bash
export JWT_SECRET="your-secret-key-that-is-at-least-256-bits-long"
```

### 3. Build and Run

```bash
# Using Gradle
./gradlew bootRun

# Or using Make
make run
```

The application will start on `http://localhost:8080`

## API Usage

### 1. User Registration

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "john_doe",
    "password": "securePassword123"
  }'
```

### 2. User Login (Get JWT Token)

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "john_doe",
    "password": "securePassword123"
  }'
```

Response:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 900,
    "tokenType": "Bearer"
  }
}
```

### 3. Create Project (JWT Required)

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "name": "My REST API",
    "type": "REST",
    "meta": {
      "baseUrl": "https://api.example.com",
      "endpoints": [
        {"path": "/users", "method": "GET"},
        {"path": "/users", "method": "POST"}
      ],
      "headers": {
        "Content-Type": "application/json",
        "Authorization": "Bearer token"
      }
    }
  }'
```

### 4. Create SOAP Project

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "name": "Legacy SOAP Service",
    "type": "SOAP",
    "meta": {
      "wsdlUrl": "https://service.example.com/service.wsdl",
      "namespace": "http://example.com/service",
      "operations": ["getUserById", "createUser", "updateUser"]
    }
  }'
```

### 5. Get Projects

```bash
# Get all user's projects
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/projects

# Get specific project
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/projects/{project-id}
```

### 6. Execute Dynamic Request

```bash
curl -X POST http://localhost:8080/api/execution/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "targetUrl": "https://api.example.com/users",
    "httpMethod": "POST",
    "requestType": "REST",
    "headers": {
      "Content-Type": "application/json"
    },
    "requestBody": {
      "name": "John Doe",
      "email": "john@example.com"
    },
    "authConfig": {
      "required": true,
      "tokenUrl": "https://auth.example.com/token",
      "clientId": "your-client-id",
      "clientSecret": "your-client-secret",
      "audience": "https://api.example.com"
    }
  }'
```

### 7. Generate Excel Template

```bash
# Generate Excel template for a project
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/projects/{project-id}/excel-template \
  --output template.xlsx
```

### 8. Bulk Execution with Excel

```bash
# Upload Excel file for bulk execution
curl -X POST http://localhost:8080/api/bulk/execute \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -F "request={\"projectId\":\"project-uuid\",\"executeImmediately\":true,\"conversionMode\":\"NONE\",\"cacheAuthToken\":true,\"respectCellColors\":true,\"appendResults\":true};type=application/json" \
  -F "file=@data.xlsx"

# Check bulk execution status
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/bulk/status/{execution-id}
```

### 9. Generate Test Files

```bash
# Generate Gatling performance test
curl -X POST http://localhost:8080/api/testing/gatling \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "projectId": "project-uuid",
    "testName": "API Performance Test",
    "userCount": 50,
    "rampUpDuration": 60,
    "testDuration": 300
  }'

# Generate Cucumber feature file
curl -X POST http://localhost:8080/api/testing/cucumber \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "projectId": "project-uuid",
    "featureName": "API Functional Tests",
    "includeValidation": true,
    "includeErrorScenarios": true
  }'
```

## Development Commands

```bash
# Build application
make build
# or
./gradlew build

# Run tests
make test
# or
./gradlew test

# Format code
make format
# or
./gradlew ktlintFormat

# Run linting
make lint
# or
./gradlew detekt ktlintCheck

# Run all checks
make check
# or
./gradlew check

# Clean build artifacts
make clean
# or
./gradlew clean
```

## Docker Deployment

### Build and run with Docker Compose

```bash
# Start all services (PostgreSQL + App)
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop services
docker-compose down
```

### Build Docker image only

```bash
docker build -t kotlin-spring-app .
```

## Testing

The project includes comprehensive tests:

- **Unit Tests**: JWT service logic
- **Integration Tests**: Full HTTP flow with Testcontainers PostgreSQL
- **Security Tests**: Authentication and authorization flows

```bash
# Run all tests
./gradlew test

# Run tests with coverage
./gradlew test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html
```

## Configuration

### Profiles

- **dev** (default): Local development with detailed logging
- **test**: Test environment with Testcontainers
- **prod**: Production environment with minimal logging

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `JWT_SECRET` | JWT signing secret (min 256 bits) | `local-dev-secret-key...` |
| `JWT_EXPIRE_MINUTES` | Token expiration in minutes | `15` |
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `appdb` |
| `DB_USER` | Database user | `app` |
| `DB_PASSWORD` | Database password | `app` |

## API Documentation

Swagger UI is available at: `http://localhost:8080/swagger-ui/index.html`

OpenAPI spec: `http://localhost:8080/v3/api-docs`

## Security Considerations

- JWT tokens are short-lived (15 minutes by default)
- Passwords are hashed with BCrypt
- All secrets should be provided via environment variables in production
- HTTPS should be terminated at the load balancer/reverse proxy level
- Consider implementing refresh tokens for longer sessions

## Key Features

### Non-blocking Architecture
- WebFlux for reactive HTTP handling
- R2DBC for non-blocking database access
- Kotlin coroutines throughout the application
- No blocking JDBC or Servlet dependencies

### Functional Error Handling
- Arrow-kt Either types for error handling
- Domain errors mapped to appropriate HTTP status codes
- Consistent error response format

### Security
- Stateless JWT authentication
- Reactive Spring Security configuration
- Password hashing with BCrypt
- Role-based access control ready

### Data Storage
- PostgreSQL with JSONB for flexible project metadata
- Proper database constraints and indexes
- R2DBC repositories with Kotlin coroutines support

### Advanced Bulk Processing
- **Token Caching**: Fetch authentication tokens once per bulk execution for efficiency
- **Smart Excel Processing**:
    - Respect cell colors to exclude values from requests
    - Skip rows based on "Skip Case(Y/N)" column
    - Intelligent header naming from JSON/XML paths
- **Template Generation**: Auto-generate Excel templates from project request/response schemas
- **Result Appending**: Add execution results back to Excel with "ACTUAL_" prefixed headers
- **Format Conversion**: Seamless SOAP ↔ REST conversion during bulk execution

### Excel Template Features
- **Default Columns**: Test Case ID, Skip Case(Y/N), Description
- **Smart Headers**: Compressed JSONPath/XPath naming with collision detection
- **Response Validation**: EXPECTED_ prefixed columns for response validation
- **Color Coding**: Use cell colors to exclude specific values from requests
- **Bulk Results**: ACTUAL_ prefixed columns show execution results

## Performance & Monitoring

- Micrometer metrics available at `/actuator/metrics`
- Health check endpoint at `/actuator/health`
- Application metrics and custom business metrics
- Docker health checks configured

## 🚀 **New Features Added**

### **Persistent Job Queue System**
- **Resilient Execution**: Jobs survive application restarts and crashes
- **Automatic Retries**: Configurable retry logic with exponential backoff
- **Job Monitoring**: Real-time status tracking and progress updates
- **Cancellation Support**: Cancel running jobs through API endpoints

### **Enhanced Logging & Observability**
- **Structured Logging**: JSON-formatted logs with MDC context
- **Trace IDs**: Unique execution IDs for tracking requests across components
- **Request Logging**: Comprehensive HTTP request/response logging
- **Error Details**: Sanitized request logging for failed operations

### 1. **Dynamic Request Execution** (`/api/execution/execute`)
- Execute HTTP requests to any REST/SOAP endpoint
- Support for various authentication methods (OAuth2, Basic Auth, API Keys)
- Automatic format conversion between JSON and XML
- Configurable timeouts and retry mechanisms

### 2. **Bulk Execution System** (`/api/bulk/execute`)
- Upload Excel files with test data for bulk API execution
- Intelligent Excel parsing with support for colored cells and skip flags
- Token caching for efficient authentication across multiple requests
- Result appending back to Excel with detailed execution outcomes

### 3. **Test Generation Capabilities**
- **Gatling Performance Tests**: Generate load testing scripts from project configurations
- **Cucumber Feature Files**: Create BDD test scenarios with validation steps
- Customizable test parameters (user count, duration, scenarios)

### 4. **Enhanced Project Templates**
- Added `requestTemplate` and `responseTemplate` fields to projects
- Support for parameterized templates using placeholder variables
- Excel template generation capabilities for bulk operations

## 🔧 **Job Management System**

### **Job Lifecycle**
1. **Creation**: Jobs are persisted in database with unique execution IDs
2. **Execution**: Asynchronous processing with progress tracking
3. **Monitoring**: Real-time status updates and error reporting
4. **Retry Logic**: Automatic retries with exponential backoff
5. **Completion**: Final status update with detailed results

### **Job API Endpoints**
- `GET /api/jobs` - List all jobs for authenticated user
- `GET /api/jobs/{executionId}` - Get specific job status
- `POST /api/jobs/{executionId}/cancel` - Cancel running job

## 🔧 **Technical Improvements**

### **Database Schema Updates**
- New `bulk_executions` table for tracking bulk operation status
- New `job_executions` table for persistent job queue
- Enhanced `projects` table with template fields
- Proper indexing for performance optimization

### **Security & Reliability**
- OAuth2 token acquisition for downstream service authentication
- Persistent job execution survives application restarts
- Comprehensive error tracking and retry mechanisms
- Configurable timeouts and retry mechanisms
- Request sanitization for secure logging

## 📊 **Usage Examples**

### **Job Management**
```bash
# Get all jobs
curl -H "Authorization: Bearer TOKEN" \
  http://localhost:8080/api/jobs

# Get specific job status
curl -H "Authorization: Bearer TOKEN" \
  http://localhost:8080/api/jobs/exec_abc123def456

# Cancel running job
curl -X POST -H "Authorization: Bearer TOKEN" \
  http://localhost:8080/api/jobs/exec_abc123def456/cancel
```

### **Dynamic Execution with Authentication**
```bash
curl -X POST http://localhost:8080/api/execution/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "targetUrl": "https://api.example.com/users",
    "httpMethod": "POST",
    "requestType": "REST",
    "authConfig": {
      "required": true,
      "tokenUrl": "https://auth.example.com/oauth/token",
      "clientId": "your-client-id",
      "clientSecret": "your-client-secret",
      "audience": "https://api.example.com"
    },
    "requestBody": {"name": "John", "email": "john@example.com"}
  }'
```

### **Bulk Execution with Excel**
```bash
# Upload Excel file for bulk execution
curl -X POST http://localhost:8080/api/bulk/execute \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -F "request={\"projectId\":\"uuid\",\"executeImmediately\":true,\"cacheAuthToken\":true};type=application/json" \
  -F "file=@test-data.xlsx"
```

### **Test Generation**
```bash
# Generate Gatling performance test
curl -X POST http://localhost:8080/api/testing/gatling \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "projectId": "project-uuid",
    "testName": "Load Test",
    "userCount": 100,
    "rampUpDuration": 60,
    "testDuration": 300
  }'
```

## 🎯 **Value Additions**

6. **Production Resilience**: Persistent job queue ensures no data loss during restarts
7. **Comprehensive Monitoring**: Structured logging with trace IDs for debugging
8. **Error Recovery**: Automatic retry mechanisms with detailed error reporting
1. **API Testing Platform**: Complete solution for testing REST/SOAP services
2. **Bulk Operations**: Excel-based bulk testing with intelligent data processing
3. **Authentication Integration**: Support for OAuth2, Basic Auth, and API key authentication
4. **Test Automation**: Generate performance and functional tests from API configurations
5. **Format Flexibility**: Seamless conversion between XML and JSON formats
9. **Enterprise Ready**: OAuth2 integration, retry logic, and comprehensive monitoring

The enhanced microservice now serves as a production-grade API testing and automation platform with persistent job management, comprehensive observability, and enterprise-level reliability features.

## Contributing

1. Follow Kotlin coding standards (ktlint configuration included)
2. Maintain test coverage above 70%
3. Use Arrow Either for error handling in services
4. All new endpoints should be properly secured
5. Add integration tests for new features

## License

This project is licensed under the MIT License.