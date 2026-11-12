# 0008. Serve torrents to other devices on the home network

Date: 2026-11-11
Status: Accepted

## Context

Torrents need bandwidth, disk and CPU. A Fire TV Stick or an old phone has little of each, while the desktop in the same house has plenty. Users asked for one device to do the torrenting and relaying for the others, like the official Stremio streaming server, but without its weaknesses: Stremio's server listens on every interface without authentication, so anything on the network (or the internet, behind a careless port forward) can use it.

Requirements:

- off by default; on only while the user wants it and the app runs;
- reachable from the home network only, never from public interfaces, mobile networks, VPNs or global IPv6;
- no unauthenticated access; pairing that works between a TV, a phone and a desktop;
- the player keeps receiving only loopback URLs, and the token never reaches it or any log;
- no open proxy: a paired device must not be able to use the server to reach the server's own network;
- the existing rules keep applying: "Only on Wi-Fi", cache size, no Referer, the redirect policy, `PublicResolver`.

## Decision

**What goes through the server.** Only torrents: an info hash, a file index and tracker URLs. Web and debrid sources keep playing through each device's own relay. Sending them through another device would save no bandwidth (the bytes come from the same CDN either way), would copy debrid keys to a second device, and would need the server to fetch arbitrary URLs for others, which is exactly the open-proxy risk to avoid.

**API.** The server (`core/crates/torrent/src/lan`) speaks HTTP/1.1 over TLS 1.3:

| Route | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/pair/start`, `POST /v1/pair/finish` | none | Pairing (below) |
| `GET /v1/hello` | token | Is this device still paired, and with whom |
| `DELETE /v1/me` | token | Unpair this device |
| `POST /v1/torrents` | token | Add a torrent: `{info_hash, file_idx, trackers}` → `{index}` |
| `GET /v1/torrents/<hash>` | token | Progress |
| `GET`/`HEAD /v1/torrents/<hash>/<index>` | token | Stream the file, with `Range` |
| `DELETE /v1/torrents/<hash>` | token | Done with it |

The token is sent as `Authorization: Bearer`. Each torrent records who holds it (this device's player, or paired devices by id) and stops when the last holder lets go. A device holds at most 3 torrents (playing a fourth releases its oldest) and streams at most 3 files at once; a torrent nobody streamed or asked about for 5 minutes is released (the device slept or crashed before saying so).

**Where it listens.** One listener per home-network address, never `0.0.0.0`: RFC 1918 IPv4 on interfaces that are up, not point-to-point, and not named like a modem, tunnel, VPN or virtual adapter (`rmnet`, `tun`, `wg`, `tailscale`, `docker`, `vEthernet`, and so on). Loopback, link-local, carrier-grade NAT (which Tailscale also uses), public IPv4 and all of IPv6 are excluded; IPv6 ULA could be added later. The interface list is read again every 15 s, so a new DHCP lease is picked up. Connections from public addresses are dropped, in case a router forwards the port. The port is 47630, or any free one if taken.

**Pairing.** The code is shown on the server and typed on the client. The device being granted access must be approved by whoever is at the device granting it, and typing six digits works on every device, including with a TV remote; reading a code off a TV and typing it into a phone is the common case (the TV usually is the client). The code is 6 random digits, valid for 3 minutes, for one device, for at most 10 attempts, with at most one attempt per second across all clients.

The code is never sent. Client and server run SPAKE2 (RustCrypto's `spake2`, Ed25519 group) with the code as the password, which gives an eavesdropper nothing to test guesses against offline and an active attacker one guess per exchange. With the shared key, the client sends an HMAC over the fingerprint of the TLS certificate it saw; the server accepts it only if that is its own certificate, then returns a 256-bit token with its own HMAC over its fingerprint, id and the token. The client stores the token only if that proof checks out, and pins the certificate's SHA-256 from then on. TLS uses the rustls stack already in the core (ring provider) with a self-signed ECDSA P-256 certificate made by `rcgen`.

The server stores only SHA-256 hashes of tokens (a 256-bit random token cannot be recovered from its hash). Paired devices are listed on the server with **Remove**. At most 16 devices are paired.

**Secrets at rest.** The server's state (id, certificate, private key, token hashes) and the client's pairing (address, server id, fingerprint, token) are handed by the core to the app through a UniFFI callback, and the app keeps them in `SecureStore` (Android Keystore, Windows DPAPI). The core keeps them in memory only. On macOS and Linux `SecureStore` does not persist yet (ADR 0006), so a server there gets a new identity on every launch and its clients have to pair again.

**Discovery.** mDNS/DNS-SD with `mdns-sd` (pure Rust, no async runtime, maintained): service `_stremionow._tcp`, instance name and host name from the server's random id, TXT `id`, `name`, `v=1`. Announcing and browsing are limited to the same home-network interfaces. Announcements are not trusted for anything; they only fill in an address. The address can also be typed. Android holds a Wi-Fi multicast lock while browsing and while serving, and asks for `ACCESS_LOCAL_NETWORK` on Android 17 before either.

**Client side.** Settings › Other devices › Use another device for torrents: choose a found device or type its address, then the code. From then on `playback_url` asks the paired server for torrents and registers a loopback relay route; the player reads `http://127.0.0.1:<port>/<secret>/remote/<token>`, and the relay forwards each request with the bearer token, `Range` and `Accept-Encoding: identity`. Progress is polled in the background, so the loading screen and its peers and speed keep working, and it now also says which device downloads the torrent.

The client's HTTP client pins the certificate, uses no system proxy (it would see the token), follows no redirects (the server never sends any), sends no Referer, and connects with a 3 s timeout so a sleeping server is noticed quickly. Server addresses must be RFC 1918 IPv4; names are not accepted, so `PublicResolver` and DNS do not come into it.

**Falling back.** If the server cannot be reached when a torrent starts, the client looks for it by id with mDNS for 2 s (it may have a new address; if so, the new address is saved) and otherwise downloads the torrent itself, and the loading screen says why: unreachable, no longer paired (removed there, or the server was reset, which shows up as a different certificate), or the server is on mobile data. If the server goes away during playback, the next player request continues the same file on this device, unless "Only on Wi-Fi" forbids it right now. A server that lost a torrent (it restarted, or the lease expired while the client slept) gets it again once.

**Existing rules.**

- *Only on Wi-Fi* applies on each device to the torrents it downloads itself. The app tells the core when it is on mobile data; a server on mobile data with the setting on refuses new torrents (`503 torrents_paused`), and a client never takes over a torrent over mobile data with the setting on. The source picker still blocks torrents on mobile data on the client, since the server is out of reach from there anyway and the torrent would fall back to mobile data.
- *Cache size* is the server's own; it trims its cache when a device releases a torrent.
- *No Referer, redirect policy, `PublicResolver`* stay as they are for web sources. The device-to-device client sends no Referer and follows no redirects at all.

**SSRF.** The server has no route that fetches a URL. What a paired device controls is limited to tracker URLs, and those already drop literal private addresses and local names; for torrents from paired devices, the server also resolves each tracker name and drops it unless every address is public, because an HTTP announce is a GET whose path the sender chooses, and a name pointing at the server's loopback or router must not get one. Two gaps remain and are accepted: librqbit resolves the name again when announcing, so a name that changes its answer in between (DNS rebinding) can still reach a private address with an announce request; and peers returned by public trackers or the DHT can be private addresses, which get a BitTorrent handshake, not an HTTP request. Both already exist for torrents the device plays itself, and a paired device is a device the user approved on the same network.

**Limits.** At most 48 connections at once, 8 per peer address; TLS handshake, request headers and request bodies each get 5 s, as on the loopback server; bodies are limited to 32 KiB; unauthorized requests get `401` and the connection is closed.

**Threat model.**

| Threat | Outcome |
| --- | --- |
| Another device on the network (guest, compromised IoT, a visitor's laptop) | Can reach the port and the pairing routes only. Without a code on screen, pairing answers `no_code`. While a code is shown it gets at most 10 guesses at a 6-digit code (about 1 in 100 000). It can make a shown code unusable by spending its attempts; the user then shows a new one. |
| Guest Wi-Fi | Usually isolated from the main network, so neither sees the other; nothing to do. If not isolated, it is the case above. |
| Eavesdropper on Wi-Fi | Sees TLS only. SPAKE2 gives it nothing to guess the code against. |
| ARP spoofing or a rogue access point (active attacker in the middle) | During pairing it would have to present its own certificate; the fingerprint the client proves would then not match the server's, and the server would not send a token. Running its own SPAKE2 with each side gives it one online guess per exchange. After pairing, the pin makes every connection fail rather than talk to it; the client reports the server as "no longer paired" and falls back to local downloads. |
| A fake server announcing itself | Cannot pair a client without knowing the code the user reads on the real server: the client checks the server's proof. |
| A stolen client device | Its token works until the user presses **Remove** on the server. The token sits in the Keystore- or DPAPI-encrypted store. |
| A paired device misbehaving | Can download and stream torrents through the server, within the limits above, and nothing else: no proxy, no file access, no private trackers. |
| The internet | Not reachable: no public listeners, public peers dropped, no UPnP. |

**Edge cases.**

| Case | Behavior |
| --- | --- |
| Server stops mid-film (turned off, app closed, crash) | The open response ends; the player's next request finds the server unreachable and the relay continues the same file on this device, from the same byte, unless "Only on Wi-Fi" forbids it. |
| Server's address changes (DHCP) | The server listens on the new address within 15 s and mDNS announces it. The client finds it by id at the next playback or Settings check and saves the new address. |
| Two servers on the network | Both are listed by name and address; the code ties the pairing to the one the user reads it from. A client uses one at a time. |
| Client paired to a server that was reset | The new certificate fails the pin; the client says the device no longer recognizes it, falls back to local downloads, and offers Stop using to pair again. |
| Device removed on the server | Its token gets `401`; same as above. Its torrents stop unless someone else uses them. |
| Many clients | Up to 16 paired, 48 connections, 8 per address, 3 torrents and 3 streams per device; the engine's 8 stream slots are shared with the server's own player. Two devices on different files of one torrent: see Consequences. |
| Wi-Fi-only client, Ethernet server | Each device applies its own setting to what it downloads. The client on Wi-Fi uses the server; on mobile data the source picker blocks torrents as before, since the server is unreachable and the fallback would use mobile data. |
| TV server goes to sleep | Clients cannot connect (3 s) and download themselves, saying why. Torrents it held are released by their leases when it wakes. |
| Client goes to sleep mid-film | Its torrents on the server are released after 5 minutes without requests; when it wakes, the relay adds the torrent back once. |

What TLS adds over plain HTTP on a home network: tokens and the content cannot be read or replayed by someone sniffing the Wi-Fi, an attacker in the middle cannot hijack a paired session or feed the client other bytes, and together with SPAKE2 the first contact is protected too, not just the ones after it.

## Consequences

- One weak device can play torrents through a stronger one, and the player and its logs still only see loopback URLs.
- Six new dependencies in the core: `tokio-rustls` (already in the tree through reqwest), `rcgen`, `ring` (already in the tree through rustls), `spake2` (with `curve25519-dalek`), `mdns-sd` and `if-addrs`. All are pure Rust or already built for Android.
- The server only serves while the app runs. On Android that means while it is open or recently in the background; a TV that goes to sleep stops serving and its clients fall back. A foreground service would keep a phone or TV serving with the screen off; it is not done yet.
- One torrent file per torrent at a time: two devices watching different episodes of the same season pack at once make the server switch files, and the device that started first gets an error and has to start again. Selecting several files per torrent would fix it.
- Servers on macOS and Linux lose their pairings on restart until their `SecureStore` persists.
- The Android emulator reaches its host's loopback as `10.0.2.2`, a private address, so a client in the emulator works unchanged. The host side needs a loopback listener, which production code never opens: `StreamServer::serve_loopback` exists only with the `dev-loopback` feature, which only `examples/serve.rs` enables.
