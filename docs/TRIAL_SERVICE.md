# No-key access and a possible hosted trial

v0.6.0 provides free, offline catalog search and follow-up questions. This path needs neither a Qwen key nor a cloud service. Photo recognition still uses the key entered on the phone. A hosted, no-key vision trial is **not available** in this release, and no paid service has been provisioned.

A hosted trial would require a server-owned Qwen key, deployment, and an agreed spending budget. The phone must never contain a shared upstream key. Before opening it to users, the service should enforce an atomic total budget, per-user/device quotas and concurrency limits, reject arbitrary models/upstream addresses, and use authenticated short-lived access tokens. A photo job needs an idempotency key so hedged requests do not spend several trial credits; the upstream request count must also have a hard limit. When a quota or budget is exhausted, the service must stop making upstream calls and offer the offline lookup instead.

The next deployment decision is who pays for the trial, its total budget, its expiry date, and the number of users/scans it should admit. There is intentionally no nonfunctional trial button in the app.
