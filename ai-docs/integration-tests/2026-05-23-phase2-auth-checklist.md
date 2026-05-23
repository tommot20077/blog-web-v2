# Phase 2 Auth Manual Checklist

## Setup

- Open frontend: `http://localhost:5500`
- Confirm backend readiness: `GET http://localhost:9010/actuator/health/readiness`
- Use a unique email: `manual-auth-{timestamp}@test.local`

## Normal Flow

- [ ] Register with unique email, username, nickname, and valid password.
- [ ] Confirm UI shows verification-required state.
- [ ] Retrieve verification token/code from dev DB only after Yuan confirms this is acceptable.
- [ ] Verify email through the UI route or API route.
- [ ] Login through UI.
- [ ] Refresh the page and confirm login state remains.
- [ ] Logout and confirm protected routes redirect to login.

## Abnormal Flow

- [ ] Register same email again; expect stable duplicate-email error.
- [ ] Login with wrong password; expect stable auth error.
- [ ] Call refresh without cookie; expect `401`.

## Evidence

UI:

- Register page success state.
- Login page error state.
- Authenticated navigation state.

API:

- `POST /api/v1/auth/register`
- `GET /api/v1/auth/verify-email`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

SQL:

```sql
SELECT id, email, username, role, status, email_verified
FROM users
WHERE email = '<manual email>';

SELECT id, user_id, type, consumed_at, expires_at
FROM verification_tokens
WHERE user_id = (SELECT id FROM users WHERE email = '<manual email>');
```

Logs:

- Backend log around registration, verification, login, refresh, logout.
- Browser devtools network entries for `Set-Cookie` and refresh/logout responses.

## Cleanup

Ask Yuan before deleting the manual user or verification tokens from dev DB.
