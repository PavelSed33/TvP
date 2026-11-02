# coturn TURN relay

The API and both WebRTC peers support TURN REST credentials. Deploy coturn on a public hostname, configure the same strong shared secret in coturn and the API, and open the listener and relay port range on the firewall. This repository does not start a public TURN relay automatically; coturn network/NAT settings depend on the hosting provider.

Example `turnserver.conf` values (replace host, addresses, and secret):

```ini
listening-port=3478
tls-listening-port=5349
realm=turn.example.com
fingerprint
use-auth-secret
static-auth-secret=<same-secret-as-REMOTEFIX_TURN_SHARED_SECRET>
min-port=49160
max-port=49200
no-cli
no-multicast-peers
no-loopback-peers
```

Install valid TLS certificates for the TLS listener. If coturn sits behind NAT, configure its public/private address mapping according to coturn's deployment documentation. Open UDP and TCP 3478, TCP 5349, and the chosen UDP relay port range (49160–49200 in this example). Validate from a separate network before relying on it.

Set the following on the API container or service:

```sh
REMOTEFIX_TURN_URLS=turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp
REMOTEFIX_TURN_SHARED_SECRET=<at-least-32-random-characters>
REMOTEFIX_TURN_CREDENTIAL_TTL_SECONDS=3600
```

The API signs a coturn REST username containing an expiry time and session ID with HMAC-SHA1, as expected by coturn's `use-auth-secret` mode. It returns only the temporary username and credential to the two session peers, with expiry no later than the session deadline. Keep the long-lived secret outside the repository, APK, panel, and logs. Set `REMOTEFIX_TURN_URLS` only to working `turn:` or `turns:` endpoints.
