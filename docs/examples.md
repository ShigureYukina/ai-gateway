# Quick Examples

Set the base URL once:

```bash
export BASE_URL=http://localhost:8081
```

## 1. Health Check

```bash
curl -f "$BASE_URL/healthz"
```

## 2. List Public Models

```bash
curl -f "$BASE_URL/v1/models"
```

## 3. Call `/v1/chat/completions` With The Demo API Key

```bash
curl -X POST "$BASE_URL/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer demo-client-key' \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "system", "content": "You are a concise assistant."},
      {"role": "user", "content": "Reply with hello from the gateway."}
    ],
    "stream": false
  }'
```

## 4. Log In As Admin

```bash
curl -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
```

## 5. Check The Current User After Login

Replace `YOUR_ACCESS_TOKEN` with the token returned by the login response.

```bash
curl -f "$BASE_URL/auth/me" \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'
```

## 6. Trigger A Manual Model Sync As Admin

Replace `YOUR_ACCESS_TOKEN` with an admin access token.

```bash
curl -X POST "$BASE_URL/admin/sync/models-dev" \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'
```

## 7. Package The Bootstrap Jar

```bash
./mvnw -pl bootstrap -am package

# Skip frontend build when you only need the backend jar
./mvnw -pl bootstrap -am package -DskipFrontendBuild=true
```
