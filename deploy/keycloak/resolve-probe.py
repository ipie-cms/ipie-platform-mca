#!/usr/bin/env python3
"""Reproduces ipie-keycloak-spi's call to iam's /internal/pillar-links/resolve.

The login path for federated (pillar) SSO is the one contract no other test touches: Keycloak's
first-broker-login authenticator signs this request and decides the login on the answer. Driving the
whole brokered round trip needs a browser and the four mock-IdP hostnames in the *Windows* hosts
file, so this probe covers the half that is testable headlessly - the signed call itself, its HMAC
canonical string, the permission gate, and the projection lookup behind it.

Prints "linked" when a known identity resolves and "not-linked" when an unknown one does not; any
other output means the contract is broken. Kept beside the realm files because it is a fixture of
this deployment, not of any one service.
"""
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

IAM = os.environ.get("IAM_SVC", "http://127.0.0.1:8093")


def keycloak_base_url():
    """Keycloak runs on the Windows host and is reached differently depending on where this runs.

    From a container it is `keycloak:8080` via the host-gateway alias; from WSL that name does not
    resolve (the alias is a Docker feature and /etc/hosts usually lacks it), and `localhost:8080` is
    WSL's own loopback, not Windows'. The default route's gateway is Windows. Trying them in order
    keeps the probe runnable from either side without configuration; KEYCLOAK_BASE_URL overrides.
    """
    configured = os.environ.get("KEYCLOAK_BASE_URL")
    if configured:
        return configured
    candidates = ["http://keycloak:8080", "http://127.0.0.1:8080"]
    try:
        with open("/proc/net/route", encoding="utf-8") as routes:
            for line in routes.read().splitlines()[1:]:
                fields = line.split()
                if len(fields) > 2 and fields[1] == "00000000":
                    gateway = ".".join(str(int(fields[2][i:i + 2], 16)) for i in (6, 4, 2, 0))
                    candidates.insert(1, f"http://{gateway}:8080")
                    break
    except OSError:
        pass
    for candidate in candidates:
        try:
            urllib.request.urlopen(candidate + "/realms/ipie", timeout=3).read()
            return candidate
        except (urllib.error.HTTPError, urllib.error.URLError, OSError):
            continue
    return candidates[0]


KEYCLOAK = keycloak_base_url()
SECRET = os.environ.get("IPIE_SECURITY_HMAC_KEY_SPI_TO_IAM", "local-dev-spi-to-iam-shared-secret")
KEY_ID = os.environ.get("IPIE_HMAC_KEY_ID_IAM", "spi-to-iam")
PATH = "/internal/pillar-links/resolve"


def service_account_token():
    request = urllib.request.Request(
        f"{KEYCLOAK}/realms/ipie/protocol/openid-connect/token",
        data=b"grant_type=client_credentials&client_id=ipie-keycloak-spi"
             b"&client_secret=ipie-keycloak-spi-secret",
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    return json.load(urllib.request.urlopen(request, timeout=15))["access_token"]


def resolve(token, pillar_type, external_id):
    body = json.dumps({"pillarType": pillar_type, "externalPillarId": external_id}).encode()
    # Instant.toString() - HmacRequestSigner rejects epoch millis as an unparseable timestamp.
    timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    nonce = str(uuid.uuid4())
    canonical = "\n".join(["POST", PATH, timestamp, nonce, hashlib.sha256(body).hexdigest()])
    signature = hmac.new(SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    request = urllib.request.Request(IAM + PATH, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + token,
        "X-Signature": signature, "X-Timestamp": timestamp, "X-Nonce": nonce,
        "X-Signing-Key-Id": KEY_ID})
    return json.load(urllib.request.urlopen(request, timeout=15))


def main():
    try:
        token = service_account_token()
        known = resolve(token, "IBBI", "IBBI-00123")
        unknown = resolve(token, "IBBI", "IBBI-does-not-exist")
    except (urllib.error.HTTPError, urllib.error.URLError, KeyError) as failure:
        print(f"error: {failure}")
        return 1
    # Both halves matter: an endpoint that answered "linked" to everything would pass the first check
    # and let any brokered identity log in as somebody.
    if known.get("linked") and known.get("userId") and not unknown.get("linked"):
        print("linked")
        return 0
    print(f"unexpected: known={known} unknown={unknown}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
