# Security Policy

## Reporting a vulnerability

Please report security issues privately via GitHub Security Advisories
("Report a vulnerability" on the repository's Security tab) rather than opening a
public issue. We aim to acknowledge reports within a few business days.

## Security model

This SDK uploads to the *adopting app's own* Firebase project. The security
posture depends on the security rules you deploy (templates in `firebase/`):

- **Per-uid isolation.** All Storage objects live under `users/{uid}/...` and the
  Firestore dedup index / file records under `users/{uid}/...`. The bundled rules
  reject any read or write where `request.auth.uid != uid`, so one user can never
  reach another user's data. Deduplication is deliberately **per-uid** — there is
  no cross-user index that could leak file existence or download URLs.
- **Authentication required.** `enqueue` requires a signed-in Firebase user; the
  storage path is scoped to that uid.
- **No secrets in the SDK.** The host app owns Firebase initialisation and
  credentials; the SDK ships no keys.

## Adopter responsibilities

- Deploy the provided Storage rules (and Firestore rules if you enable dedup or
  `SyncPolicy`). Uploads to an unprotected bucket are your risk, not the SDK's.
- Consider Firebase App Check (Play Integrity) to reject requests from non-genuine
  app builds — install your provider in `Application.onCreate()`.
- Apply your own per-user rate/quota limits if abuse is a concern; the SDK does
  not throttle how much a single account may upload.
