# 0008. Serve torrents to other devices on the home network

Date: 2026-11-11
Status: Accepted; revised after a security review (home network binding, UDP-only trackers, pairing and connection limits)

## Context

Torrents need bandwidth, disk and CPU. A Fire TV Stick or an old phone has little of each, while the desktop in the same house has plenty. Users asked for one device to do the torrenting and relaying for the others, like the official Stremio streaming server, but without its weaknesses: Stremio's server listens on every interface without authentication, so anything on the network (or the internet, behind a careless port forward) can use it.

Requirements:

- off by default; on only while the user wants it and the app runs;
- reachable from the user's home network only: not from another private network the device joins (a café, a hotel, an office), public interfaces, mobile networks, VPNs or global IPv6;
- no unauthenticated access; pairing that works between a TV, a phone and a desktop;
- the player keeps receiving only loopback URLs, and the token never reaches it or any log;
- no open proxy: a paired device must not be able to use the server to reach the server's own network;
- the existing rules keep applying: "Only on Wi-Fi", cache size, no Referer, the redirect policy, `PublicResolver`.

## Decision

**What goes through the server.** Only torrents: an info hash, a file index and UDP tracker URLs. Web and debrid sources keep playing through each device's own relay. Sending them through another device would save no bandwidth (the bytes come from the same CDN either way), would copy debrid keys to a second device, and would need the server to fetch arbitrary URLs for others, which is exactly the open-proxy risk to avoid.

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

The token is sent as `Authorization: Bearer`. Each torrent records who holds it (this device's player, or paired devices by id) and stops when the last holder lets go. A device holds at most 3 torrents (playing a fourth releases its oldest) and streams at most 3 files at once; a torrent nobody streamed for 5 minutes is released (the device slept or crashed before saying so). Progress requests do not extend that lease, so a client that forgot a torrent but keeps polling does not hold it. A torrent that is gone answers `404`, which a client's relay answers by adding it again once; one another device switched to a different file answers `409`, which it does not. Errors carry a code only (`metadata_timeout`, `no_video_file`, `torrent_failed`, ...); details stay in the server's log.

**Where it listens.** Only on the network where the user turned serving on. An RFC 1918 address alone does not make a network the user's: cafés, hotels and offices hand them out too. Each candidate interface is identified by its default gateway, by the gateway's MAC address where the system exposes it (Windows through `GetAdaptersAddresses` and the neighbor cache, Linux through `/proc/net/route` and `/proc/net/arp`, macOS through `route` and `arp`). Android hides both the ARP table and the Wi-Fi name from apps without location access, so there the app reports the gateway, DHCP server, DNS servers, search domains and IPv4 prefix from the system's link properties instead. That is weaker: two networks set up alike, such as two routers on factory settings, look the same. The identities are stored hashed in the server's state. Turning serving on makes the current network the home network; restoring it at launch does not. On any other network the server closes its listeners, withdraws its announcement and reports itself paused, and resumes when the device is home again. Only the user makes another network the home network ("Serve on this network", confirmed in a dialog).

Candidates are physical Ethernet and Wi-Fi interfaces that are up and have an RFC 1918 IPv4 address and a default gateway. On Windows the adapter type must be Ethernet (6) or Wi-Fi (71) and neither its description nor its name may name a virtual, VPN or tethering adapter: Wintun and WireGuard (type 53), TAP, AnyConnect, GlobalProtect, Fortinet, Hyper-V, VirtualBox, VMware, Remote NDIS, Bluetooth and Wi-Fi Direct adapters are out. On Linux the interface must have a device behind it in sysfs and not be a bridge. Everywhere, names of modems, tunnels, VPNs, container and VM bridges, hotspots, Wi-Fi Direct groups and USB tethering are out (`rmnet`, `tun`, `wg`, `tailscale`, `docker`, `podman`, `lxdbr`, `br`, `p2p-wlan0-0`, `ap0`, `usb0`, and so on). Loopback, link-local, carrier-grade NAT (which Tailscale also uses), public IPv4 and all of IPv6 are excluded; IPv6 ULA could be added later. One listener per address, never `0.0.0.0`. The interfaces are read again every 15 s and whenever the app reports a network change. Connections are accepted from RFC 1918 addresses only, in case a router forwards the port; a router that forwards it and also rewrites the source to its own address would hide the internet behind a home address, and pairing and tokens are what stand then. The port is 47630, or any free one if taken.

The Windows "Private network" profile is not required. Tying serving to the network where the user turned it on is the same decision made in the app, by someone who just chose to serve, and does not depend on how they once answered Windows' prompt, which many dismiss either way.

**Pairing.** The code is shown on the server and typed on the client. The device being granted access must be approved by whoever is at the device granting it, and typing six digits works on every device, including with a TV remote; reading a code off a TV and typing it into a phone is the common case (the TV usually is the client). The code is 6 random digits, valid for 3 minutes, for one device, for at most 10 attempts in all and 3 from any one address. Each address may start one exchange per second, whether or not a code is showing, so polling for a code to appear is as slow as guessing; across addresses, one exchange per 100 ms bounds the work. Several addresses can still spend a code's attempts (a local denial of service); the server's screen then says another device tried the code, and the user shows a new one. Approving each new device on the server as well was considered and left out: the code typed on the client already proves someone read it on the server's screen, and a second step would not stop attempts from being spent.

The code is never sent. Client and server run SPAKE2 (RustCrypto's `spake2`, Ed25519 group) with the code as the password, which gives an eavesdropper nothing to test guesses against offline and an active attacker one guess per exchange. With the shared key, the client sends an HMAC over the fingerprint of the TLS certificate it saw; the server accepts it only if that is its own certificate, then returns a 256-bit token with its own HMAC over its fingerprint, id and the token. The client stores the token only if that proof checks out, and pins the certificate's SHA-256 from then on. TLS uses the rustls stack already in the core (ring provider) with a self-signed ECDSA P-256 certificate made by `rcgen`.

The server stores only SHA-256 hashes of tokens (a 256-bit random token cannot be recovered from its hash). Paired devices are listed on the server with **Remove**. At most 16 devices are paired.

**Secrets at rest.** The server's state (id, certificate, private key, token hashes) and the client's pairing (address, server id, fingerprint, token) are handed by the core to the app through a UniFFI callback, and the app keeps them in `SecureStore` (Android Keystore, Windows DPAPI). The core keeps them in memory only. On macOS and Linux `SecureStore` does not persist yet (ADR 0006), so a server there gets a new identity on every launch and its clients have to pair again.

**Discovery.** mDNS/DNS-SD with `mdns-sd` (pure Rust, no async runtime, maintained): service `_stremionow._tcp`. An announcement carries nothing stable that would let anyone on a network track the device: the instance and host names are random each time serving starts, and instead of the server's id it carries a random nonce `n` and a tag `t`, an HMAC of the nonce keyed by the server's id, which only paired clients (they learned the id when pairing) can recognize. The announced `name` is the user's choice, by default "StremioNow (TV)", "(phone)", "(computer)" rather than the device's own name, which is often its owner's. Announcing happens only on the interfaces being served; browsing only on home-network interfaces. Discovery keeps every address a server announces, and pairing and rediscovery try each in turn. Announcements are not trusted for anything; they only fill in addresses. The address can also be typed. Android holds a Wi-Fi multicast lock while browsing and while serving, and asks for `ACCESS_LOCAL_NETWORK` on Android 17 before either.

**Client side.** Settings › Other devices › Use another device for torrents: choose a found device or type its address, then the code. From then on `playback_url` asks the paired server for torrents and registers a loopback relay route; the player reads `http://127.0.0.1:<port>/<secret>/remote/<token>`, and the relay forwards each request with the bearer token, `Range` and `Accept-Encoding: identity`. Progress is polled in the background, so the loading screen and its peers and speed keep working, and it now also says which device downloads the torrent.

The client's HTTP client pins the certificate, uses no system proxy (it would see the token), follows no redirects (the server never sends any), sends no Referer, and connects with a 3 s timeout so a sleeping server is noticed quickly. Server addresses must be RFC 1918 IPv4; names are not accepted, so `PublicResolver` and DNS do not come into it.

**Falling back.** If the server cannot be reached when a torrent starts, or something at its address presents another certificate (its DHCP address went to another device, or it was reset), the client looks for it with mDNS for 2 s; if it answers at a new address, that address is saved. Otherwise the client downloads the torrent itself, and the loading screen says why: unreachable, no longer paired (removed there, or reset: another certificate and no sign of the server elsewhere), or the server is on mobile data. A certificate mismatch is read from the failed request's own error, so concurrent requests cannot take each other's answer. If the server goes away during playback, the next player request continues the same file on this device, unless "Only on Wi-Fi" forbids it right now. A server that lost a torrent (it restarted, or the lease expired while the client slept) gets it again once. The relay stops polling progress after 30 reads in a row find the torrent gone, and resumes when it adds the torrent back; a torrent stopped while it was still being added is stopped on the server too.

**Existing rules.**

- *Only on Wi-Fi* applies on each device to the torrents it downloads itself. The app tells the core when it is on mobile data; a server on mobile data with the setting on refuses new torrents (`503 torrents_paused`), and a client never takes over a torrent over mobile data with the setting on. The source picker still blocks torrents on mobile data on the client, since the server is out of reach from there anyway and the torrent would fall back to mobile data.
- *Cache size* is the server's own; it trims its cache when a device releases a torrent.
- *No Referer, redirect policy, `PublicResolver`* stay as they are for web sources. The device-to-device client sends no Referer and follows no redirects at all.

**SSRF.** The server has no route that fetches a URL. What a paired device controls is limited to tracker URLs. Only UDP trackers are announced to, for every torrent, local or remote: librqbit announces to HTTP trackers with its own HTTP client, which follows redirects through the system resolver and cannot be replaced, so `http://tracker.example/announce` could redirect into the server's network past every check on the URL. UDP trackers have no redirects, and together with DHT they find peers for public torrents. Tracker URLs with private, loopback, multicast or reserved IP literals, numeric forms the resolver would read as an address (`2130706433`, `0x7f.1`, `127.1`), and local names (`.local`, `.lan`, `.home.arpa`, `.internal`) are dropped. For torrents from paired devices, the server also resolves each tracker name, eight at a time, and drops it unless every address is public. Two gaps remain and are accepted: librqbit resolves the name again when announcing, so a name that changes its answer in between (DNS rebinding) can still send a UDP announce, a fixed binary packet, to a private address; and peers returned by public trackers or the DHT can be private addresses, which get a BitTorrent handshake. Both already exist for torrents the device plays itself, neither is an HTTP request, and a paired device is a device the user approved on the same network.

**Limits.** 8 connections per peer address. A new connection starts in a pool of 16 unauthenticated connections, shared with pairing; when it is full, its oldest connection is closed to make room, so hosts that open connections and send nothing cannot lock paired devices out. A connection's first authenticated request moves it to a pool of 32 reserved for paired devices. TLS handshake, request headers and request bodies each get 5 s, as on the loopback server; bodies are limited to 32 KiB; unauthorized requests get `401` and the connection is closed. The server's state is serialized and handed to the app under one lock, so saves arrive in order and a removed device cannot come back with a stale one. Removing a device ends its open streams, and a device is checked again before a torrent it asked for is recorded.

**Threat model.**

| Threat | Outcome |
| --- | --- |
| Another device on the network (guest, compromised IoT, a visitor's laptop) | Can reach the port and the pairing routes only, and learn whether a code is showing at most once a second. While a code is shown it gets 3 guesses at a 6-digit code, and all addresses together 10 (about 1 in 100 000). With several addresses it can spend a code's attempts; the server says another device tried, and the user shows a new code. |
| Guest Wi-Fi, café, hotel, office | A guest network is usually isolated from the main one. A server taken to another network stops listening there and says so; it serves again only at home or where the user explicitly chooses. |
| Eavesdropper on Wi-Fi | Sees TLS only. SPAKE2 gives it nothing to guess the code against. |
| ARP spoofing or a rogue access point (active attacker in the middle) | During pairing it would have to present its own certificate; the fingerprint the client proves would then not match the server's, and the server would not send a token. Running its own SPAKE2 with each side gives it one online guess per exchange. After pairing, the pin makes every connection fail rather than talk to it; the client looks for the server elsewhere, then reports it as "no longer paired" and falls back to local downloads. |
| A fake server announcing itself | Cannot pair a client without knowing the code the user reads on the real server: the client checks the server's proof. |
| A stolen client device | Its token works until the user presses **Remove** on the server. The token sits in the Keystore- or DPAPI-encrypted store. |
| A paired device misbehaving | Can download and stream torrents through the server, within the limits above, and nothing else: no proxy, no file access, no HTTP or private trackers. |
| Someone tracking devices by their announcements | Sees a name (neutral unless the user chose otherwise) and values that change every time serving starts. |
| The internet | Not reachable: no public listeners, public peers dropped, no UPnP. |

**Edge cases.**

| Case | Behavior |
| --- | --- |
| Server stops mid-film (turned off, app closed, crash) | The open response ends; the player's next request finds the server unreachable and the relay continues the same file on this device, from the same byte, unless "Only on Wi-Fi" forbids it. |
| Server's address changes (DHCP) | The server listens on the new address within 15 s and mDNS announces it. The client finds it by its tag at the next playback or Settings check and saves the new address; if another device got the old address, its different certificate makes the client look the same way. |
| Server taken to another network (a laptop at a café) | It pauses: no listener, no announcement. Settings says why and offers "Serve on this network". Back home it resumes by itself. |
| Two servers on the network | Both are listed by name and address; the code ties the pairing to the one the user reads it from. A client uses one at a time. |
| Client paired to a server that was reset | The new certificate fails the pin; the client looks for the server elsewhere, finds none with its id, says the device no longer recognizes it, falls back to local downloads, and offers Stop using to pair again. |
| Device removed on the server | Its token gets `401`; same as above. Its torrents stop unless someone else uses them. |
| Many clients | Up to 16 paired, 32 connections for paired devices plus 16 for the rest, 8 per address, 3 torrents and 3 streams per device; the engine's 8 stream slots are shared with the server's own player. Two devices on different files of one torrent: the later one wins and the earlier gets `409`, without the two switching back and forth (see Consequences). |
| Wi-Fi-only client, Ethernet server | Each device applies its own setting to what it downloads. The client on Wi-Fi uses the server; on mobile data the source picker blocks torrents as before, since the server is unreachable and the fallback would use mobile data. |
| TV server goes to sleep | Clients cannot connect (3 s) and download themselves, saying why. Torrents it held are released by their leases when it wakes. |
| Client goes to sleep mid-film | Its torrents on the server are released after 5 minutes without requests; when it wakes, the relay adds the torrent back once. |

What TLS adds over plain HTTP on a home network: tokens and the content cannot be read or replayed by someone sniffing the Wi-Fi, an attacker in the middle cannot hijack a paired session or feed the client other bytes, and together with SPAKE2 the first contact is protected too, not just the ones after it.

## Consequences

- One weak device can play torrents through a stronger one, and the player and its logs still only see loopback URLs.
- New dependencies in the core: `tokio-rustls` (already in the tree through reqwest), `rcgen`, `ring` (already in the tree through rustls), `spake2` (with `curve25519-dalek`), `mdns-sd` and `if-addrs`, and on Windows `windows-sys` (already in the tree through if-addrs and tokio) for adapter types, gateways and the gateway's MAC address. That last part is the only `unsafe` code in the torrent crate, confined to one module.
- HTTP and HTTPS trackers are no longer announced to, for any torrent. Torrents whose swarm is only reachable through an HTTP tracker, and not through DHT or UDP trackers, start slower or not at all.
- On Android the home network is identified by its configuration, not its router's MAC address, so a network set up exactly like home (same gateway, DHCP server, DNS and prefix) passes for it. The macOS network identity is untested.
- The server only serves while the app runs. On Android that means while it is open or recently in the background; a TV that goes to sleep stops serving and its clients fall back. A foreground service would keep a phone or TV serving with the screen off; it is not done yet.
- One torrent file per torrent at a time: two devices watching different episodes of the same season pack at once make the server switch files, and the device that started first gets an error and has to start again. Selecting several files per torrent would fix it.
- Servers on macOS and Linux lose their pairings on restart until their `SecureStore` persists.
- The Android emulator reaches its host's loopback as `10.0.2.2`, a private address, so a client in the emulator works unchanged. The host side needs a loopback listener, which production code never opens: `StreamServer::serve_loopback` exists only with the `dev-loopback` feature, which only `examples/serve.rs` enables.
