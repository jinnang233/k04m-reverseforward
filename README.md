# K04M Reverse Forward

K04M Reverse Forward is a Minecraft client mod that provides encrypted, authenticated reverse TCP port forwarding between two verified players through `KryptSocket` control and tunnel streams in Krypt04Mcg 0.23.0. It supports both Fabric and NeoForge and is released under the Unlicense.

> [!WARNING]
> This codebase was **generated with AI assistance**. Review the implementation carefully, especially the cryptography, key storage, networking behavior, and dependency configuration, before using it in any real environment.
>
> If possible, please run it in an **ISOLATED** environment, such as a virtual machine, to avoid potential security risks from build artifacts, such as the possibility that the maintainer’s computer has been infected with malware.
>
> If you discover any code security issues, or any copyright or licensing concerns, please report them in Issues. Thank you for your understanding.

> [!WARNING]
> Krypt04Mcg is **EXPERIMENTAL** software and has not undergone independent security auditing. The protocol, implementation, and cryptographic design **may contain vulnerabilities or design flaws**. Do not rely on this mod to protect highly sensitive, important, or production-critical data. If you require mature and battle-tested end-to-end encrypted communication, consider using established tools such as Signal or SimpleX instead.

## How It Works

Suppose Alice wants to expose a connection on her local `127.0.0.1:25570` to a service listening on Bob's local `127.0.0.1:8080`:

1. Alice registers a route and invites Bob.
2. Bob explicitly accepts the invitation and may choose the local target port. The authorization is bound to Alice's verified player identity and an unguessable route UUID.
3. Alice's client starts listening on `127.0.0.1:25570`.
4. Each TCP connection arriving at that port opens a `KryptSocket` to Bob. After Bob verifies the player identity and route UUID, his client connects to its own `127.0.0.1:8080` and begins forwarding bytes in both directions.

Both the listening endpoint and target endpoint are restricted to loopback addresses, so services are not accidentally exposed to the LAN or public internet. Each route supports at most eight concurrent connections. Routes and accepted authorizations are stored in `config/k04m-reverse-forward/routes.dat`; pending invitations are retained for only two minutes and are never written to disk.

The route storage directory and file are restricted to the local owner's access (POSIX
`700`/`600`, or an owner-only ACL). Symbolic links in the storage path are rejected,
including dangling links. Saves use a unique private temporary file and an atomic
replacement where supported; the `routes.dat` format remains compatible. If you previously
linked this directory or file, migrate its contents into a regular local directory.

## Requirements

- Minecraft Java 26.3 and Java 25.
- Fabric Loader 0.19.5 with Fabric API 0.161.0+26.3, or NeoForge 26.3.0.16-beta.
- Both players must install this mod and Krypt04Mcg 0.23.0 or later for their respective mod loader.
- Both players must enable `enableDataApi` in Krypt04Mcg and import/trust each other's public keys as described in the Krypt04Mcg documentation.
- The server-side relay must support the raw encrypted channel protocol: `krypt04mcg_stream:control` and `krypt04mcg_stream:data/0` through the configured channel count. Legacy relays are incompatible. Concurrent control and tunnel streams share Krypt04Mcg's `apiChannelCount` pool (default 16).

This project references `libs/Krypt04Mcg.jar` as a `compileOnly` dependency. GitHub Actions downloads the latest Fabric release JAR from Krypt04Mcg and renames it automatically. The build output neither bundles nor modifies Krypt04Mcg. Krypt04Mcg must be installed separately at runtime; NeoForge users must install its NeoForge build rather than placing the Fabric JAR in a NeoForge client.

## Commands

All commands are client-side commands:

```text
/k04mrf register <name> <listenPort> <targetPort>
/k04mrf invite <name> <player>
/k04mrf invitations
/k04mrf accept <invitationId> [targetPort]
/k04mrf deny <invitationId>
/k04mrf list
/k04mrf stop <name>
/k04mrf start <name>
/k04mrf remove <name>
/k04mrf revoke <routeId>
/k04mrf help
```

For `invitationId` and `routeId`, you may use the eight-character prefixes displayed by `/k04mrf invitations` or `/k04mrf list`. The command is rejected if the prefix is ambiguous. The port supplied during `register` is proposed to the invited player; they may accept it as-is or override it by supplying `targetPort` to `accept`.

Pending invitations expire after two minutes, including when accepting or denying them. At most
64 invitations are retained globally and four per player; excess offers are ignored without
evicting existing offers. A pending invitation ID keeps its original sender, route and proposed
port. Duplicate packets cannot replace those details or extend the deadline; changed offers
must use a new invitation ID.

### Example

Alice runs:

```text
/k04mrf register web 25570 8080
/k04mrf invite web Bob
```

Bob sees a message containing a short invitation ID and then runs the following command, using the displayed ID:

```text
/k04mrf accept a1b2c3d4 8080
```

After Alice receives the acceptance confirmation, her client listens on `127.0.0.1:25570`. TCP traffic sent to that address is handled by the application listening on Bob's `127.0.0.1:8080`.

`stop` closes Alice's local entry point and its active connections while preserving the authorization; `start` opens it again. Accepting a pending invitation preserves this stopped state. `remove` deletes the initiator's route and tells the peer to revoke its authorization. Bob can also revoke an inbound authorization directly with `/k04mrf revoke <routeId>`, closing its active connections immediately.

Both endpoints explicitly use IPv4 `127.0.0.1`, even when Java prefers IPv6. Use `/k04mrf list` to check whether a route is actually `listening`. If its port is occupied, startup reports failure; free the port and retry `/k04mrf start <name>`. An accepted, enabled route waits for a server connection before it can listen.

## Building

The build requires Java 25 and network access to the Minecraft, Fabric, and NeoForge Maven repositories. Place the latest Krypt04Mcg Fabric release JAR at `libs/Krypt04Mcg.jar` before building locally. The `Build` GitHub Actions workflow performs this download automatically.

The repository includes a manually triggered `Generate Gradle Wrapper` workflow that can generate and commit the Gradle wrapper files. Once those files are present, build both variants with:

```powershell
# Fabric
.\gradlew.bat build

# NeoForge
.\gradlew.bat -p neoforge build
```

The resulting artifacts are written to:

- `build/libs/k04m-reverse-forward-fabric-1.2.0.jar`
- `neoforge/build/libs/k04m-reverse-forward-neoforge-1.2.0.jar`

## Security and Operational Limits

- Confidentiality, identity authentication, ordered socket streams, and authenticated EOF are provided by Krypt04Mcg. It remains experimental and should not be used for sensitive or production traffic.
- Inbound authorization is persisted only after the invitee explicitly accepts it. When a socket arrives, the mod checks the verified sender reported by Krypt04Mcg and the route UUID again.
- The mod never listens on non-loopback addresses, automatically executes files, or launches target services.
- `KryptSocket` reads, writes and flushes run on virtual I/O workers. Stream creation and control-message processing still use the Minecraft client thread. Socket callbacks hand work to application workers immediately.
- Disconnecting from the server terminates all listeners and active Krypt04Mcg streams. Enabled routes with accepted authorization resume listening after reconnection.
- Krypt04Mcg streams carry up to 16 KiB per encrypted record and buffer at most 1 MiB per direction. Forwarding workers pace writes using available queue capacity. Flush does not wait for delivery; normal completion waits for queued output and both authenticated EOFs before cleanup. Cancellation closes input to abort blocked reads.

## Protocol Overview

The `k04m_reverse_forward:control` channel uses one bounded, versioned binary message per stream, terminated by authenticated EOF, for invitations, acceptances, denials, and revocations. Sending only confirms local queueing; there are no delivery receipts or automatic retries. If an invitation or acceptance is lost, invite again. The data channel is `k04m_reverse_forward:tunnel`. Every new stream begins with the `K04M` magic value, a protocol version, and a 128-bit route UUID. After the receiver verifies the player identity and authorization and successfully connects to the local target, it returns status byte `0`; only then does application-data forwarding begin.

The protocol operates exclusively inside the authenticated and encrypted channels provided by the Krypt04Mcg API. It does not implement its own cryptography or bypass Krypt04Mcg's trust checks.

Tunnel protocol version 2 frames each payload with a four-byte big-endian length (1–16384 bytes). A zero length signals EOF in that direction, allowing TCP half-close request/response exchanges to finish before the connection closes. Both endpoints must use version 2; version 1 streams are rejected. The control and route-storage formats are unchanged.

`gradle check` includes manager lifecycle regressions against API doubles, TCP half-close tests, and IPv4 loopback tests with IPv6 preference enabled.
