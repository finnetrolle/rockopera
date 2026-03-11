# Agent Messenger — чат для агентов

## Задача

Нужен self-hosted чат-сервер (docker-compose), куда агенты пишут:
- какие задачи берут
- что выполнили
- статусы и ошибки
- общаются между собой при необходимости

## Рекомендация: Mattermost

Лучший вариант для агентного использования благодаря простому REST API и лёгкому деплою.

### Docker Compose

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: mattermost
      POSTGRES_USER: mmuser
      POSTGRES_PASSWORD: mmpassword
    volumes:
      - pgdata:/var/lib/postgresql/data

  mattermost:
    image: mattermost/mattermost-team-edition:latest
    environment:
      MM_SQLSETTINGS_DRIVERNAME: postgres
      MM_SQLSETTINGS_DATASOURCE: postgres://mmuser:mmpassword@postgres:5432/mattermost?sslmode=disable
      MM_SERVICESETTINGS_SITEURL: http://localhost:8065
    ports:
      - "8065:8065"
    volumes:
      - mmdata:/mattermost/data
    depends_on:
      - postgres

volumes:
  pgdata:
  mmdata:
```

### Интеграция агентов

**Вариант 1: Incoming Webhooks (односторонняя лента событий)**

```bash
curl -X POST http://localhost:8065/hooks/HOOK_ID \
  -H 'Content-Type: application/json' \
  -d '{"channel": "agents", "text": "Agent-1: взял задачу ROC-42"}'
```

Простейший способ — агенты только пишут, не читают чужие сообщения.

**Вариант 2: Bot API (двусторонняя коммуникация)**

```bash
# Отправка сообщения
curl -H "Authorization: Bearer BOT_TOKEN" \
  http://localhost:8065/api/v4/posts \
  -d '{"channel_id": "...", "message": "Agent-1: задача ROC-42 выполнена"}'

# Чтение сообщений канала
curl -H "Authorization: Bearer BOT_TOKEN" \
  http://localhost:8065/api/v4/channels/CHANNEL_ID/posts
```

Для полноценного общения между агентами — бот-аккаунты + WebSocket API для получения событий в реальном времени.

### Структура каналов (пример)

- `#agent-feed` — общая лента событий всех агентов
- `#agent-errors` — ошибки и проблемы
- `#task-ROC-42` (или треды) — обсуждение конкретной задачи

## Альтернативы

| Сервер | Плюсы | Минусы |
|--------|-------|--------|
| **Rocket.Chat** | REST + WebSocket API, bot SDK (JS/TS) | MongoDB, тяжелее |
| **Zulip** | Topics внутри streams — идеально для задач | Сложнее настройка docker |
| **Matrix (Synapse + Element)** | Децентрализация, федерация | Тяжёлый, сложная настройка |
