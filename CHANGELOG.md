# Changelog

## 1.3.2 — 2026-10-08

### Security and reliability

- Limit pending invitations to 64 globally and four per player, ignoring player-name case. Invitation contents and their two-minute monotonic deadline remain bound to the first offer; duplicates cannot replace the sender/port or renew the deadline.
- Validate expiry when accepting or denying, without depending on an earlier tick. Unrelated and repeated remote revocations no longer cause synchronous route-store writes.
- Protect `routes.dat` with an owner-only directory/file and reject symbolic links, including dangling links and linked parents. Saves use unique private temporary files instead of following a predictable `routes.dat.tmp` path, then replace the file atomically where supported.
- Bound pending control/tunnel reads to 64 globally and 16 per player across both channels. Control messages and tunnel authorization headers have a fixed 30-second deadline; partial progress cannot renew it.
- Disconnect cancels pending reads and queued control actions. Expired headers cannot connect to a local TCP target; ownership passes to the authorization connection group before pending tracking ends.

### Upgrade notes

- Fabric and NeoForge versions match. Control packets, tunnel protocol v2, the route-store format, and the Krypt04Mcg minimum requirement of 0.23.0 remain unchanged.
- Install the matching loader build on both peers. Krypt04Mcg 0.27.5 and relay plugin 1.8.2 are recommended for their separate security fixes.
- Linked route-store directories/files must be migrated into regular local storage. POSIX permissions become `700` for the directory and `600` for the file; ACL platforms restrict access to the owner.
- Slow or excessive setup requests may be closed and need to be retried. Established tunnels retain the existing eight-connections-per-route limit and normal half-close/revocation lifecycle; they have no new 30-second total duration limit.

The changes do not claim complete denial-of-service protection. Windows ACL behavior and actual Minecraft networking were not exercised in this audit round.

Details: [control path](docs/security/2026-10-08-follow-up.md), [local storage](docs/security/2026-10-08-local-storage.md), [pending streams](docs/security/2026-10-08-pending-streams.md).
