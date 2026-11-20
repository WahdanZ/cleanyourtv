"""HTTPS for the admin panel at home, and phone notifications (web push).

Phones treat a plain-http page as "not secure": no installed app, no notifications, no clipboard. So the helper
makes its own small certificate authority (data/tls/ca.crt), installed once on the phone, and with it a certificate
for this computer's address; the panel is then also at https://<address>:<port + 1>/admin.

Web push (RFC 8291 encryption, RFC 8292 VAPID): the phone's browser gives a subscription (its push service's address
and keys); a notification is encrypted for it and posted there (Google's or Apple's push service), which wakes the
phone even with the panel closed. Needs the "cryptography" package; without it, everything here stays off.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
import ssl
import struct
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    AVAILABLE = True
except ImportError:   # pip install cryptography
    AVAILABLE = False


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def unb64u(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _write_private(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    try:
        path.chmod(0o600)
    except OSError:
        pass


# ---------- certificates ----------
class Certs:
    """A certificate authority of this helper's own, and a server certificate for this computer's address."""

    def __init__(self, data: Path, log):
        self.dir = data / "tls"
        self.log = log

    @property
    def ca_file(self) -> Path:
        return self.dir / "ca.crt"

    def _ca(self):
        key_f = self.dir / "ca.key"
        if key_f.exists() and self.ca_file.exists():
            key = serialization.load_pem_private_key(key_f.read_bytes(), None)
            return key, x509.load_pem_x509_certificate(self.ca_file.read_bytes())
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Home helper (this home only)"),
                          x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Home helper")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number())
                .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=3650))
                .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True, content_commitment=False,
                                             key_encipherment=False, data_encipherment=False, key_agreement=False,
                                             encipher_only=False, decipher_only=False), critical=True)
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
                .sign(key, hashes.SHA256()))
        _write_private(key_f, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                serialization.NoEncryption()))
        self.ca_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        self.log("https: made this home's certificate authority (data/tls/ca.crt)")
        return key, cert

    def server(self, ip: str) -> tuple[Path, Path]:
        """The certificate for `ip` (made again when the address changed or it's about to run out)."""
        ca_key, ca_cert = self._ca()
        crt_f, key_f = self.dir / "server.crt", self.dir / "server.key"
        if crt_f.exists() and key_f.exists():
            cur = x509.load_pem_x509_certificate(crt_f.read_bytes())
            try:
                ips = cur.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.IPAddress)
            except x509.ExtensionNotFound:
                ips = []
            left = cur.not_valid_after_utc - datetime.datetime.now(datetime.timezone.utc)
            if ipaddress.ip_address(ip) in ips and left > datetime.timedelta(days=30):
                return crt_f, key_f
        key = ec.generate_private_key(ec.SECP256R1())
        now = datetime.datetime.now(datetime.timezone.utc)
        alt = [x509.IPAddress(ipaddress.ip_address(ip)), x509.IPAddress(ipaddress.ip_address("127.0.0.1")), x509.DNSName("localhost")]
        cert = (x509.CertificateBuilder()
                .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, ip)])).issuer_name(ca_cert.subject)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                # phones accept at most 825 days for a certificate like this
                .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=800))
                .add_extension(x509.SubjectAlternativeName(alt), critical=False)
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                .add_extension(x509.KeyUsage(digital_signature=True, key_encipherment=False, content_commitment=False,
                                             data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                             crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
                .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
                .sign(ca_key, hashes.SHA256()))
        _write_private(key_f, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                serialization.NoEncryption()))
        crt_f.write_bytes(cert.public_bytes(serialization.Encoding.PEM) + ca_cert.public_bytes(serialization.Encoding.PEM))
        self.log(f"https: certificate for {ip}")
        return crt_f, key_f

    def context(self, ip: str) -> ssl.SSLContext:
        crt, key = self.server(ip)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(str(crt), str(key))
        return ctx

    def ca_der(self) -> bytes:
        self._ca()
        return x509.load_pem_x509_certificate(self.ca_file.read_bytes()).public_bytes(serialization.Encoding.DER)


# ---------- web push ----------
class Push:
    def __init__(self, data: Path, log, contact: str = "mailto:helper@example.com"):
        self.dir = data / "push"
        self.log = log
        self.contact = contact
        self.lock = threading.Lock()
        self._key = None

    def _vapid(self):
        if self._key is None:
            f = self.dir / "vapid.pem"
            if f.exists():
                self._key = serialization.load_pem_private_key(f.read_bytes(), None)
            else:
                self._key = ec.generate_private_key(ec.SECP256R1())
                _write_private(f, self._key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                          serialization.NoEncryption()))
        return self._key

    def public_key(self) -> str:
        return b64u(self._vapid().public_key().public_bytes(serialization.Encoding.X962,
                                                            serialization.PublicFormat.UncompressedPoint))

    # subscriptions: one per phone (or browser) that switched notifications on
    def _subs_file(self) -> Path:
        return self.dir / "subs.json"

    def subs(self) -> list:
        try:
            v = json.loads(self._subs_file().read_text(encoding="utf-8"))
            return v if isinstance(v, list) else []
        except (OSError, ValueError):
            return []

    def _save(self, subs: list):
        self.dir.mkdir(parents=True, exist_ok=True)
        _write_private(self._subs_file(), json.dumps(subs, indent=1).encode())

    def add(self, sub: dict, label: str = "") -> int:
        ep = str(sub.get("endpoint", ""))
        keys = sub.get("keys") or {}
        if not ep.startswith("https://") or not keys.get("p256dh") or not keys.get("auth"):
            raise ValueError("not a push subscription")
        with self.lock:
            subs = [s for s in self.subs() if s["endpoint"] != ep]
            subs.append({"endpoint": ep, "keys": {"p256dh": keys["p256dh"], "auth": keys["auth"]},
                         "label": label[:80], "at": int(time.time())})
            self._save(subs[-10:])
            return len(subs)

    def remove(self, endpoint: str):
        with self.lock:
            self._save([s for s in self.subs() if s["endpoint"] != endpoint])

    def _jwt(self, endpoint: str) -> str:
        u = urlsplit(endpoint)
        head = b64u(json.dumps({"typ": "JWT", "alg": "ES256"}).encode())
        body = b64u(json.dumps({"aud": f"{u.scheme}://{u.netloc}", "exp": int(time.time()) + 12 * 3600,
                                "sub": self.contact}).encode())
        der = self._vapid().sign(f"{head}.{body}".encode(), ec.ECDSA(hashes.SHA256()))
        r, s = decode_dss_signature(der)
        return f"{head}.{body}.{b64u(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"

    @staticmethod
    def _encrypt(sub: dict, plain: bytes) -> bytes:
        """aes128gcm content encoding for one push message (RFC 8291 / RFC 8188, a single record)."""
        ua_pub = unb64u(sub["keys"]["p256dh"])
        auth = unb64u(sub["keys"]["auth"])
        eph = ec.generate_private_key(ec.SECP256R1())
        as_pub = eph.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        shared = eph.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), ua_pub))
        hk = lambda key, msg: hmac.new(key, msg, hashlib.sha256).digest()
        ikm = hk(hk(auth, shared), b"WebPush: info\x00" + ua_pub + as_pub + b"\x01")
        salt = os.urandom(16)
        prk = hk(salt, ikm)
        cek = hk(prk, b"Content-Encoding: aes128gcm\x00\x01")[:16]
        nonce = hk(prk, b"Content-Encoding: nonce\x00\x01")[:12]
        body = AESGCM(cek).encrypt(nonce, plain + b"\x02", None)
        return salt + struct.pack(">I", 4096) + bytes([len(as_pub)]) + as_pub + body

    def send(self, title: str, body: str = "", tag: str = "", url: str = "/admin", actions: list | None = None) -> int:
        """To every phone that switched notifications on; drops the ones their push service says are gone."""
        payload = json.dumps({"title": title, "body": body, "tag": tag, "url": url, "actions": actions or []},
                             ensure_ascii=False).encode()
        sent = 0
        for sub in self.subs():
            try:
                req = urllib.request.Request(sub["endpoint"], data=self._encrypt(sub, payload), method="POST", headers={
                    "Content-Encoding": "aes128gcm", "Content-Type": "application/octet-stream", "TTL": "3600",
                    "Urgency": "high", "Authorization": f"vapid t={self._jwt(sub['endpoint'])}, k={self.public_key()}"})
                with urllib.request.urlopen(req, timeout=15) as r:
                    sent += r.status in (200, 201, 202)
            except urllib.error.HTTPError as e:
                if e.code in (404, 410):   # unsubscribed, or the app was removed
                    self.remove(sub["endpoint"])
                self.log(f"push: {e.code} from {urlsplit(sub['endpoint']).netloc}")
            except Exception as e:
                self.log(f"push: {e}")
        return sent

    def send_later(self, *a, **k):
        threading.Thread(target=self.send, args=a, kwargs=k, daemon=True).start()
