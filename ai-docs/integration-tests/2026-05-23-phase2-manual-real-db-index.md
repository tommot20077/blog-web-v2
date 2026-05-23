# Phase 2 Manual Real DB Verification Index

Date: 2026-05-23
Environment: dev profile, real dev database

## Purpose

These checklists verify the same journeys defined by the E2E red-test design against
the real running frontend/backend and real dev database.

## Required Running Services

- Backend: `http://localhost:9010`
- Frontend: `http://127.0.0.1:5500` or `http://localhost:5500`
- PostgreSQL: dev `blog_v2_db`
- Redis: dev Redis
- Elasticsearch: dev Elasticsearch
- MinIO: dev MinIO
- RabbitMQ: dev RabbitMQ

## Checklists

- Handoff runbook: `2026-05-23-p0-red-e2e-handoff.md`
- Auth lifecycle: `2026-05-23-phase2-auth-checklist.md`
- Author/Admin review: `2026-05-23-phase2-author-review-checklist.md`
- Reader interaction: `2026-05-23-phase2-reader-interaction-checklist.md`
- System evidence: `2026-05-23-phase2-system-evidence-checklist.md`

## Rule

Ask Yuan before destructive cleanup in the real dev database.
