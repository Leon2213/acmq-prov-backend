# Docker Setup Guide

## Snabbstart

### 1. Förberedelser

```bash
# Klona repot
git clone <repo-url>
cd acmq-prov-backend

# Kopiera och konfigurera miljövariabler
cp .env.example .env
```

Redigera `.env` och fyll i dina värden:
```
GIT_USERNAME=ditt-användarnamn
BITBUCKET_TOKEN=din-token
```

### 2. Starta endast backend

```bash
# Bygg och starta
docker-compose up -d --build

# Följ loggarna
docker-compose logs -f backend
```

Backend är nu tillgänglig på http://localhost:8080

### 3. Verifiera att det fungerar

```bash
# Health check
curl http://localhost:8080/actuator/health

# Ska returnera: {"status":"UP"}
```

---

## Full Setup med Frontend

### Förberedelser

Se till att du har båda repos:
```
/path/to/
├── acmq-prov-backend/    # Detta repo
└── acmq-prov-frontend/   # Frontend-repot
```

### Konfigurera Frontend

1. Kopiera `Dockerfile.frontend.example` till frontend-repot:
```bash
cp Dockerfile.frontend.example ../acmq-prov-frontend/Dockerfile
```

2. Avkommentera frontend-tjänsten i `docker-compose.yml`

3. Starta allt:
```bash
docker-compose up -d --build
```

---

## Kommandon

| Kommando | Beskrivning |
|----------|-------------|
| `docker-compose up -d` | Starta i bakgrunden |
| `docker-compose up -d --build` | Bygg om och starta |
| `docker-compose down` | Stoppa alla containers |
| `docker-compose logs -f` | Visa loggar (live) |
| `docker-compose ps` | Visa körande containers |
| `docker-compose restart backend` | Starta om backend |

---

## Felsökning

### Container startar inte

```bash
# Kolla loggar
docker-compose logs backend

# Kolla att porten inte används
lsof -i :8080
```

### Git-operationer misslyckas

1. Verifiera att `BITBUCKET_TOKEN` är korrekt satt i `.env`
2. Kontrollera att token har rätt behörigheter (repo read/write)

### Bygg misslyckas

```bash
# Rensa Docker cache och bygg om
docker-compose down
docker system prune -f
docker-compose up -d --build
```

---

## Produktionsmiljö

För produktion, överväg:

1. **Använd secrets istället för .env**
```yaml
services:
  backend:
    secrets:
      - bitbucket_token
secrets:
  bitbucket_token:
    file: ./secrets/bitbucket_token.txt
```

2. **Lägg till reverse proxy (nginx/traefik)**

3. **Aktivera HTTPS**

4. **Begränsa resurser**
```yaml
services:
  backend:
    deploy:
      resources:
        limits:
          cpus: '1'
          memory: 512M
```

---

## Arkitektur

```
┌─────────────────┐     ┌─────────────────┐
│    Frontend     │────▶│     Backend     │
│   (port 3000)   │     │   (port 8080)   │
└─────────────────┘     └────────┬────────┘
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
            ┌───────────────┐       ┌───────────────┐
            │   Hieradata   │       │    Puppet     │
            │     Repo      │       │     Repo      │
            └───────────────┘       └───────────────┘
```
